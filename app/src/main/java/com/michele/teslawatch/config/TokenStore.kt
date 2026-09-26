package com.michele.teslawatch.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Home Assistant token, kept out of the APK (installed APKs can be read by other apps).
 * It arrives once through adb (see TokenProvider) and is stored in this app's private storage,
 * encrypted with an AES key that never leaves the Android Keystore. One instance per process.
 */
class TokenStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cached: String? = null
    @Volatile private var loaded = false

    @Synchronized
    fun get(): String? {
        if (!loaded) {
            cached = runCatching { prefs.getString(KEY_TOKEN, null)?.let(::decrypt) }.getOrNull()
            loaded = true
        }
        return cached
    }

    /** Short SHA-256 fingerprint of the current token, to remember per-token facts without storing it again. */
    fun fingerprint(): String? = get()?.let { token ->
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun set(token: String) {
        prefs.edit().putString(KEY_TOKEN, encrypt(token)).commit()
        cached = token
        loaded = true
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_TOKEN).commit()
        cached = null
        loaded = true
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
    }

    companion object {
        @Volatile private var instance: TokenStore? = null

        fun get(context: Context): TokenStore =
            instance ?: synchronized(this) { instance ?: TokenStore(context).also { instance = it } }

        private const val PREFS = "secure_config"
        private const val KEY_TOKEN = "ha_token"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "tesla_watch_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12

        /** A Home Assistant long-lived token is a JWT: three base64url parts separated by dots. */
        private val TOKEN_FORMAT = Regex("^[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}$")

        fun looksValid(token: String): Boolean = token.length in 50..4096 && TOKEN_FORMAT.matches(token)
    }
}
