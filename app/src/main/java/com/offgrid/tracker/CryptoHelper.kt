package com.offgrid.tracker

import android.util.Base64
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class LocationPacket(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val timestampSec: Long
)

enum class StrengthLevel {
    TOO_SHORT,
    WEAK,
    MEDIUM,
    STRONG
}

data class PassphraseEvaluation(
    val isValid: Boolean,
    val score: Int,
    val level: StrengthLevel,
    val feedback: String
)

sealed class DecryptResult {
    data class Success(val packet: LocationPacket) : DecryptResult()
    data class ReplayDetected(val reason: String) : DecryptResult()
    data class InvalidKeyOrCorrupted(val reason: String) : DecryptResult()
    data class InvalidPayload(val reason: String) : DecryptResult()
}

object CryptoHelper {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 65_536
    private const val KEY_BIT_LENGTH = 256
    private const val SALT_SIZE = 8
    private const val IV_SIZE = 12
    private const val TAG_BIT_LENGTH = 128

    const val PACKET_PREFIX = "OGT2:"
    const val MAX_PACKET_LENGTH = 220
    const val MIN_PASSPHRASE_LENGTH = 10

    // Replay koruma zaman pencereleri (saniye)
    private const val MAX_PACKET_AGE_SEC = 15L * 60L       // 15 dakika eski paketler reddedilir
    private const val MAX_FUTURE_SKEW_SEC = 5L * 60L       // 5 dakika ileri saat sapması toleransı
    private const val MAX_SEEN_IV_CACHE = 500

    private val secureRandom = SecureRandom()

    // Tekrar (replay) saldırılarına karşı son görülen IV ve gönderici zaman damgaları
    private val seenIvLock = Any()
    private val seenIvs = LinkedHashSet<String>()
    private val lastSenderTimestamp = Collections.synchronizedMap(HashMap<String, Long>())

    // Okunabilir, karışmayan karakter seti (0/O, 1/l/I hariç)
    private const val GENERATOR_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789@#%&"

    fun evaluatePassphrase(phrase: String): PassphraseEvaluation {
        if (phrase.length < MIN_PASSPHRASE_LENGTH) {
            val remaining = MIN_PASSPHRASE_LENGTH - phrase.length
            return PassphraseEvaluation(
                isValid = false,
                score = (phrase.length * 100) / MIN_PASSPHRASE_LENGTH / 2,
                level = StrengthLevel.TOO_SHORT,
                feedback = "En az $MIN_PASSPHRASE_LENGTH karakter gerekli ($remaining karakter daha)"
            )
        }

        var score = 35
        val hasLower = phrase.any { it.isLowerCase() }
        val hasUpper = phrase.any { it.isUpperCase() }
        val hasDigit = phrase.any { it.isDigit() }
        val hasSymbol = phrase.any { !it.isLetterOrDigit() }

        val varietyCount = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        score += varietyCount * 12
        if (phrase.length >= 14) score += 10
        if (phrase.length >= 18) score += 7

        val uniqueChars = phrase.toSet().size
        if (uniqueChars < 5) score = (score - 30).coerceAtLeast(15)

        val bounded = score.coerceIn(0, 100)
        return when {
            bounded < 55 -> PassphraseEvaluation(
                isValid = true,
                score = bounded,
                level = StrengthLevel.WEAK,
                feedback = "Zayıf — büyük/küçük harf, rakam veya sembol ekleyin"
            )
            bounded < 80 -> PassphraseEvaluation(
                isValid = true,
                score = bounded,
                level = StrengthLevel.MEDIUM,
                feedback = "Orta güçte — kullanılabilir"
            )
            else -> PassphraseEvaluation(
                isValid = true,
                score = bounded,
                level = StrengthLevel.STRONG,
                feedback = "Güçlü anahtar (AES-256 + PBKDF2)"
            )
        }
    }

    fun generateStrongPassphrase(): String {
        val groups = List(4) {
            buildString(4) {
                repeat(4) {
                    append(GENERATOR_ALPHABET[secureRandom.nextInt(GENERATOR_ALPHABET.length)])
                }
            }
        }
        return groups.joinToString("-")
    }

    private fun deriveKey(secretPhrase: String, salt: ByteArray): SecretKeySpec {
        val chars = secretPhrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, KEY_BIT_LENGTH)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun encryptLocation(
        lat: Double,
        lon: Double,
        speedKmh: Float,
        secretPhrase: String,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): String {
        require(secretPhrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Şifreleme anahtarı en az $MIN_PASSPHRASE_LENGTH karakter olmalıdır."
        }
        require(isValidCoordinate(lat, lon)) { "Geçersiz koordinat." }

        val safeSpeed = speedKmh.coerceIn(0f, 500f)
        val plainText = String.format(Locale.US, "%.5f,%.5f,%.1f,%d", lat, lon, safeSpeed, timestampSec)

        val salt = ByteArray(SALT_SIZE).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }
        val key = deriveKey(secretPhrase, salt)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BIT_LENGTH, iv))
        cipher.updateAAD(PACKET_PREFIX.toByteArray(Charsets.UTF_8))

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(SALT_SIZE + IV_SIZE + cipherText.size)
        System.arraycopy(salt, 0, combined, 0, SALT_SIZE)
        System.arraycopy(iv, 0, combined, SALT_SIZE, IV_SIZE)
        System.arraycopy(cipherText, 0, combined, SALT_SIZE + IV_SIZE, cipherText.size)

        return PACKET_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptLocation(
        packet: String,
        secretPhrase: String,
        senderKey: String,
        nowSec: Long = System.currentTimeMillis() / 1000L
    ): DecryptResult {
        if (secretPhrase.length < MIN_PASSPHRASE_LENGTH) {
            return DecryptResult.InvalidKeyOrCorrupted("Geçerli bir anahtar tanımlı değil.")
        }
        if (packet.length > MAX_PACKET_LENGTH || !packet.startsWith(PACKET_PREFIX)) {
            return DecryptResult.InvalidPayload("Paket formatı geçersiz.")
        }

        val combined = try {
            val rawBase64 = packet.removePrefix(PACKET_PREFIX).trim()
            Base64.decode(rawBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            return DecryptResult.InvalidPayload("Base64 çözümlenemedi.")
        }

        val minSize = SALT_SIZE + IV_SIZE + (TAG_BIT_LENGTH / 8) + 1
        if (combined.size < minSize) {
            return DecryptResult.InvalidPayload("Paket boyutu eksik.")
        }

        val salt = combined.copyOfRange(0, SALT_SIZE)
        val iv = combined.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
        val cipherText = combined.copyOfRange(SALT_SIZE + IV_SIZE, combined.size)

        val ivFingerprint = Base64.encodeToString(iv, Base64.NO_WRAP)
        synchronized(seenIvLock) {
            if (seenIvs.contains(ivFingerprint)) {
                return DecryptResult.ReplayDetected("Tekrarlanan (replay) konum paketi engellendi.")
            }
        }

        val decrypted = try {
            val key = deriveKey(secretPhrase, salt)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BIT_LENGTH, iv))
            cipher.updateAAD(PACKET_PREFIX.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            return DecryptResult.InvalidKeyOrCorrupted("Şifre çözülemedi — Ortak Anahtar uyuşmuyor veya paket bozulmuş.")
        }

        val parts = decrypted.split(",")
        if (parts.size != 4) {
            return DecryptResult.InvalidPayload("Paket içeriği beklenenden farklı.")
        }

        val lat = parts[0].toDoubleOrNull()
        val lon = parts[1].toDoubleOrNull()
        val speed = parts[2].toFloatOrNull()
        val timestampSec = parts[3].toLongOrNull()

        if (lat == null || lon == null || speed == null || timestampSec == null ||
            !isValidCoordinate(lat, lon) || speed.isNaN() || speed.isInfinite() || speed < 0f || speed > 500f
        ) {
            return DecryptResult.InvalidPayload("Koordinat veya hız verisi geçersiz.")
        }

        // Zaman damgası pencere kontrolü (Replay saldırı engeli)
        val ageSec = nowSec - timestampSec
        if (ageSec > MAX_PACKET_AGE_SEC) {
            return DecryptResult.ReplayDetected("Süresi dolmuş eski konum paketi reddedildi (${ageSec / 60} dk eski).")
        }
        if (ageSec < -MAX_FUTURE_SKEW_SEC) {
            return DecryptResult.ReplayDetected("Gelecek zamanlı geçersiz paket reddedildi.")
        }

        // Gönderici bazlı monotonik zaman kontrolü
        val normalizedSender = PhoneNumberUtils.canonicalKey(senderKey)
        val prevTs = lastSenderTimestamp[normalizedSender]
        if (prevTs != null && timestampSec < prevTs) {
            return DecryptResult.ReplayDetected("Daha eski zaman damgalı paket engellendi.")
        }

        // Paket geçerli — Replay önbelleğini güncelle
        synchronized(seenIvLock) {
            if (seenIvs.size >= MAX_SEEN_IV_CACHE) {
                val iter = seenIvs.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
            seenIvs.add(ivFingerprint)
        }
        lastSenderTimestamp[normalizedSender] = timestampSec

        return DecryptResult.Success(
            LocationPacket(
                latitude = lat,
                longitude = lon,
                speedKmh = speed,
                timestampSec = timestampSec
            )
        )
    }

    fun clearReplayCache() {
        synchronized(seenIvLock) {
            seenIvs.clear()
        }
        lastSenderTimestamp.clear()
    }

    private fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return !lat.isNaN() && !lat.isInfinite() &&
            !lon.isNaN() && !lon.isInfinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
    }
}
