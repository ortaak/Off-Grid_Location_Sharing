package com.offgrid.tracker

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PeerContact(
    val phone: String,
    val label: String,
    val colorInt: Int
) {
    val displayName: String
        get() = if (label.isNotBlank()) label else PhoneNumberUtils.formatForDisplay(phone)

    val canonicalKey: String
        get() = PhoneNumberUtils.canonicalKey(phone)
}

class SecureStorage(context: Context) {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "offgrid_tracker_master_key_v2"
        private const val PREFS_NAME = "offgrid_secure_prefs_v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BIT_LENGTH = 128

        private const val KEY_PASSPHRASE = "enc_passphrase"
        private const val KEY_PEERS = "enc_peers"
        private const val KEY_INTERVAL_SEC = "interval_sec"
        private const val KEY_MAP_STYLE = "map_style"

        const val DEFAULT_INTERVAL_SEC = 60

        val PEER_COLORS = intArrayOf(
            0xFF10B981.toInt(), // Emerald
            0xFFF59E0B.toInt(), // Amber
            0xFFA855F7.toInt(), // Purple
            0xFFF43F5E.toInt(), // Rose
            0xFF06B6D4.toInt(), // Cyan
            0xFFEC4899.toInt()  // Pink
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptString(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptString(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_SIZE) return null
            val iv = combined.copyOfRange(0, IV_SIZE)
            val cipherText = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BIT_LENGTH, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun savePassphrase(passphrase: String) {
        if (passphrase.isEmpty()) {
            prefs.edit().remove(KEY_PASSPHRASE).apply()
            return
        }
        val enc = encryptString(passphrase)
        if (enc != null) {
            prefs.edit().putString(KEY_PASSPHRASE, enc).apply()
        }
    }

    fun getPassphrase(): String {
        return decryptString(prefs.getString(KEY_PASSPHRASE, null)).orEmpty()
    }

    fun savePeers(peers: List<PeerContact>) {
        val arr = JSONArray()
        for (peer in peers) {
            val obj = JSONObject().apply {
                put("phone", peer.phone)
                put("label", peer.label)
                put("color", peer.colorInt)
            }
            arr.put(obj)
        }
        val enc = encryptString(arr.toString())
        if (enc != null) {
            prefs.edit().putString(KEY_PEERS, enc).apply()
        }
    }

    fun getPeers(): MutableList<PeerContact> {
        val json = decryptString(prefs.getString(KEY_PEERS, null)) ?: return mutableListOf()
        val result = mutableListOf<PeerContact>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val phone = obj.optString("phone", "")
                val label = obj.optString("label", "")
                val color = obj.optInt("color", PEER_COLORS[i % PEER_COLORS.size])
                if (phone.isNotBlank()) {
                    result.add(PeerContact(phone, label, color))
                }
            }
        } catch (_: Exception) {
            // Bozuk kayıt varsa boş liste dön
        }
        return result
    }

    fun saveIntervalSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_INTERVAL_SEC, seconds).apply()
    }

    fun getIntervalSeconds(): Int {
        return prefs.getInt(KEY_INTERVAL_SEC, DEFAULT_INTERVAL_SEC)
    }

    fun saveMapStyle(styleId: String) {
        prefs.edit().putString(KEY_MAP_STYLE, styleId).apply()
    }

    fun getMapStyle(): String {
        return prefs.getString(KEY_MAP_STYLE, MapStyle.OSM.id) ?: MapStyle.OSM.id
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        CryptoHelper.clearReplayCache()
    }
}
