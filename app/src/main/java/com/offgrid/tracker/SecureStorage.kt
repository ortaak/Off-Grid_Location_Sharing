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

data class HgmConfig(
    val apiKey: String = "",
    val layer: String = LAYER_TEMEL,
    val urlTemplate: String = DEFAULT_URL_TEMPLATE,
    val referer: String = ""
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && urlTemplate.startsWith("https://")

    companion object {
        const val LAYER_TEMEL = "temel"
        const val LAYER_ORTOFOTO = "ortofoto"
        const val LAYER_TOPO = "topo"
        const val LAYER_GECE = "gece"

        const val DEFAULT_URL_TEMPLATE =
            "https://atlas.harita.gov.tr/webservis/{layer}/{z}/{x}/{y}.png?apikey={key}"
    }
}

data class CustomMapConfig(
    val name: String = "",
    val protocol: String = PROTOCOL_XYZ,
    val urlTemplate: String = "",
    val wmsLayers: String = "",
    val authHeader: String = ""
) {
    val isConfigured: Boolean
        get() = urlTemplate.startsWith("https://") &&
            (protocol == PROTOCOL_XYZ || wmsLayers.isNotBlank())

    companion object {
        const val PROTOCOL_XYZ = "XYZ"
        const val PROTOCOL_WMS = "WMS"
    }
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
        private const val KEY_HGM_CONFIG = "enc_hgm_config"
        private const val KEY_CUSTOM_MAP_CONFIG = "enc_custom_map_config"
        private const val KEY_OFFLINE_ARCHIVE = "offline_archive_name"

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
        }
        return result
    }

    fun saveHgmConfig(config: HgmConfig) {
        val obj = JSONObject().apply {
            put("apiKey", config.apiKey)
            put("layer", config.layer)
            put("urlTemplate", config.urlTemplate)
            put("referer", config.referer)
        }
        val enc = encryptString(obj.toString())
        if (enc != null) {
            prefs.edit().putString(KEY_HGM_CONFIG, enc).apply()
        }
    }

    fun getHgmConfig(): HgmConfig {
        val json = decryptString(prefs.getString(KEY_HGM_CONFIG, null)) ?: return HgmConfig()
        return try {
            val obj = JSONObject(json)
            HgmConfig(
                apiKey = obj.optString("apiKey", ""),
                layer = obj.optString("layer", HgmConfig.LAYER_TEMEL),
                urlTemplate = obj.optString("urlTemplate", HgmConfig.DEFAULT_URL_TEMPLATE)
                    .ifBlank { HgmConfig.DEFAULT_URL_TEMPLATE },
                referer = obj.optString("referer", "")
            )
        } catch (_: Exception) {
            HgmConfig()
        }
    }

    fun saveCustomMapConfig(config: CustomMapConfig) {
        val obj = JSONObject().apply {
            put("name", config.name)
            put("protocol", config.protocol)
            put("urlTemplate", config.urlTemplate)
            put("wmsLayers", config.wmsLayers)
            put("authHeader", config.authHeader)
        }
        val enc = encryptString(obj.toString())
        if (enc != null) {
            prefs.edit().putString(KEY_CUSTOM_MAP_CONFIG, enc).apply()
        }
    }

    fun getCustomMapConfig(): CustomMapConfig {
        val json = decryptString(prefs.getString(KEY_CUSTOM_MAP_CONFIG, null)) ?: return CustomMapConfig()
        return try {
            val obj = JSONObject(json)
            CustomMapConfig(
                name = obj.optString("name", ""),
                protocol = obj.optString("protocol", CustomMapConfig.PROTOCOL_XYZ),
                urlTemplate = obj.optString("urlTemplate", ""),
                wmsLayers = obj.optString("wmsLayers", ""),
                authHeader = obj.optString("authHeader", "")
            )
        } catch (_: Exception) {
            CustomMapConfig()
        }
    }

    fun saveActiveOfflineArchive(fileName: String) {
        if (fileName.isBlank()) {
            prefs.edit().remove(KEY_OFFLINE_ARCHIVE).apply()
        } else {
            prefs.edit().putString(KEY_OFFLINE_ARCHIVE, fileName).apply()
        }
    }

    fun getActiveOfflineArchive(): String {
        return prefs.getString(KEY_OFFLINE_ARCHIVE, "").orEmpty()
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
