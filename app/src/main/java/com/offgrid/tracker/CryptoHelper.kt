package com.offgrid.tracker

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BIT_LENGTH = 128
    const val PACKET_PREFIX = "OGT1:"

    private fun deriveKey(secretPhrase: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(secretPhrase.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptLocation(lat: Double, lon: Double, speed: Float, secretPhrase: String): String {
        val plainText = "%.5f,%.5f,%.1f".format(java.util.Locale.US, lat, lon, speed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secretPhrase), GCMParameterSpec(TAG_BIT_LENGTH, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherText
        return PACKET_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptLocation(packet: String, secretPhrase: String): Triple<Double, Double, Float>? {
        return try {
            if (!packet.startsWith(PACKET_PREFIX)) return null
            val rawBase64 = packet.removePrefix(PACKET_PREFIX).trim()
            val combined = Base64.decode(rawBase64, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_SIZE)
            val cipherText = combined.copyOfRange(IV_SIZE, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(secretPhrase), GCMParameterSpec(TAG_BIT_LENGTH, iv))
            val decrypted = String(cipher.doFinal(cipherText), Charsets.UTF_8)
            val parts = decrypted.split(",")
            Triple(parts[0].toDouble(), parts[1].toDouble(), parts[2].toFloat())
        } catch (e: Exception) {
            null
        }
    }
}
