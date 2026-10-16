package com.vyora.music.data.discord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.vyora.music.BuildConfig
import com.vyora.music.core.Http
import com.vyora.music.core.SecureBox
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class DiscordUser(
    val id: String,
    val name: String,
    val username: String = name,
    val avatarUrl: String? = null,
)

/** Where the browser based sign-in currently is, shown by the Discord settings screen. */
sealed interface DiscordAuthState {
    data object Idle : DiscordAuthState
    data object Waiting : DiscordAuthState
    data object Exchanging : DiscordAuthState
    data class Failed(val message: String) : DiscordAuthState
}

/** One OAuth2 authorization attempt: the browser gets [uri], the redirect must echo [state]. */
data class DiscordAuthSession(val state: String, val verifier: String, val uri: Uri)

/**
 * Discord sign-in, ported from YumaPlayer / ArchiveTune (GPL-3.0): an OAuth2 authorization-code flow with
 * PKCE against a public Discord application. The consent page opens in the user's browser (Chrome, or the
 * Discord app when it claims the link), Discord redirects to `discord-<app id>:/authorize/callback`, and
 * [DiscordOAuthCallbackActivity] hands the code back here. No password or user token ever touches VYORA.
 *
 * Tokens are kept in [SecureBox] (Android Keystore AES-GCM) and refreshed with the refresh token.
 */
object DiscordAccount {
    private const val TAG = "VYORA-Discord"
    private const val AUTHORIZE = "https://discord.com/oauth2/authorize"
    private const val TOKEN = "https://discord.com/api/oauth2/token"
    private const val ME = "https://discord.com/api/v10/users/@me"
    private const val SCOPES = "openid identify sdk.social_layer_presence"
    private const val EXPIRY_SKEW_MS = 5 * 60_000L

    private const val PREFS = "vyora_discord_oauth"
    private const val LEGACY_PREFS = "vyora_discord"
    private const val KEY_ACCESS = "discord_access_token"
    private const val KEY_REFRESH = "discord_refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_ID = "user_id"
    private const val KEY_NAME = "user_name"
    private const val KEY_USERNAME = "user_username"
    private const val KEY_AVATAR = "user_avatar"

    val applicationId: String get() = BuildConfig.DISCORD_APPLICATION_ID
    val redirectUri: String get() = "${BuildConfig.DISCORD_REDIRECT_SCHEME}:/authorize/callback"

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private val random = SecureRandom()

    @Volatile private var pending: DiscordAuthSession? = null

    private val _user = MutableStateFlow<DiscordUser?>(null)
    val user: StateFlow<DiscordUser?> = _user.asStateFlow()

    private val _auth = MutableStateFlow<DiscordAuthState>(DiscordAuthState.Idle)
    val auth: StateFlow<DiscordAuthState> = _auth.asStateFlow()

    val client: OkHttpClient by lazy {
        Http.base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // 1.8.0 and older stored a raw user token captured from a WebView; that login is gone, drop it.
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).let { if (it.all.isNotEmpty()) it.edit { clear() } }
        val p = prefs() ?: return
        val id = p.getString(KEY_ID, null)
        val name = p.getString(KEY_NAME, null)
        if (id != null && name != null && SecureBox.get(KEY_ACCESS) != null) {
            _user.value = DiscordUser(id, name, p.getString(KEY_USERNAME, null) ?: name, p.getString(KEY_AVATAR, null))
        }
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts a new attempt; the caller opens [DiscordAuthSession.uri] in the browser. */
    fun beginLogin(): DiscordAuthSession {
        val session = createSession(applicationId, redirectUri)
        pending = session
        _auth.value = DiscordAuthState.Waiting
        return session
    }

    /** Opens the consent page in the browser, like Yuma does (ACTION_VIEW + BROWSABLE). */
    fun launchLogin(context: Context): Boolean {
        val session = beginLogin()
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, session.uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            _auth.value = DiscordAuthState.Failed(it.message ?: "No browser")
            pending = null
        }.isSuccess
    }

    fun cancelLogin() {
        pending = null
        if (_auth.value !is DiscordAuthState.Exchanging) _auth.value = DiscordAuthState.Idle
    }

    /** Called by [DiscordOAuthCallbackActivity] with the redirect. Finishes the sign-in in the background. */
    fun handleRedirect(redirect: Uri) {
        val session = pending
        val problem = validateRedirect(redirect, session, BuildConfig.DISCORD_REDIRECT_SCHEME)
        if (problem != null) {
            Log.w(TAG, "Ignoring Discord redirect: $problem")
            if (session != null) _auth.value = DiscordAuthState.Failed(problem)
            return
        }
        pending = null
        val code = redirect.getQueryParameter("code").orEmpty()
        _auth.value = DiscordAuthState.Exchanging
        scope.launch {
            runCatching {
                val token = postToken(
                    FormBody.Builder()
                        .add("client_id", applicationId)
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("redirect_uri", redirectUri)
                        .add("code_verifier", session!!.verifier)
                        .build()
                )
                storeToken(token, null)
                val user = fetchUser(token.optString("access_token"))
                storeUser(user)
                user
            }.onSuccess {
                _auth.value = DiscordAuthState.Idle
                DiscordPresence.refresh()
            }.onFailure {
                Log.w(TAG, "Discord token exchange failed", it)
                _auth.value = DiscordAuthState.Failed(it.message ?: "Token exchange failed")
            }
        }
    }

    /** A usable access token, refreshing it first when it is about to expire. */
    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        refreshLock.withLock {
            val current = SecureBox.get(KEY_ACCESS) ?: return@withLock null
            val expires = prefs()?.getLong(KEY_EXPIRES, 0L) ?: 0L
            if (expires == 0L || System.currentTimeMillis() + EXPIRY_SKEW_MS < expires) return@withLock current
            val refresh = SecureBox.get(KEY_REFRESH) ?: return@withLock current
            runCatching {
                val token = postToken(
                    FormBody.Builder()
                        .add("client_id", applicationId)
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refresh)
                        .build()
                )
                storeToken(token, refresh)
                token.optString("access_token")
            }.onFailure { Log.w(TAG, "Discord token refresh failed", it) }
                .getOrNull()
                ?: current
        }
    }

    /** Called when Discord rejects the token outright (gateway close 4004). */
    fun invalidate() {
        prefs()?.edit { putLong(KEY_EXPIRES, 1L) }
    }

    fun signOut() {
        SecureBox.remove(KEY_ACCESS)
        SecureBox.remove(KEY_REFRESH)
        prefs()?.edit { clear() }
        pending = null
        _auth.value = DiscordAuthState.Idle
        _user.value = null
    }

    private fun postToken(body: FormBody): JSONObject {
        val request = Request.Builder().url(TOKEN).post(body).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val reason = json?.optString("error_description")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                error(reason)
            }
            val json = JSONObject(text)
            require(json.optString("access_token").isNotBlank()) { "Discord returned no access token" }
            return json
        }
    }

    private fun storeToken(token: JSONObject, fallbackRefresh: String?) {
        SecureBox.put(KEY_ACCESS, token.optString("access_token"))
        val refresh = token.optString("refresh_token").takeIf { it.isNotBlank() } ?: fallbackRefresh
        if (refresh != null) SecureBox.put(KEY_REFRESH, refresh)
        val expiresIn = token.optLong("expires_in", 0L)
        prefs()?.edit { putLong(KEY_EXPIRES, if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000L else 0L) }
    }

    private fun storeUser(user: DiscordUser) {
        prefs()?.edit {
            putString(KEY_ID, user.id)
            putString(KEY_NAME, user.name)
            putString(KEY_USERNAME, user.username)
            putString(KEY_AVATAR, user.avatarUrl)
        }
        _user.value = user
    }

    private fun fetchUser(accessToken: String): DiscordUser {
        val request = Request.Builder().url(ME).header("Authorization", "Bearer $accessToken").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return parseUser(JSONObject(response.body?.string().orEmpty()))
        }
    }

    internal fun parseUser(json: JSONObject): DiscordUser {
        fun str(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
        val id = str("id") ?: str("sub") ?: ""
        val username = str("preferred_username") ?: str("username") ?: id
        val name = str("global_name") ?: str("nickname") ?: str("name") ?: username
        return DiscordUser(id, name, username, str("picture") ?: avatarUrl(id, str("avatar")))
    }

    internal fun avatarUrl(userId: String, hash: String?): String? {
        if (userId.isBlank()) return null
        if (!hash.isNullOrBlank()) {
            val ext = if (hash.startsWith("a_")) "gif" else "png"
            return "https://cdn.discordapp.com/avatars/$userId/$hash.$ext?size=256"
        }
        val index = userId.toLongOrNull()?.let { ((it shr 22) % 6L).toInt() } ?: 0
        return "https://cdn.discordapp.com/embed/avatars/$index.png"
    }

    internal fun createSession(appId: String, redirect: String): DiscordAuthSession {
        val state = randomUrlSafe(32)
        val verifier = randomUrlSafe(64)
        val uri = Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", appId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        return DiscordAuthSession(state, verifier, uri)
    }

    /** Returns why [redirect] cannot complete [session], or null when it can. */
    internal fun validateRedirect(redirect: Uri, session: DiscordAuthSession?, scheme: String): String? = when {
        redirect.scheme != scheme -> "Unexpected redirect scheme"
        redirect.path != "/authorize/callback" -> "Unexpected redirect target"
        session == null -> "No sign-in in progress"
        redirect.getQueryParameter("state") != session.state -> "State mismatch"
        redirect.getQueryParameter("error") != null ->
            redirect.getQueryParameter("error_description") ?: redirect.getQueryParameter("error")
        redirect.getQueryParameter("code").isNullOrBlank() -> "Authorization code missing"
        else -> null
    }

    internal fun challenge(verifier: String): String = base64Url(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    )

    private fun randomUrlSafe(bytes: Int): String = base64Url(ByteArray(bytes).also(random::nextBytes))

    internal fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** Receives `discord-<app id>:/authorize/callback?code=…&state=…` and brings VYORA back to the front. */
class DiscordOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        DiscordAccount.init(applicationContext)
        intent?.data?.let(DiscordAccount::handleRedirect)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { startActivity(launch) }
        }
        finish()
    }
}
