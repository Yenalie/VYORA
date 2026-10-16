package com.vyora.music.data.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.vyora.music.BuildConfig
import com.vyora.music.MainActivity
import com.vyora.music.R
import com.vyora.music.data.settings.SettingsRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val reason: UpdateError, val release: ReleaseInfo?) : UpdateState
}

val UpdateState.release: ReleaseInfo?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.ReadyToInstall -> release
        is UpdateState.Failed -> release
        else -> null
    }

enum class UpdateError {
    NETWORK,
    DOWNLOAD,
    INVALID_APK,
    SIGNATURE,
}

object UpdateManager {
    private const val TAG = "VYORA-Update"
    private const val PREFS = "vyora_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DISMISSED = "dismissed_version"
    private const val KEY_NOTIFIED = "notified_version"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4210
    private const val LAUNCH_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000
    const val EXTRA_SHOW_UPDATE = "com.vyora.music.SHOW_UPDATE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var installAfterPermission = false

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    val currentVersion: AppVersion? get() = AppVersion.parse(BuildConfig.VERSION_NAME)

    fun init(context: Context) {
        createChannel(context)
        UpdateWorker.schedule(context, SettingsRepository.current.autoUpdateCheck)
    }

    fun isNewer(release: ReleaseInfo): Boolean {
        val current = currentVersion ?: return false
        return release.version > current
    }

    fun checkOnLaunch(context: Context) {
        if (!SettingsRepository.current.autoUpdateCheck) return
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < LAUNCH_CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    fun check(context: Context, manual: Boolean) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        if (_state.value is UpdateState.Downloading) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val release = runCatching { ReleaseApi.latest() }
                .onFailure { Log.w(TAG, "Update check failed", it) }
                .getOrElse {
                    _state.value = if (manual) UpdateState.Failed(UpdateError.NETWORK, null) else UpdateState.Idle
                    return@launch
                }
            prefs(app).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            if (release == null || !isNewer(release)) {
                _state.value = UpdateState.UpToDate
                cancelNotification(app)
                return@launch
            }
            _state.value = UpdateState.Available(release)
            val dismissed = prefs(app).getString(KEY_DISMISSED, null)
            if (manual || dismissed != release.version.toString()) _prompt.value = true
        }
    }

    internal fun checkInBackground(context: Context): Boolean {
        val release = runCatching { ReleaseApi.latest() }.getOrElse {
            Log.w(TAG, "Background update check failed", it)
            return false
        }
        prefs(context).edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
        if (release != null && isNewer(release)) {
            if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.ReadyToInstall) {
                _state.value = UpdateState.Available(release)
            }
            notifyOnce(context, release)
        }
        return true
    }

    fun showPrompt() {
        if (_state.value.release != null) _prompt.value = true
    }

    fun dismiss(context: Context) {
        _prompt.value = false
        _state.value.release?.let { prefs(context).edit { putString(KEY_DISMISSED, it.version.toString()) } }
    }

    fun hidePrompt() {
        _prompt.value = false
    }

    fun handleIntent(context: Context, intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) != true) return
        intent.removeExtra(EXTRA_SHOW_UPDATE)
        if (_state.value.release != null) showPrompt() else check(context, manual = true)
    }

    fun download(context: Context) {
        val app = context.applicationContext
        val release = _state.value.release ?: return
        if (_state.value is UpdateState.Downloading) return
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, if (release.apkSize > 0) 0f else -1f)
            val dir = File(app.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "VYORA-${release.version}.apk")
            val part = File(dir, target.name + ".part")
            val ok = runCatching {
                val request = Request.Builder().url(release.apkUrl).header("User-Agent", "VYORA-Android").build()
                ReleaseApi.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            var lastPublished = 0f
                            while (isActive) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                if (total > 0) {
                                    val p = (read.toFloat() / total).coerceIn(0f, 1f)
                                    if (p - lastPublished >= 0.01f || p >= 1f) {
                                        lastPublished = p
                                        _state.value = UpdateState.Downloading(release, p)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!isActive) error("cancelled")
                part.renameTo(target) || error("rename failed")
            }.onFailure { Log.w(TAG, "Download failed", it) }.isSuccess
            if (!ok) {
                part.delete()
                _state.value = UpdateState.Failed(UpdateError.DOWNLOAD, release)
                return@launch
            }
            val problem = verify(app, target, release)
            if (problem != null) {
                target.delete()
                _state.value = UpdateState.Failed(problem, release)
                return@launch
            }
            _state.value = UpdateState.ReadyToInstall(release, target)
            _prompt.value = true
            install(app)
        }
    }

    fun cancelDownload() {
        val release = _state.value.release
        job?.cancel()
        _state.value = if (release != null) UpdateState.Available(release) else UpdateState.Idle
    }

    private fun verify(context: Context, apk: File, release: ReleaseInfo): UpdateError? {
        val pm = context.packageManager
        val archive = pm.archiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return UpdateError.INVALID_APK
        if (archive.packageName != context.packageName) return UpdateError.INVALID_APK
        if (archive.versionCodeCompat <= BuildConfig.VERSION_CODE) return UpdateError.INVALID_APK
        if (!checksumMatches(release, apk)) return UpdateError.INVALID_APK
        val installed = runCatching {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        val verdict = UpdateSignature.check(
            installed = installed?.signerDigests().orEmpty(),
            archive = archive.signerDigests(),
            pinned = if (BuildConfig.DEBUG) null else UpdateSignature.RELEASE_CERT_SHA256,
        )
        if (verdict != UpdateSignature.Verdict.OK) {
            Log.w(TAG, "Rejected update: $verdict")
            return UpdateError.SIGNATURE
        }
        return null
    }

    /** When the release publishes a `.sha256` file, the download must match it exactly. */
    private fun checksumMatches(release: ReleaseInfo, apk: File): Boolean {
        val url = release.checksumUrl ?: return true
        val expected = runCatching {
            val request = Request.Builder().url(url).header("User-Agent", "VYORA-Android").build()
            ReleaseApi.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                UpdateSignature.parseChecksum(response.body?.string().orEmpty())
            }
        }.getOrNull() ?: return false
        return expected == UpdateSignature.sha256(apk)
    }

    fun install(context: Context) {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (!ready.file.exists()) {
            _state.value = UpdateState.Available(ready.release)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            installAfterPermission = true
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "No installer", it) }
    }

    fun onResume(context: Context) {
        if (!installAfterPermission) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) return
        installAfterPermission = false
        install(context)
    }

    fun setAutoCheck(context: Context, enabled: Boolean) {
        SettingsRepository.update { it.copy(autoUpdateCheck = enabled) }
        UpdateWorker.schedule(context, enabled)
        if (!enabled) cancelNotification(context)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.update_channel_desc) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun notifyOnce(context: Context, release: ReleaseInfo) {
        val prefs = prefs(context)
        val version = release.version.toString()
        if (prefs.getString(KEY_NOTIFIED, null) == version) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.update_notification_title, version))
            .setContentText(context.getString(R.string.update_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    release.notes.ifBlank { context.getString(R.string.update_notification_text) }.take(400)
                )
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onSuccess { prefs.edit { putString(KEY_NOTIFIED, version) } }
    }

    private fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    private fun PackageManager.archiveInfo(path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            getPackageArchiveInfo(path, flags)
        }

    @Suppress("DEPRECATION")
    private val PackageInfo.versionCodeCompat: Long
        get() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private fun PackageInfo.signerDigests(): Set<String> {
        val info = signingInfo ?: return emptySet()
        val signers = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return signers.orEmpty().map { sig ->
            with(UpdateSignature) { MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toHex() }
        }.toSet()
    }
}
