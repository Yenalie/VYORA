package com.vyora.music.playback.cast

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CastStatus(
    val available: Boolean = false,
    val deviceName: String? = null,
) {
    val connected: Boolean get() = deviceName != null
}

object CastSupport {
    private const val TAG = "VYORA-Cast"

    private val _status = MutableStateFlow(CastStatus())
    val status: StateFlow<CastStatus> = _status.asStateFlow()

    fun hasPlayServices(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun context(context: Context): CastContext? {
        if (!hasPlayServices(context)) return null
        return runCatching { CastContext.getSharedInstance(context.applicationContext) }
            .onFailure { Log.w(TAG, "Cast unavailable", it) }
            .getOrNull()
    }

    fun selector(context: Context): MediaRouteSelector? = context(context)?.mergedSelector

    fun stop(context: Context) {
        runCatching { context(context)?.sessionManager?.endCurrentSession(true) }
    }

    internal fun publish(available: Boolean, deviceName: String?) {
        _status.value = CastStatus(available, deviceName)
    }
}
