package com.flugel.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Token Android Keystore AES-GCM ile korunur; Android yedeği kapalıdır. */
class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("flugel_server_settings", Context.MODE_PRIVATE)
    private val alias = "flugel_server_api_token_v1"

    fun serverUrl(): String = preferences.getString("server_url", "") ?: ""

    fun saveServerUrl(input: String): Boolean {
        val value = input.trim().trimEnd('/')
        val uri = try { URI(value) } catch (_: Exception) { return false }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null || (uri.path.orEmpty().isNotEmpty() && uri.path != "/")) return false
        preferences.edit().putString("server_url", value).apply()
        return true
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun saveToken(token: String) {
        require(token.length in 32..256 && token.all { it.code in 33..126 })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("token", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    fun token(): String? {
        val encoded = preferences.getString("token", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.size < 29) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
