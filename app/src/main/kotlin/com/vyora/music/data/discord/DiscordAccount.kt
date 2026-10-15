package com.vyora.music.data.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class DiscordUser(val id: String, val name: String)

object DiscordAccount {
    private const val PREFS = "vyora_discord"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_ALIAS = "vyora_discord_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private var appContext: Context? = null

    private val _user = MutableStateFlow<DiscordUser?>(null)
    val user: StateFlow<DiscordUser?> = _user.asStateFlow()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = prefs()
        val id = prefs.getString(KEY_USER_ID, null)
        val name = prefs.getString(KEY_USER_NAME, null)
        if (id != null && name != null && prefs.contains(KEY_TOKEN)) _user.value = DiscordUser(id, name)
    }

    fun token(): String? {
        val stored = prefs().getString(KEY_TOKEN, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    suspend fun signIn(rawToken: String): Result<DiscordUser> = withContext(Dispatchers.IO) {
        val token = rawToken.trim().trim('"')
        if (token.length < 20 || token.any { it.isWhitespace() }) {
            return@withContext Result.failure(IllegalArgumentException("invalid token"))
        }
        runCatching {
            val request = Request.Builder()
                .url("https://discord.com/api/v10/users/@me")
                .header("Authorization", token)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) VYORA")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                val name = json.optString("global_name").takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("username")
                DiscordUser(json.getString("id"), name)
            }
        }.onSuccess { user ->
            prefs().edit {
                putString(KEY_TOKEN, encrypt(token))
                putString(KEY_USER_ID, user.id)
                putString(KEY_USER_NAME, user.name)
            }
            _user.value = user
        }
    }

    fun signOut() {
        prefs().edit { clear() }
        _user.value = null
    }

    private fun prefs() = requireNotNull(appContext) { "DiscordAccount.init not called" }
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
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
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
