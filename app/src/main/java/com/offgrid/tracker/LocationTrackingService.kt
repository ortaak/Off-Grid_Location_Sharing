package com.offgrid.tracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class LocationTrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "com.offgrid.tracker.action.START_SHARING"
        const val ACTION_STOP = "com.offgrid.tracker.action.STOP_SHARING"
        const val ACTION_SEND_NOW = "com.offgrid.tracker.action.SEND_NOW"
        const val EXTRA_MANUAL_LAT = "manual_lat"
        const val EXTRA_MANUAL_LON = "manual_lon"
        const val EXTRA_MANUAL_SPEED = "manual_speed"

        const val ACTION_SERVICE_EVENT = "com.offgrid.tracker.SERVICE_EVENT"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LAST_SENT_MS = "last_sent_ms"
        const val EXTRA_SENT_COUNT = "sent_count"

        const val EVENT_STARTED = "started"
        const val EVENT_STOPPED = "stopped"
        const val EVENT_SENT = "sent"
        const val EVENT_ERROR = "error"

        private const val CHANNEL_ID = "offgrid_tracking_channel"
        private const val NOTIFICATION_ID = 2026

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastSentTimeMs: Long = 0L
            private set
    }

    private lateinit var storage: SecureStorage
    private lateinit var locationManager: LocationManager
    private val cryptoExecutor = Executors.newSingleThreadExecutor()
    private var lastLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        storage = SecureStorage(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSharing()
                return START_NOT_STICKY
            }
            ACTION_SEND_NOW -> {
                triggerManualSend(intent)
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
            else -> {
                if (!hasRequiredPermissions()) {
                    emitEvent(EVENT_ERROR, "GPS veya SMS izni eksik.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification("Canlı şifreli konum paylaşımı aktif")
                startGpsUpdates()
                isRunning = true
                emitEvent(EVENT_STARTED, "Arka plan paylaşımı başlatıldı.")
                return START_STICKY
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val sms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        return fine && sms
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OffGrid Konum • AES-256 Aktif")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_delete, "Durdur", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OffGrid Canlı Konum Paylaşımı",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "İnternetsiz şifreli GPS konum paylaşımı arka plan bildirimi"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun startGpsUpdates() {
        try {
            locationManager.removeUpdates(this)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    2f,
                    this
                )
            }
            lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            emitEvent(EVENT_ERROR, "GPS başlatılamadı: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val intervalSec = storage.getIntervalSeconds()
        if (intervalSec <= 0) return // Sadece manuel mod

        val now = System.currentTimeMillis()
        if (now - lastSentTimeMs >= intervalSec * 1000L) {
            broadcastEncryptedLocation(location)
        }
    }

    private fun triggerManualSend(intent: Intent) {
        val loc = if (intent.hasExtra(EXTRA_MANUAL_LAT) && intent.hasExtra(EXTRA_MANUAL_LON)) {
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = intent.getDoubleExtra(EXTRA_MANUAL_LAT, 0.0)
                longitude = intent.getDoubleExtra(EXTRA_MANUAL_LON, 0.0)
                speed = intent.getFloatExtra(EXTRA_MANUAL_SPEED, 0f)
            }
        } else {
            lastLocation ?: run {
                try {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    } else null
                } catch (_: Exception) {
                    null
                }
            }
        }
        if (loc == null) {
            emitEvent(EVENT_ERROR, "Henüz GPS uydu kilidi alınmadı.")
            if (!isRunning) stopSelf()
            return
        }
        broadcastEncryptedLocation(loc)
    }

    private fun broadcastEncryptedLocation(location: Location) {
        val peers = storage.getPeers()
        val secret = storage.getPassphrase()

        if (peers.isEmpty()) {
            emitEvent(EVENT_ERROR, "İzinli numara listesi boş.")
            return
        }
        if (secret.length < CryptoHelper.MIN_PASSPHRASE_LENGTH) {
            emitEvent(EVENT_ERROR, "Geçerli (en az ${CryptoHelper.MIN_PASSPHRASE_LENGTH} karakter) anahtar gerekli.")
            return
        }

        val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f)
        lastSentTimeMs = System.currentTimeMillis()

        cryptoExecutor.execute {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            var successCount = 0
            var lastError: String? = null

            for (peer in peers) {
                try {
                    // Her alıcı için benzersiz random salt & IV ile ayrı paket üretilir
                    val packet = CryptoHelper.encryptLocation(
                        lat = location.latitude,
                        lon = location.longitude,
                        speedKmh = speedKmh,
                        secretPhrase = secret
                    )
                    smsManager.sendTextMessage(peer.phone, null, packet, null, null)
                    successCount++
                } catch (e: Exception) {
                    lastError = e.message
                }
            }

            if (successCount > 0) {
                val msg = String.format(
                    Locale.getDefault(),
                    "%d kişiye şifreli konum gönderildi (%.4f, %.4f)",
                    successCount, location.latitude, location.longitude
                )
                if (isRunning) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification(msg))
                }
                emitEvent(EVENT_SENT, msg, successCount)
            } else {
                emitEvent(EVENT_ERROR, "SMS gönderilemedi: ${lastError ?: "Bilinmeyen hata"}")
            }

            if (!isRunning) {
                stopSelf()
            }
        }
    }

    private fun emitEvent(type: String, message: String, sentCount: Int = 0) {
        val intent = Intent(ACTION_SERVICE_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_EVENT_TYPE, type)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_LAST_SENT_MS, lastSentTimeMs)
            putExtra(EXTRA_SENT_COUNT, sentCount)
        }
        sendBroadcast(intent)
    }

    private fun stopSharing() {
        isRunning = false
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        emitEvent(EVENT_STOPPED, "Canlı paylaşım durduruldu.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Deprecated("Deprecated in Android API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        isRunning = false
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {
        }
        cryptoExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
