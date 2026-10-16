package com.vyora.music.playback.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.playback.source.YouTubeDataSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
object CastProxy {
    private const val TAG = "VYORA-CastProxy"
    private const val BUFFER = 64 * 1024
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_CONNECTIONS = 6

    private val types = ConcurrentHashMap<String, String>()
    private val lock = Any()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var pool: ExecutorService? = null
    @Volatile private var factory: DataSource.Factory? = null
    @Volatile private var token: String = ""

    @Volatile private var appContext: Context? = null

    fun start(context: Context) {
        synchronized(lock) {
            if (server?.isClosed == false) return
            appContext = context.applicationContext
            factory = YouTubeDataSource.audioFactory(context.applicationContext)
            token = randomToken()
            val socket = ServerSocket()
            socket.reuseAddress = true
            // Only the Wi-Fi/Ethernet address the Cast device can reach, not every interface (mobile data etc.).
            socket.bind(InetSocketAddress(lanAddress(), 0))
            server = socket
            // A fixed pool: a receiver needs a couple of connections; anything beyond that just queues.
            val executor = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r -> Thread(r, "vyora-cast-proxy").apply { isDaemon = true } }
            pool = executor
            executor.execute { acceptLoop(socket, executor) }
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching { server?.close() }
            server = null
            pool?.shutdownNow()
            pool = null
        }
    }

    fun urlFor(videoId: String): String {
        rebindIfNetworkChanged()
        val socket = server
        val host = lanAddress()?.hostAddress
        if (socket == null || socket.isClosed || host == null) return SimpleItem.watchUrl(videoId)
        return "http://$host:${socket.localPort}/$token/audio/${Uri.encode(videoId)}"
    }

    /** The socket is bound to one LAN address; follow the device when it hops to another network. */
    private fun rebindIfNetworkChanged() {
        val socket = server ?: return
        val bound = socket.inetAddress ?: return
        if (bound.isAnyLocalAddress) return
        val current = lanAddress() ?: return
        if (current == bound) return
        val context = appContext ?: return
        stop()
        runCatching { start(context) }.onFailure { Log.w(TAG, "Rebind failed", it) }
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            runCatching { executor.execute { handle(client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            try {
                val request = readRequest(input) ?: return
                serve(request, output)
            } catch (_: SocketException) {
            } catch (e: IOException) {
                Log.w(TAG, "Request failed", e)
                runCatching { status(output, 502, "Bad Gateway") }
            }
        }
    }

    private class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): HttpRequest? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            total++
            if (total > MAX_HEADER_BYTES) return null
            if (b == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.setLength(0)
                if (text.isEmpty()) break
                lines += text
            } else {
                line.append(b.toChar())
            }
        }
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val headers = lines.drop(1).mapNotNull { h ->
            val i = h.indexOf(':')
            if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
        }.toMap()
        return HttpRequest(first[0].uppercase(), first[1], headers)
    }

    private fun serve(request: HttpRequest, out: OutputStream) {
        if (request.method == "OPTIONS") {
            write(out, "HTTP/1.1 204 No Content", corsHeaders() + ("Content-Length" to "0"))
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            status(out, 405, "Method Not Allowed")
            return
        }
        val parts = request.path.substringBefore('?').trim('/').split('/')
        if (parts.size != 3 || parts[0] != token || parts[1] != "audio") {
            status(out, 404, "Not Found")
            return
        }
        val videoId = Uri.decode(parts[2])
        if (!isServable(videoId)) {
            status(out, 404, "Not Found")
            return
        }
        val range = parseRange(request.headers["range"])
        val start = range?.first ?: 0L
        val source = requireNotNull(factory).createDataSource()
        val uri = Uri.parse(SimpleItem.playbackUrl(videoId))
        try {
            val remaining = source.open(DataSpec.Builder().setUri(uri).setPosition(start).build())
            val total = if (remaining == C.LENGTH_UNSET.toLong()) -1L else start + remaining
            val end = when {
                total < 0 -> -1L
                range?.second != null -> minOf(range.second!!, total - 1)
                else -> total - 1
            }
            if (total >= 0 && start >= total) {
                write(out, "HTTP/1.1 416 Range Not Satisfiable", corsHeaders() + listOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                return
            }
            val head = ByteArray(BUFFER)
            var headLength = 0
            if (request.method == "GET" || types[videoId] == null) {
                headLength = readFully(source, head, if (end >= 0) minOf(head.size.toLong(), end - start + 1).toInt() else head.size)
            }
            val type = types[videoId] ?: if (start == 0L && headLength >= 12) sniff(head).also { types[videoId] = it } else probe(uri, videoId)
            val headers = corsHeaders().toMutableList()
            headers += "Content-Type" to type
            headers += "Accept-Ranges" to "bytes"
            headers += "Cache-Control" to "no-store"
            headers += "Connection" to "close"
            val statusLine = if (range != null && total >= 0) {
                headers += "Content-Range" to "bytes $start-$end/$total"
                "HTTP/1.1 206 Partial Content"
            } else {
                "HTTP/1.1 200 OK"
            }
            if (end >= 0) headers += "Content-Length" to (end - start + 1).toString()
            write(out, statusLine, headers)
            if (request.method == "HEAD") return
            var left = if (end >= 0) end - start + 1 else Long.MAX_VALUE
            if (headLength > 0) {
                val n = minOf(headLength.toLong(), left).toInt()
                out.write(head, 0, n)
                left -= n
            }
            val buffer = ByteArray(BUFFER)
            while (left > 0) {
                val n = source.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
                left -= n
            }
            out.flush()
        } finally {
            runCatching { source.close() }
        }
    }

    private fun probe(uri: Uri, videoId: String): String {
        val source = requireNotNull(factory).createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(16).build())
            val head = ByteArray(16)
            val n = readFully(source, head, head.size)
            if (n >= 8) sniff(head).also { types[videoId] = it } else "audio/mp4"
        } catch (_: IOException) {
            "audio/mp4"
        } finally {
            runCatching { source.close() }
        }
    }

    private fun readFully(source: DataSource, buffer: ByteArray, length: Int): Int {
        var read = 0
        while (read < length) {
            val n = source.read(buffer, read, length - read)
            if (n == C.RESULT_END_OF_INPUT) break
            read += n
        }
        return read
    }

    /** YouTube video ids, or `local:<MediaStore id>` for files on this device. */
    internal fun isServable(id: String): Boolean =
        id.matches(VIDEO_ID) || (SimpleItem.isLocalId(id) && id.removePrefix(SimpleItem.LOCAL_PREFIX).matches(DIGITS))

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")
    private val DIGITS = Regex("[0-9]{1,19}")

    internal fun sniff(head: ByteArray): String = when {
        head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> "audio/flac"
        head.size >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() -> "audio/wav"
        head.size >= 4 && head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() && head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte() -> "audio/ogg"
        head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> "audio/mpeg"
        head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "audio/webm"
        head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> "audio/mp4"
        else -> "audio/mp4"
    }

    internal fun parseRange(header: String?): Pair<Long, Long?>? {
        val value = header?.trim()?.lowercase() ?: return null
        if (!value.startsWith("bytes=")) return null
        val spec = value.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val from = spec.substring(0, dash).trim()
        val to = spec.substring(dash + 1).trim()
        if (from.isEmpty()) return null
        val start = from.toLongOrNull() ?: return null
        val end = if (to.isEmpty()) null else to.toLongOrNull() ?: return null
        if (end != null && end < start) return null
        return start to end
    }

    private fun corsHeaders(): List<Pair<String, String>> = listOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "Range, Content-Type",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges",
    )

    private fun status(out: OutputStream, code: Int, text: String) {
        write(out, "HTTP/1.1 $code $text", corsHeaders() + listOf("Content-Length" to "0", "Connection" to "close"))
    }

    private fun write(out: OutputStream, statusLine: String, headers: List<Pair<String, String>>) {
        val text = buildString {
            append(statusLine).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun lanAddress(): InetAddress? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } }
            .filter { (_, addr) -> addr is Inet4Address && addr.isSiteLocalAddress }
        candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("eth") }?.second
            ?: candidates.firstOrNull { (name, _) -> !name.startsWith("rmnet") && !name.startsWith("ccmni") }?.second
            ?: candidates.firstOrNull()?.second
    }.getOrNull()
}
