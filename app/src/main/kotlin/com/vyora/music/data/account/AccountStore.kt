package com.vyora.music.data.account

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** The signed-in YouTube account as shown in Settings. */
data class YouTubeAccount(
    val name: String,
    /** "@handle" or e-mail, whichever YouTube returned. */
    val handle: String?,
    val photo: String?,
    /** Which Google account of the browser session (X-Goog-AuthUser). */
    val authUser: Int = 0,
)

/**
 * The YouTube session: account info plus the cookies that act as the password.
 *
 * Everything is encrypted with an AES-GCM key held by the Android Keystore (it never leaves
 * the secure hardware and is not part of backups), so a copied app data folder or a cloud
 * backup does not carry a usable session. Excluded from backups in the backup rules too.
 */
object AccountStore {
    private const val TAG = "VYORA-Account"
    private const val PREFS = "vyora_account"
    private const val KEY_BLOB = "session"
    private const val KEY_ALIAS = "vyora_account_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private var appContext: Context? = null
    @Volatile private var cookie: String? = null

    private val _account = MutableStateFlow<YouTubeAccount?>(null)
    val account: StateFlow<YouTubeAccount?> = _account.asStateFlow()

    val isSignedIn: Boolean get() = cookie != null

    /** Session cookie header ("SAPISID=...; SID=..."), only while signed in. */
    fun cookie(): String? = cookie

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val blob = prefs()?.getString(KEY_BLOB, null) ?: return
        val json = runCatching { JSONObject(decrypt(blob)) }
            .onFailure { Log.w(TAG, "Stored session unreadable, signing out", it) }
            .getOrNull()
        if (json == null) {
            prefs()?.edit()?.remove(KEY_BLOB)?.apply()
            return
        }
        cookie = json.optString("cookie").takeIf { it.isNotBlank() }
        _account.value = if (cookie == null) null else YouTubeAccount(
            name = json.optString("name"),
            handle = json.optString("handle").takeIf { it.isNotBlank() },
            photo = json.optString("photo").takeIf { it.isNotBlank() },
            authUser = json.optInt("authUser", 0),
        )
    }

    fun save(cookie: String, account: YouTubeAccount) {
        val json = JSONObject()
            .put("cookie", cookie)
            .put("name", account.name)
            .put("handle", account.handle.orEmpty())
            .put("photo", account.photo.orEmpty())
            .put("authUser", account.authUser)
        val blob = runCatching { encrypt(json.toString()) }
            .onFailure { Log.e(TAG, "Cannot encrypt session; it lasts until the app closes", it) }
            .getOrNull()
        if (blob != null) prefs()?.edit()?.putString(KEY_BLOB, blob)?.apply()
        this.cookie = cookie
        _account.value = account
    }

    /** Account details changed (name, photo) but the session is the same. */
    fun updateProfile(account: YouTubeAccount) {
        val current = cookie ?: return
        save(current, account)
    }

    fun clear() {
        cookie = null
        _account.value = null
        prefs()?.edit()?.remove(KEY_BLOB)?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------------- crypto

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
