package com.offgrid.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var mapView: MapView
    private lateinit var locationManager: LocationManager
    private lateinit var phoneInput: EditText
    private lateinit var secretKeyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var shareSwitch: Switch

    private var myMarker: Marker? = null
    private var peerMarker: Marker? = null
    private var lastSentTime: Long = 0L
    private var myLastLocation: Location? = null

    // Canlı paylaşım periyodu (Hareket halindeyken her 30 saniyede bir günceller)
    private val SEND_INTERVAL_MS = 30_000L

    private val peerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packet = intent?.getStringExtra("packet") ?: return
            val sender = intent.getStringExtra("sender") ?: ""
            val secret = secretKeyInput.text.toString().ifBlank { "varsayilan123" }

            val decoded = CryptoHelper.decryptLocation(packet, secret)
            if (decoded != null) {
                val (lat, lon, speed) = decoded
                updatePeerOnMap(lat, lon, speed, sender)
            } else {
                Toast.makeText(this@MainActivity, "Şifreli konum alındı ancak Eşleşme Anahtarı uyuşmuyor!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid çevrimdışı yapılandırması
        Configuration.getInstance().userAgentValue = packageName
        setupUI()
        checkAndRequestPermissions()

        val filter = IntentFilter("com.offgrid.tracker.PEER_LOCATION_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(peerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(peerReceiver, filter)
        }
    }

    private fun setupUI() {
        val root = FrameLayout(this)

        // Harita Görünümü
        mapView = MapView(this).apply {
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(39.9208, 32.8541))
        }
        root.addView(mapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Üst Kontrol Paneli (Kullanıcı Dostu Eşleşme Kartı)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xE61E293B.toInt()) // Koyu Yarı Saydam Panel
        }

        phoneInput = EditText(this).apply {
            hint = "Eşleşecek Telefon No (+905...)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
        }

        secretKeyInput = EditText(this).apply {
            hint = "Ortak Şifreleme Anahtarı (Örn: Kamp2026)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
        }

        shareSwitch = Switch(this).apply {
            text = "Canlı Konum Paylaşımını Başlat (İnternetsiz)"
            setTextColor(0xFF38BDF8.toInt())
        }

        statusText = TextView(this).apply {
            text = "Durum: Uydu (GPS) sinyali bekleniyor..."
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(0, 16, 0, 0)
        }

        panel.addView(phoneInput)
        panel.addView(secretKeyInput)
        panel.addView(shareSwitch)
        panel.addView(statusText)

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP }

        root.addView(panel, panelParams)
        setContentView(root)
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            startGpsTracking()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Doğrudan GPS Uydusu (İnternet Gerektirmez)
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            3000L, // 3 saniyede bir uydu kontrolü
            2f,    // En az 2 metre hareket
            this
        )
    }

    override fun onLocationChanged(location: Location) {
        myLastLocation = location
        val myPoint = GeoPoint(location.latitude, location.longitude)

        if (myMarker == null) {
            myMarker = Marker(mapView).apply {
                title = "Benim Konumum"
                mapView.overlays.add(this)
            }
            mapView.controller.animateTo(myPoint)
        }
        myMarker?.position = myPoint
        mapView.invalidate()

        val speedKmh = location.speed * 3.6f
        statusText.text = String.format(
            Locale.getDefault(),
            "Uydu Aktif | Konum: %.5f, %.5f | Hız: %.1f km/s",
            location.latitude, location.longitude, speedKmh
        )

        // Canlı paylaşım açıksa ve süre dolduysa şifreli paketi gönder
        val now = System.currentTimeMillis()
        if (shareSwitch.isChecked && (now - lastSentTime >= SEND_INTERVAL_MS)) {
            val targetPhone = phoneInput.text.toString().trim()
            val secret = secretKeyInput.text.toString().ifBlank { "varsayilan123" }
            if (targetPhone.isNotEmpty()) {
                sendEncryptedLocation(targetPhone, location.latitude, location.longitude, speedKmh, secret)
                lastSentTime = now
            }
        }
    }

    private fun sendEncryptedLocation(phone: String, lat: Double, lon: Double, speed: Float, secret: String) {
        try {
            val encryptedPacket = CryptoHelper.encryptLocation(lat, lon, speed, secret)
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, encryptedPacket, null, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Gönderim hatası: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePeerOnMap(lat: Double, lon: Double, speed: Float, sender: String) {
        val peerPoint = GeoPoint(lat, lon)
        if (peerMarker == null) {
            peerMarker = Marker(mapView).apply {
                mapView.overlays.add(this)
            }
        }
        peerMarker?.position = peerPoint

        // Aradaki mesafeyi hesapla
        val distanceInfo = myLastLocation?.let {
            val results = FloatArray(1)
            Location.distanceBetween(it.latitude, it.longitude, lat, lon, results)
            "Mesafe: %.0f m".format(results[0])
        } ?: ""

        peerMarker?.title = "Eşleşen ($sender) | Hız: %.1f km/s | $distanceInfo".format(speed)
        peerMarker?.showInfoWindow()
        mapView.controller.animateTo(peerPoint)
        mapView.invalidate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGpsTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(peerReceiver)
    }
}
