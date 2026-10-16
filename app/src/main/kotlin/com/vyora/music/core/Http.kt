package com.vyora.music.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * One OkHttp client for the whole app. Every feature derives its own timeouts with [OkHttpClient.newBuilder],
 * which shares the connection pool, dispatcher threads and TLS session cache instead of each module paying for
 * its own.
 */
object Http {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun with(connectSec: Long, readSec: Long, writeSec: Long = readSec): OkHttpClient = base.newBuilder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .writeTimeout(writeSec, TimeUnit.SECONDS)
        .build()

    const val USER_AGENT = "VYORA (Android; +https://github.com/Yenalie/VYORA)"
}
