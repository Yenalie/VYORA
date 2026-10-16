package com.vyora.music.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key-value store for secrets (scrobbling tokens, music server passwords).
 * Values are AES-256-GCM encrypted with a key that never leaves the Android Keystore.
 */
object SecureBox {
    private const val TAG = "VYORA-SecureBox"
    private const val PREFS = "vyora_secure"
    private const val KEY_ALIAS = "vyora_secure_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): String? {
        val blob = prefs()?.getString(key, null) ?: return null
        return runCatching { decrypt(blob) }
            .onFailure { Log.w(TAG, "Unreadable secret '$key', dropping it", it); remove(key) }
            .getOrNull()
    }

    fun put(key: String, value: String?) {
        if (value.isNullOrEmpty()) return remove(key)
        val blob = runCatching { encrypt(value) }.onFailure { Log.e(TAG, "Cannot encrypt '$key'", it) }.getOrNull() ?: return
        prefs()?.edit()?.putString(key, blob)?.apply()
    }

    fun remove(key: String) {
        prefs()?.edit()?.remove(key)?.apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        return String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }
}
