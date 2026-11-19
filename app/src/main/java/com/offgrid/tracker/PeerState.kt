package com.offgrid.tracker

import android.location.Location
import java.util.Locale

data class PeerState(
    val contact: PeerContact,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val packetTimestampSec: Long,
    val receivedAtMs: Long
) {
    fun formatDistanceAndBearing(fromLocation: Location?): String {
        if (fromLocation == null) return "Mesafe bekleniyor"
        val results = FloatArray(2)
        Location.distanceBetween(
            fromLocation.latitude,
            fromLocation.longitude,
            latitude,
            longitude,
            results
        )
        val meters = results[0]
        val bearing = ((results[1] + 360f) % 360f)
        val distStr = if (meters < 1000f) {
            String.format(Locale.getDefault(), "%.0f m", meters)
        } else {
            String.format(Locale.getDefault(), "%.2f km", meters / 1000f)
        }
        val compass = bearingToCompassTr(bearing)
        return "$distStr • $compass"
    }

    fun formatAge(): String {
        val diffSec = ((System.currentTimeMillis() - receivedAtMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 15 -> "Az önce"
            diffSec < 60 -> "$diffSec sn önce"
            diffSec < 3600 -> "${diffSec / 60} dk önce"
            else -> "${diffSec / 3600} sa önce"
        }
    }

    companion object {
        private val DIRECTIONS = arrayOf("K", "KD", "D", "GD", "G", "GB", "B", "KB")

        fun bearingToCompassTr(degrees: Float): String {
            val idx = (((degrees + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)
            return DIRECTIONS[idx]
        }
    }
}
