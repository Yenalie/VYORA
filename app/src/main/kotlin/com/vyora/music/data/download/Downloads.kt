package com.vyora.music.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.vyora.music.data.settings.SettingsRepository
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.source.MediaCache
import com.vyora.music.playback.source.OfflineCache
import com.vyora.music.playback.source.YouTubeDataSource
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

data class DownloadEntry(
    val item: SimpleItem,
    val status: DownloadStatus,
    val progress: Float,
    val bytes: Long,
    val updatedAt: Long,
)

@OptIn(UnstableApi::class)
object Downloads {
    private const val TAG = "VYORA-Downloads"
    private const val ART_DIR = "offline_art"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { com.vyora.music.core.Http.base }

    @Volatile private var manager: DownloadManager? = null
    private var appContext: Context? = null
    private var progressJob: Job? = null
    @Volatile private var savedArt: Set<String> = emptySet()

    private val _entries = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DownloadEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            val m = runCatching { manager(context) }.onFailure { Log.e(TAG, "Download manager unavailable", it) }.getOrNull()
                ?: return@launch
            SettingsRepository.settings
                .map { it.downloadWifiOnly }
                .distinctUntilChanged()
                .collect { wifiOnly -> m.requirements = requirementsFor(wifiOnly) }
        }
        scope.launch {
            SettingsRepository.settings
                .map { it.exportDownloads }
                .distinctUntilChanged()
                .collect { export -> if (export) exportCompleted() }
        }
    }

    fun manager(context: Context): DownloadManager {
        manager?.let { return it }
        val app = context.applicationContext
        appContext = app
        return synchronized(this) {
            manager ?: DownloadManager(
                app,
                MediaCache.database(app),
                OfflineCache.get(app),
                YouTubeDataSource.downloadUpstreamFactory(app),
                Executors.newFixedThreadPool(2),
            ).apply {
                maxParallelDownloads = 2
                minRetryCount = 3
                requirements = requirementsFor(SettingsRepository.current.downloadWifiOnly)
                addListener(object : DownloadManager.Listener {
                    override fun onInitialized(downloadManager: DownloadManager) = reload(downloadManager)
                    override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                        update(download)
                        if (download.state == Download.STATE_DOWNLOADING) watchProgress(downloadManager)
                    }
                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                        remove(download.request.id)
                    }
                })
                if (isInitialized) reload(this)
            }.also { manager = it }
        }
    }

    fun status(id: String): DownloadStatus? = _entries.value[id]?.status

    fun download(context: Context, item: SimpleItem) = downloadAll(context, listOf(item))

    fun downloadAll(context: Context, items: List<SimpleItem>) {
        val app = context.applicationContext
        val songs = items.filter { it.isSong && it.id.isNotBlank() && (it.isYouTube || it.isServer) }
            .filter { _entries.value[it.id]?.status.let { s -> s == null || s == DownloadStatus.FAILED } }
            .distinctBy { it.id }
        if (songs.isEmpty()) return
        manager(app)
        for (item in songs) {
            val request = DownloadRequest.Builder(item.id, Uri.parse(SimpleItem.playbackUrl(item.id)))
                .setCustomCacheKey(OfflineCache.keyFor(item.id))
                .setData(encode(item).toByteArray(Charsets.UTF_8))
                .build()
            _entries.value = _entries.value + (item.id to DownloadEntry(item, DownloadStatus.QUEUED, 0f, 0L, System.currentTimeMillis()))
            runCatching { DownloadService.sendAddDownload(app, VyoraDownloadService::class.java, request, false) }
                .onFailure {
                    Log.w(TAG, "Download service could not start", it)
                    manager(app).addDownload(request)
                }
            saveArtwork(app, item)
        }
    }

    fun remove(context: Context, id: String) {
        val app = context.applicationContext
        val thumbnail = _entries.value[id]?.item?.thumbnail
        runCatching { DownloadService.sendRemoveDownload(app, VyoraDownloadService::class.java, id, false) }
            .onFailure { manager(app).removeDownload(id) }
        remove(id)
        thumbnail?.takeIf { url -> _entries.value.values.none { it.item.thumbnail == url } }?.let {
            savedArt = savedArt - it
            io.launch { artFile(app, it).delete() }
        }
    }

    fun removeAll(context: Context) {
        val app = context.applicationContext
        runCatching { DownloadService.sendRemoveAllDownloads(app, VyoraDownloadService::class.java, false) }
            .onFailure { manager(app).removeAllDownloads() }
        _entries.value = emptyMap()
        OfflineCache.completed = emptySet()
        savedArt = emptySet()
        io.launch { app.filesDir.resolve(ART_DIR).listFiles()?.forEach { it.delete() } }
    }

    fun resumePending(context: Context) {
        val pending = _entries.value.values.any { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
        if (!pending) return
        runCatching { DownloadService.start(context.applicationContext, VyoraDownloadService::class.java) }
            .onFailure { Log.w(TAG, "Could not resume downloads", it) }
    }

    fun usedBytes(context: Context): Long = runCatching { OfflineCache.usedBytes(context) }.getOrDefault(0L)

    fun artFile(context: Context, url: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return context.applicationContext.filesDir.resolve(ART_DIR).resolve("$name.jpg")
    }

    fun offlineArtwork(context: Context, url: String): File? {
        if (url !in savedArt) return null
        return artFile(context, url)
    }

    private fun requirementsFor(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    private fun reload(m: DownloadManager) {
        io.launch {
            val list = mutableListOf<Download>()
            runCatching {
                m.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) list += cursor.download
                }
            }.onFailure { Log.w(TAG, "Download index unreadable", it) }
            val app = appContext
            val entries = list.mapNotNull(::toEntry)
            if (app != null) {
                savedArt = entries.mapNotNull { it.item.thumbnail }.filter { artFile(app, it).length() > 0 }.toSet()
            }
            scope.launch {
                val map = LinkedHashMap<String, DownloadEntry>()
                entries.forEach { map[it.item.id] = it }
                _entries.value = map
                publishCompleted()
                if (SettingsRepository.current.exportDownloads) exportCompleted()
                if (list.any { it.state == Download.STATE_DOWNLOADING }) watchProgress(m)
            }
        }
    }

    private fun update(download: Download) {
        val entry = toEntry(download)
        if (entry == null) {
            remove(download.request.id)
            return
        }
        val was = _entries.value[entry.item.id]?.status
        _entries.value = _entries.value + (entry.item.id to entry)
        publishCompleted()
        if (entry.status == DownloadStatus.COMPLETED && was != DownloadStatus.COMPLETED && SettingsRepository.current.exportDownloads) {
            exportCompleted()
        }
    }

    @Volatile private var exporting: Job? = null

    /** Copies every finished, not-yet-exported download into Music/VYORA, one at a time. */
    private fun exportCompleted() {
        val app = appContext ?: return
        if (exporting?.isActive == true) {
            exporting?.invokeOnCompletion { scope.launch { exportCompleted() } }
            return
        }
        val done = _entries.value.values.filter { it.status == DownloadStatus.COMPLETED }.map { it.item }
        exporting = io.launch {
            for (item in done) {
                if (!SettingsRepository.current.exportDownloads) break
                if (!DownloadExport.isExported(app, item.id)) DownloadExport.export(app, item)
            }
        }
    }

    private fun remove(id: String) {
        if (id !in _entries.value) return
        _entries.value = _entries.value - id
        publishCompleted()
    }

    private fun publishCompleted() {
        OfflineCache.completed = _entries.value.values.filter { it.status == DownloadStatus.COMPLETED }.map { it.item.id }.toSet()
    }

    private fun watchProgress(m: DownloadManager) {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val active = m.currentDownloads.filter { it.state == Download.STATE_DOWNLOADING }
                if (active.isEmpty()) break
                active.forEach(::update)
                delay(700)
            }
        }
    }

    private fun toEntry(download: Download): DownloadEntry? {
        val item = decode(download.request.id, download.request.data) ?: return null
        val status = when (download.state) {
            Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_FAILED -> DownloadStatus.FAILED
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> DownloadStatus.QUEUED
            else -> return null
        }
        val percent = download.percentDownloaded
        val progress = when {
            status == DownloadStatus.COMPLETED -> 1f
            percent >= 0f -> (percent / 100f).coerceIn(0f, 1f)
            else -> 0f
        }
        return DownloadEntry(item, status, progress, download.bytesDownloaded, download.updateTimeMs)
    }

    private fun saveArtwork(context: Context, item: SimpleItem) {
        val url = item.thumbnail?.takeIf { it.startsWith("http") } ?: return
        io.launch {
            val file = artFile(context, url)
            if (file.length() > 0) {
                savedArt = savedArt + url
                return@launch
            }
            runCatching {
                file.parentFile?.mkdirs()
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    val tmp = File(file.path + ".part")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                    if (tmp.renameTo(file)) savedArt = savedArt + url
                }
            }.onFailure { Log.w(TAG, "Artwork not saved for ${item.id}", it) }
        }
    }

    internal fun encode(item: SimpleItem): String = JSONObject()
        .put("id", item.id)
        .put("title", item.title)
        .put("artist", item.artist)
        .put("thumbnail", item.thumbnail.orEmpty())
        .put("duration", item.duration)
        .put("artistId", item.artistId.orEmpty())
        .put("video", item.isVideo)
        .toString()

    internal fun decode(id: String, data: ByteArray?): SimpleItem? {
        if (id.isBlank()) return null
        val json = runCatching { JSONObject(String(data ?: ByteArray(0), Charsets.UTF_8)) }.getOrNull()
        return SimpleItem(
            id = id,
            title = json?.optString("title").orEmpty().ifBlank { id },
            artist = json?.optString("artist").orEmpty(),
            thumbnail = json?.optString("thumbnail")?.takeIf { it.isNotBlank() },
            url = SimpleItem.playbackUrl(id),
            duration = json?.optLong("duration") ?: 0L,
            type = ItemType.SONG,
            artistId = json?.optString("artistId")?.takeIf { it.isNotBlank() },
            isVideo = json?.optBoolean("video") == true,
        )
    }
}
