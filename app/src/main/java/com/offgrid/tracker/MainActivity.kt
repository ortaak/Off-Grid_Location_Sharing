package com.offgrid.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.DashPathEffect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.offgrid.tracker.databinding.ActivityMainBinding
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: SecureStorage
    private lateinit var locationManager: LocationManager
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val cryptoExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var currentMapStyle: MapStyle = MapStyle.OSM
    private var compassOverlay: CompassOverlay? = null
    private var myMarker: Marker? = null
    private var myLastLocation: Location? = null
    private var hasCenteredOnUser = false

    private val peers = mutableListOf<PeerContact>()
    private val peerStates = LinkedHashMap<String, PeerState>()
    private val peerMarkers = HashMap<String, Marker>()
    private val peerLines = HashMap<String, Polyline>()

    private var lastManualSendClickMs = 0L
    private var suppressSwitchCallback = false
    private var activeDownloadTask: CacheManager.CacheManagerTask? = null

    private val refreshAgeRunnable = object : Runnable {
        override fun run() {
            if (peerStates.isNotEmpty()) {
                renderLivePeerChips()
                refreshAllPeerMarkers()
            }
            updateSharingSubtitle()
            uiHandler.postDelayed(this, 15_000L)
        }
    }

    // Rehberden izin istemeden (ACTION_PICK) tek kişi seçici
    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0)?.trim().orEmpty()
                val rawPhone = cursor.getString(1)?.trim().orEmpty()
                if (rawPhone.isNotEmpty()) {
                    addPeerToWhitelist(rawPhone, name)
                }
            }
        }
    }

    private val peerSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packet = intent?.getStringExtra(LocationSmsReceiver.EXTRA_PACKET) ?: return
            val sender = intent.getStringExtra(LocationSmsReceiver.EXTRA_SENDER) ?: return

            // 1) Katı Beyaz Liste Kontrolü: İzinli olmayan numaradan gelen paket çözülmeden reddedilir
            val matchedPeer = peers.firstOrNull { PhoneNumberUtils.matches(it.phone, sender) }
            if (matchedPeer == null) {
                showBanner(
                    "Güvenlik: İzinli listede olmayan numaradan ($sender) gelen paket engellendi.",
                    isError = true
                )
                return
            }

            val secret = binding.secretKeyInput.text?.toString().orEmpty()
            if (secret.length < CryptoHelper.MIN_PASSPHRASE_LENGTH) {
                showBanner(
                    "${matchedPeer.displayName} konum gönderdi ancak geçerli ortak anahtar girili değil.",
                    isError = true
                )
                return
            }

            // 2) PBKDF2 + AES-GCM çözme ve Replay korumasını arka plan thread'inde çalıştır
            cryptoExecutor.execute {
                val decryptResult = CryptoHelper.decryptLocation(packet, secret, matchedPeer.phone)
                runOnUiThread {
                    when (decryptResult) {
                        is DecryptResult.Success -> {
                            val p = decryptResult.packet
                            val state = PeerState(
                                contact = matchedPeer,
                                latitude = p.latitude,
                                longitude = p.longitude,
                                speedKmh = p.speedKmh,
                                packetTimestampSec = p.timestampSec,
                                receivedAtMs = System.currentTimeMillis()
                            )
                            peerStates[matchedPeer.canonicalKey] = state
                            renderPeerOnMap(state, animateToPeer = true)
                            renderLivePeerChips()
                            showBanner("${matchedPeer.displayName} konumu güncellendi.")
                        }
                        is DecryptResult.ReplayDetected -> {
                            showBanner("Güvenlik: ${decryptResult.reason}", isError = true)
                        }
                        is DecryptResult.InvalidKeyOrCorrupted -> {
                            showBanner(
                                "${matchedPeer.displayName}: Anahtar uyuşmuyor veya paket bozuk!",
                                isError = true
                            )
                        }
                        is DecryptResult.InvalidPayload -> {
                            showBanner("Geçersiz konum paketi: ${decryptResult.reason}", isError = true)
                        }
                    }
                }
            }
        }
    }

    private val serviceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra(LocationTrackingService.EXTRA_EVENT_TYPE) ?: return
            val message = intent.getStringExtra(LocationTrackingService.EXTRA_MESSAGE).orEmpty()

            when (type) {
                LocationTrackingService.EVENT_STARTED -> {
                    setSwitchSilently(true)
                    updateSharingSubtitle()
                    showBanner(message)
                }
                LocationTrackingService.EVENT_STOPPED -> {
                    setSwitchSilently(false)
                    updateSharingSubtitle()
                }
                LocationTrackingService.EVENT_SENT -> {
                    updateSharingSubtitle()
                    showBanner(message)
                }
                LocationTrackingService.EVENT_ERROR -> {
                    if (!LocationTrackingService.isRunning) {
                        setSwitchSilently(false)
                    }
                    updateSharingSubtitle()
                    showBanner(message, isError = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ekran görüntüsü ve son uygulamalar önizleme engeli (FLAG_SECURE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // OSMDroid yapılandırması mutlaka MapView inflate edilmeden ÖNCE yapılmalıdır!
        OfflineMapManager.initialize(this)

        storage = SecureStorage(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupBottomSheet()
        loadSavedSettings()
        setupInteractions()
        registerInternalReceivers()
        checkAndRequestPermissions()
    }

    private fun setupMap() {
        currentMapStyle = MapStyle.fromId(storage.getMapStyle())

        binding.mapView.apply {
            OfflineMapManager.applyMapStyle(this, currentMapStyle)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(39.9208, 32.8541)) // Varsayılan: Ankara

            // Ölçek çubuğu
            val scaleBar = ScaleBarOverlay(this).apply {
                setCentred(false)
                setScaleBarOffset(28, 190)
            }
            overlays.add(scaleBar)

            // Pusula katmanı
            compassOverlay = CompassOverlay(
                this@MainActivity,
                InternalCompassOrientationProvider(this@MainActivity),
                this
            ).apply {
                enableCompass()
                setCompassCenter(36f, 115f)
            }
            overlays.add(compassOverlay)
        }
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun loadSavedSettings() {
        // İzinli numaraları yükle
        peers.clear()
        peers.addAll(storage.getPeers())
        renderWhitelistChips()
        renderLivePeerChips()

        // Şifreli anahtarı Keystore'dan yükle
        val savedKey = storage.getPassphrase()
        if (savedKey.isNotEmpty()) {
            binding.secretKeyInput.setText(savedKey)
        }
        updateKeyStrengthUI(savedKey)

        // Gönderim aralığını yükle
        when (storage.getIntervalSeconds()) {
            30 -> binding.chipInterval30s.isChecked = true
            60 -> binding.chipInterval60s.isChecked = true
            180 -> binding.chipInterval180s.isChecked = true
            300 -> binding.chipInterval300s.isChecked = true
            0 -> binding.chipIntervalManual.isChecked = true
            else -> binding.chipInterval60s.isChecked = true
        }

        setSwitchSilently(LocationTrackingService.isRunning)
        updateSharingSubtitle()
        updateCacheInfo()
    }

    private fun setupInteractions() {
        // Üst telemetri kartına tıklayınca mevcut koordinatları kopyala
        binding.telemetryInfoContainer.setOnClickListener {
            val loc = myLastLocation
            if (loc != null) {
                val coords = String.format(Locale.US, "%.6f, %.6f", loc.latitude, loc.longitude)
                copyToClipboard("GPS Koordinat", coords, sensitive = false)
                showBanner("Koordinat kopyalandı: $coords")
            } else {
                showBanner("Henüz GPS sinyali alınmadı.")
            }
        }

        // Güvenlik rozetine tıklayınca aktif koruma özetini göster
        binding.securityBadge.setOnClickListener { showSecuritySummaryDialog() }

        // Tutma çubuğuna tıklayınca paneli aç/kapat
        binding.dragHandle.setOnClickListener {
            bottomSheetBehavior.state =
                if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    BottomSheetBehavior.STATE_COLLAPSED
                } else {
                    BottomSheetBehavior.STATE_EXPANDED
                }
        }

        // Sağ Harita FAB Butonları
        binding.fabMyLocation.setOnClickListener {
            val loc = myLastLocation
            if (loc != null) {
                binding.mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), 16.0, 500L)
            } else {
                showBanner("GPS uydusu henüz kilitlenmedi. Açık alanda bekleyin.")
            }
        }

        binding.fabFitGroup.setOnClickListener { fitGroupOnMap() }
        binding.fabMapLayers.setOnClickListener { showMapLayerDialog() }
        binding.fabSendNow.setOnClickListener { handleInstantSend() }

        // Canlı Paylaşım Anahtarı (Switch)
        binding.shareSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!validateReadyToShare()) {
                    setSwitchSilently(false)
                    return@setOnCheckedChangeListener
                }
                startTrackingService()
            } else {
                stopTrackingService()
            }
        }

        // Beyaz Liste Numara Ekleme
        binding.btnAddPeer.setOnClickListener {
            val raw = binding.phoneInput.text?.toString().orEmpty()
            addPeerToWhitelist(raw, "")
        }

        binding.phoneInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addPeerToWhitelist(binding.phoneInput.text?.toString().orEmpty(), "")
                true
            } else false
        }

        binding.btnPickContact.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            try {
                contactPickerLauncher.launch(intent)
            } catch (_: Exception) {
                showBanner("Rehber uygulaması açılamadı.", isError = true)
            }
        }

        // Ortak Anahtar Değişimi & Güç Ölçer
        binding.secretKeyInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                updateKeyStrengthUI(text)
                storage.savePassphrase(text)
            }
        })

        binding.btnGenerateKey.setOnClickListener {
            val generated = CryptoHelper.generateStrongPassphrase()
            binding.secretKeyInput.setText(generated)
            storage.savePassphrase(generated)
            showBanner("16 karakterli yüksek güvenli anahtar oluşturuldu.")
        }

        binding.btnCopyKey.setOnClickListener {
            val key = binding.secretKeyInput.text?.toString().orEmpty()
            if (key.length < CryptoHelper.MIN_PASSPHRASE_LENGTH) {
                showBanner("Önce en az ${CryptoHelper.MIN_PASSPHRASE_LENGTH} karakterli bir anahtar belirleyin.", isError = true)
                return@setOnClickListener
            }
            copyToClipboard("OffGrid Grup Anahtarı", key, sensitive = true)
            showBanner("Anahtar güvenli olarak panoya kopyalandı.")
        }

        // Paylaşım Sıklığı Seçimi
        binding.intervalChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val seconds = when (selectedId) {
                R.id.chipInterval30s -> 30
                R.id.chipInterval60s -> 60
                R.id.chipInterval180s -> 180
                R.id.chipInterval300s -> 300
                R.id.chipIntervalManual -> 0
                else -> 60
            }
            storage.saveIntervalSeconds(seconds)
            updateSharingSubtitle()
        }

        // Çevrimdışı Harita İndir & Verileri Sıfırla
        binding.btnOfflineDownload.setOnClickListener { startOfflineRegionDownload() }
        binding.btnClearData.setOnClickListener { confirmClearAllData() }
    }

    private fun addPeerToWhitelist(rawPhone: String, label: String) {
        if (!PhoneNumberUtils.isValidPhoneNumber(rawPhone)) {
            binding.phoneInputLayout.error = "Geçerli bir telefon numarası girin (örn. +905...)"
            return
        }
        binding.phoneInputLayout.error = null
        val normalized = PhoneNumberUtils.normalize(rawPhone)

        if (peers.any { PhoneNumberUtils.matches(it.phone, normalized) }) {
            showBanner("Bu numara zaten izinli listede.")
            binding.phoneInput.text?.clear()
            return
        }

        val color = SecureStorage.PEER_COLORS[peers.size % SecureStorage.PEER_COLORS.size]
        peers.add(PeerContact(phone = normalized, label = label, colorInt = color))
        storage.savePeers(peers)

        binding.phoneInput.text?.clear()
        renderWhitelistChips()
        renderLivePeerChips()
        updateSharingSubtitle()
        showBanner("${label.ifBlank { PhoneNumberUtils.formatForDisplay(normalized) }} izinli listeye eklendi.")
    }

    private fun removePeerFromWhitelist(contact: PeerContact) {
        peers.removeAll { it.canonicalKey == contact.canonicalKey }
        storage.savePeers(peers)

        peerStates.remove(contact.canonicalKey)
        peerMarkers.remove(contact.canonicalKey)?.let { binding.mapView.overlays.remove(it) }
        peerLines.remove(contact.canonicalKey)?.let { binding.mapView.overlays.remove(it) }
        binding.mapView.invalidate()

        renderWhitelistChips()
        renderLivePeerChips()
        updateSharingSubtitle()

        if (peers.isEmpty() && LocationTrackingService.isRunning) {
            stopTrackingService()
        }
    }

    private fun renderWhitelistChips() {
        binding.whitelistChipGroup.removeAllViews()
        binding.whitelistCountBadge.text = "${peers.size} kişi"

        for (peer in peers) {
            val chip = Chip(this).apply {
                text = peer.displayName
                isCloseIconVisible = true
                chipStrokeWidth = 2f
                chipStrokeColor = ColorStateList.valueOf(peer.colorInt)
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.bg_surface_elevated)
                )
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setOnCloseIconClickListener { removePeerFromWhitelist(peer) }
                setOnClickListener {
                    peerStates[peer.canonicalKey]?.let { state ->
                        focusOnPeer(state)
                    } ?: showBanner("${peer.displayName} kişisinden henüz konum alınmadı.")
                }
            }
            binding.whitelistChipGroup.addView(chip)
        }
    }

    private fun renderLivePeerChips() {
        binding.livePeersChipGroup.removeAllViews()
        if (peers.isEmpty()) {
            binding.emptyPeersHint.visibility = View.VISIBLE
            return
        }
        binding.emptyPeersHint.visibility = View.GONE

        for (peer in peers) {
            val state = peerStates[peer.canonicalKey]
            val chipText = if (state != null) {
                "${peer.displayName} • ${state.formatDistanceAndBearing(myLastLocation)}"
            } else {
                "${peer.displayName} • Sinyal yok"
            }

            val chip = Chip(this).apply {
                text = chipText
                isClickable = true
                chipStrokeWidth = if (state != null) 2.5f else 1f
                chipStrokeColor = ColorStateList.valueOf(
                    if (state != null) peer.colorInt else ContextCompat.getColor(this@MainActivity, R.color.border_subtle)
                )
                chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this@MainActivity, R.color.bg_surface_elevated)
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (state != null) R.color.text_primary else R.color.text_secondary
                    )
                )
                setOnClickListener {
                    if (state != null) {
                        focusOnPeer(state)
                    } else {
                        showBanner("${peer.displayName} henüz şifreli konum paylaşmadı.")
                    }
                }
            }
            binding.livePeersChipGroup.addView(chip)
        }
    }

    private fun updateKeyStrengthUI(phrase: String) {
        val eval = CryptoHelper.evaluatePassphrase(phrase)
        binding.keyStrengthBar.setProgressCompat(eval.score, true)
        binding.keyStrengthText.text = eval.feedback

        val colorRes = when (eval.level) {
            StrengthLevel.TOO_SHORT -> R.color.accent_rose
            StrengthLevel.WEAK -> R.color.accent_amber
            StrengthLevel.MEDIUM -> R.color.accent_cyan
            StrengthLevel.STRONG -> R.color.accent_emerald
        }
        val color = ContextCompat.getColor(this, colorRes)
        binding.keyStrengthBar.setIndicatorColor(color)
        binding.keyStrengthText.setTextColor(color)

        binding.secretInputLayout.error = if (phrase.isNotEmpty() && !eval.isValid) {
            "En az ${CryptoHelper.MIN_PASSPHRASE_LENGTH} karakter zorunludur"
        } else {
            null
        }
    }

    private fun validateReadyToShare(): Boolean {
        if (peers.isEmpty()) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            binding.phoneInputLayout.error = "Önce en az bir izinli telefon numarası ekleyin"
            binding.phoneInput.requestFocus()
            showBanner("Paylaşım için en az 1 izinli numara eklemelisiniz.", isError = true)
            return false
        }
        val secret = binding.secretKeyInput.text?.toString().orEmpty()
        if (secret.length < CryptoHelper.MIN_PASSPHRASE_LENGTH) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            binding.secretInputLayout.error = "En az ${CryptoHelper.MIN_PASSPHRASE_LENGTH} karakterli anahtar gerekli"
            binding.secretKeyInput.requestFocus()
            showBanner("Güvenlik gereği en az ${CryptoHelper.MIN_PASSPHRASE_LENGTH} karakterli anahtar belirleyin.", isError = true)
            return false
        }
        if (!hasCorePermissions()) {
            checkAndRequestPermissions()
            showBanner("Konum ve SMS izinlerini onaylayın.", isError = true)
            return false
        }
        storage.savePassphrase(secret)
        storage.savePeers(peers)
        return true
    }

    private fun startTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun handleInstantSend() {
        val now = System.currentTimeMillis()
        if (now - lastManualSendClickMs < 5_000L) {
            showBanner("Yeni gönderim için birkaç saniye bekleyin.")
            return
        }
        if (!validateReadyToShare()) return
        lastManualSendClickMs = now

        val intent = Intent(this, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_SEND_NOW
            myLastLocation?.let { loc ->
                putExtra(LocationTrackingService.EXTRA_MANUAL_LAT, loc.latitude)
                putExtra(LocationTrackingService.EXTRA_MANUAL_LON, loc.longitude)
                putExtra(LocationTrackingService.EXTRA_MANUAL_SPEED, loc.speed)
            }
        }
        startService(intent)
        showBanner("${peers.size} kişiye şifreli konum paketi hazırlanıyor...")
    }

    private fun updateSharingSubtitle() {
        val intervalLabel = when (storage.getIntervalSeconds()) {
            30 -> "30 sn"
            60 -> "1 dk"
            180 -> "3 dk"
            300 -> "5 dk"
            0 -> "Manuel"
            else -> "${storage.getIntervalSeconds()} sn"
        }
        if (LocationTrackingService.isRunning) {
            val lastMs = LocationTrackingService.lastSentTimeMs
            val sentStr = if (lastMs > 0L) {
                val sec = ((System.currentTimeMillis() - lastMs) / 1000L).coerceAtLeast(0L)
                if (sec < 15) "Son: Az önce" else if (sec < 60) "Son: ${sec}sn önce" else "Son: ${sec / 60}dk önce"
            } else {
                "Uydu bekleniyor"
            }
            binding.sharingStatusText.text = "Aktif ($intervalLabel) • ${peers.size} kişi • $sentStr"
            binding.sharingStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
        } else {
            binding.sharingStatusText.text = "Beklemede • ${peers.size} izinli kişi • Sıklık: $intervalLabel"
            binding.sharingStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun renderPeerOnMap(state: PeerState, animateToPeer: Boolean) {
        val peerPoint = GeoPoint(state.latitude, state.longitude)
        val key = state.contact.canonicalKey

        val marker = peerMarkers.getOrPut(key) {
            Marker(binding.mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                val drawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker_peer)?.mutate()
                if (drawable != null) {
                    DrawableCompat.setTint(drawable, state.contact.colorInt)
                    icon = drawable
                }
                binding.mapView.overlays.add(this)
            }
        }

        marker.position = peerPoint
        marker.title = state.contact.displayName
        marker.snippet = String.format(
            Locale.getDefault(),
            "Hız: %.1f km/s | %s | %s",
            state.speedKmh,
            state.formatDistanceAndBearing(myLastLocation),
            state.formatAge()
        )

        // Kendi konumumuz varsa eşleşen kişiye kesikli çizgi çiz
        myLastLocation?.let { myLoc ->
            val line = peerLines.getOrPut(key) {
                Polyline(binding.mapView).apply {
                    outlinePaint.color = state.contact.colorInt
                    outlinePaint.strokeWidth = 6f
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
                    binding.mapView.overlays.add(0, this)
                }
            }
            line.setPoints(listOf(GeoPoint(myLoc.latitude, myLoc.longitude), peerPoint))
        }

        if (animateToPeer) {
            marker.showInfoWindow()
            binding.mapView.controller.animateTo(peerPoint)
        }
        binding.mapView.invalidate()
    }

    private fun refreshAllPeerMarkers() {
        for (state in peerStates.values) {
            renderPeerOnMap(state, animateToPeer = false)
        }
    }

    private fun focusOnPeer(state: PeerState) {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        val pt = GeoPoint(state.latitude, state.longitude)
        binding.mapView.controller.animateTo(pt, 16.0, 500L)
        peerMarkers[state.contact.canonicalKey]?.showInfoWindow()
    }

    private fun fitGroupOnMap() {
        val points = mutableListOf<GeoPoint>()
        myLastLocation?.let { points.add(GeoPoint(it.latitude, it.longitude)) }
        peerStates.values.forEach { points.add(GeoPoint(it.latitude, it.longitude)) }

        when {
            points.isEmpty() -> showBanner("Haritada gösterilecek aktif konum henüz yok.")
            points.size == 1 -> binding.mapView.controller.animateTo(points.first(), 16.0, 500L)
            else -> {
                val box = BoundingBox.fromGeoPointsSafe(points)
                binding.mapView.zoomToBoundingBox(box.increaseByScale(1.35f), true, 120)
            }
        }
    }

    private fun showMapLayerDialog() {
        val styles = MapStyle.values()
        val labels = styles.map { "${it.displayName} — ${it.description}" }.toTypedArray()
        val currentIdx = styles.indexOf(currentMapStyle).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Harita Stili & Çevrimdışı Önbellek")
            .setSingleChoiceItems(labels, currentIdx) { dialog, which ->
                currentMapStyle = styles[which]
                storage.saveMapStyle(currentMapStyle.id)
                OfflineMapManager.applyMapStyle(binding.mapView, currentMapStyle)
                dialog.dismiss()
                updateCacheInfo()
                showBanner("Harita stili: ${currentMapStyle.displayName}")
            }
            .setNeutralButton("Önbelleği Temizle") { _, _ ->
                OfflineMapManager.clearTileCache(this)
                updateCacheInfo()
                showBanner("Çevrimdışı harita önbelleği temizlendi.")
            }
            .setPositiveButton("Bölgeyi Çevrimdışı İndir") { _, _ ->
                startOfflineRegionDownload()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun startOfflineRegionDownload() {
        val (estimated, minZoom, maxZoom) = OfflineMapManager.estimateDownloadTileCount(binding.mapView)
        if (estimated > OfflineMapManager.MAX_OFFLINE_TILES_PER_DOWNLOAD) {
            showBanner(
                "Seçili alan çok geniş ($estimated karo). Haritayı biraz yakınlaştırıp tekrar deneyin.",
                isError = true
            )
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Çevrimdışı Harita İndirme")
            .setMessage(
                "Ekranda görünen bölge (${currentMapStyle.displayName}, yakınlaştırma $minZoom-$maxZoom, yaklaşık $estimated harita karosu) cihazınıza kaydedilecek.\n\nİndirme sırasında internet bağlantısı gerekir; sonrasında bu alan tamamen internetsiz çalışır."
            )
            .setPositiveButton("İndir") { _, _ ->
                val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = false
                    max = estimated.coerceAtLeast(1)
                    setPadding(48, 32, 48, 16)
                }
                val progressDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("Harita İndiriliyor...")
                    .setMessage("0 / $estimated karo")
                    .setView(progressBar)
                    .setCancelable(false)
                    .setNegativeButton("İptal") { _, _ ->
                        activeDownloadTask?.cancel(true)
                        activeDownloadTask = null
                    }
                    .show()

                activeDownloadTask = OfflineMapManager.downloadVisibleRegion(
                    mapView = binding.mapView,
                    onProgress = { done, total ->
                        runOnUiThread {
                            progressBar.max = total.coerceAtLeast(1)
                            progressBar.progress = done
                            progressDialog.setMessage("$done / $total harita karosu indirildi")
                        }
                    },
                    onComplete = { downloaded, errors ->
                        runOnUiThread {
                            progressDialog.dismiss()
                            activeDownloadTask = null
                            updateCacheInfo()
                            if (errors == 0) {
                                showBanner("Bölge çevrimdışı kullanım için kaydedildi ($downloaded karo).")
                            } else {
                                showBanner("İndirme bitti ($downloaded başarılı, $errors hatalı — internet bağlantınızı kontrol edin).", isError = true)
                            }
                        }
                    },
                    onTooLarge = { count ->
                        runOnUiThread {
                            progressDialog.dismiss()
                            showBanner("Seçili alan çok geniş ($count karo). Haritayı biraz yakınlaştırıp tekrar deneyin.", isError = true)
                        }
                    }
                )
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showSecuritySummaryDialog() {
        val keyEval = CryptoHelper.evaluatePassphrase(binding.secretKeyInput.text?.toString().orEmpty())
        val summary = buildString {
            appendLine("• Şifreleme: AES-256-GCM + AAD Başlık Doğrulama")
            appendLine("• Anahtar Türetme: PBKDF2-HMAC-SHA256 (65.536 tur + paket başı rastgele Salt)")
            appendLine("• Tekrar (Replay) Koruması: Aktif (Zaman damgası + IV önbelleği)")
            appendLine("• Gönderici Beyaz Listesi: Aktif (${peers.size} izinli numara)")
            appendLine("• Ekran Güvenliği (FLAG_SECURE): Aktif (Ekran görüntüsü engelli)")
            appendLine("• Yerel Depolama: Android Keystore Donanım Şifreli")
            appendLine("• Ortak Anahtar Durumu: ${if (keyEval.isValid) keyEval.feedback else "Henüz geçerli anahtar yok"}")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Aktif Güvenlik Korumaları")
            .setMessage(summary)
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun confirmClearAllData() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Tüm Verileri Sıfırla")
            .setMessage("Kayıtlı ortak anahtar, izinli numara listesi ve haritadaki konum geçmişi tamamen silinecek. Emin misiniz?")
            .setPositiveButton("Sil ve Sıfırla") { _, _ ->
                if (LocationTrackingService.isRunning) {
                    stopTrackingService()
                }
                storage.clearAll()
                peers.clear()
                peerStates.clear()
                peerMarkers.values.forEach { binding.mapView.overlays.remove(it) }
                peerLines.values.forEach { binding.mapView.overlays.remove(it) }
                peerMarkers.clear()
                peerLines.clear()
                binding.mapView.invalidate()

                binding.secretKeyInput.text?.clear()
                binding.phoneInput.text?.clear()
                renderWhitelistChips()
                renderLivePeerChips()
                updateKeyStrengthUI("")
                updateSharingSubtitle()
                showBanner("Tüm anahtar ve kişi verileri güvenle silindi.")
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun updateCacheInfo() {
        val sizeStr = OfflineMapManager.getCacheSizeFormatted(this)
        binding.cacheInfoText.text = "Çevrimdışı harita önbelleği: $sizeStr • Stil: ${currentMapStyle.displayName}"
    }

    private fun copyToClipboard(label: String, text: String, sensitive: Boolean) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    private fun setSwitchSilently(checked: Boolean) {
        suppressSwitchCallback = true
        binding.shareSwitch.isChecked = checked
        suppressSwitchCallback = false
    }

    private fun showBanner(message: String, isError: Boolean = false) {
        val snackbar = Snackbar.make(binding.rootCoordinator, message, Snackbar.LENGTH_SHORT)
        snackbar.setBackgroundTint(
            ContextCompat.getColor(
                this,
                if (isError) R.color.accent_rose else R.color.bg_surface_elevated
            )
        )
        snackbar.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        snackbar.show()
    }

    private fun registerInternalReceivers() {
        val smsFilter = IntentFilter(LocationSmsReceiver.ACTION_PEER_LOCATION_UPDATE)
        val serviceFilter = IntentFilter(LocationTrackingService.ACTION_SERVICE_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(peerSmsReceiver, smsFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(serviceEventReceiver, serviceFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(peerSmsReceiver, smsFilter)
            registerReceiver(serviceEventReceiver, serviceFilter)
        }
    }

    private fun hasCorePermissions(): Boolean {
        val core = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        return core.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startLocalGpsListener()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocalGpsListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            locationManager.removeUpdates(this)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2500L,
                    2f,
                    this
                )
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    onLocationChanged(it)
                }
            } else {
                updateGpsStatusUI(locked = false, accuracy = null, disabled = true)
            }
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        myLastLocation = location
        val myPoint = GeoPoint(location.latitude, location.longitude)

        if (myMarker == null) {
            myMarker = Marker(binding.mapView).apply {
                title = "Benim Konumum"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_marker_me)
                binding.mapView.overlays.add(this)
            }
        }
        myMarker?.position = myPoint

        if (!hasCenteredOnUser) {
            hasCenteredOnUser = true
            binding.mapView.controller.animateTo(myPoint, 16.0, 600L)
        }

        val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f)
        updateGpsStatusUI(locked = true, accuracy = location.accuracy, disabled = false)
        binding.gpsTelemetrySub.text = String.format(
            Locale.getDefault(),
            "%.5f, %.5f • %.1f km/s",
            location.latitude, location.longitude, speedKmh
        )

        // Tüm eşleşen kişilerin mesafe ve bağlantı çizgilerini güncelle
        if (peerStates.isNotEmpty()) {
            refreshAllPeerMarkers()
            renderLivePeerChips()
        }
        binding.mapView.invalidate()
    }

    private fun updateGpsStatusUI(locked: Boolean, accuracy: Float?, disabled: Boolean) {
        val dotColorRes = when {
            disabled -> R.color.accent_rose
            locked -> R.color.accent_emerald
            else -> R.color.accent_amber
        }
        binding.gpsStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))

        binding.gpsStatusTitle.text = when {
            disabled -> getString(R.string.status_gps_disabled)
            locked && accuracy != null -> String.format(Locale.getDefault(), "GPS Kilitli (±%.0f m)", accuracy)
            locked -> getString(R.string.status_gps_locked)
            else -> getString(R.string.status_gps_searching)
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            updateGpsStatusUI(locked = myLastLocation != null, accuracy = myLastLocation?.accuracy, disabled = false)
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            updateGpsStatusUI(locked = false, accuracy = null, disabled = true)
        }
    }

    @Deprecated("Deprecated in Android API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && hasCorePermissions()) {
            startLocalGpsListener()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        compassOverlay?.enableCompass()
        setSwitchSilently(LocationTrackingService.isRunning)
        updateSharingSubtitle()
        updateCacheInfo()
        if (hasCorePermissions()) {
            startLocalGpsListener()
        }
        uiHandler.post(refreshAgeRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshAgeRunnable)
        compassOverlay?.disableCompass()
        binding.mapView.onPause()
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        activeDownloadTask?.cancel(true)
        uiHandler.removeCallbacks(refreshAgeRunnable)
        cryptoExecutor.shutdown()
        try {
            unregisterReceiver(peerSmsReceiver)
            unregisterReceiver(serviceEventReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
