package com.vyora.music.data.download

import android.content.Context
import android.net.Uri
import coil.intercept.Interceptor
import coil.request.ImageResult

class OfflineArtInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = when (val data = chain.request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> null
        }
        if (url != null && url.startsWith("http")) {
            val file = Downloads.offlineArtwork(context, url)
            if (file != null) return chain.proceed(chain.request.newBuilder().data(file).build())
        }
        return chain.proceed(chain.request)
    }
}
