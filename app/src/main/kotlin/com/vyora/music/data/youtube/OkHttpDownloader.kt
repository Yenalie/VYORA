package com.vyora.music.data.youtube

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

class OkHttpDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(YouTubeConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(YouTubeConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(YouTubeConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(url)
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (request.headers().keys.none { it.equals(USER_AGENT, ignoreCase = true) }) {
            builder.header(USER_AGENT, YouTubeConfig.BROWSER_USER_AGENT)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == YouTubeConfig.HTTP_TOO_MANY_REQUESTS) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val USER_AGENT = "User-Agent"
    }
}
