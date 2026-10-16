package com.vyora.music.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the active connection is metered (mobile data, hotspot), kept fresh by a network callback. */
object NetworkState {
    private val _metered = MutableStateFlow(false)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()
    val isMetered: Boolean get() = _metered.value

    private var cm: ConnectivityManager? = null

    fun init(context: Context) {
        if (cm != null) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        refresh()
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _metered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }

                override fun onLost(network: Network) = refresh()
            })
        }
    }

    private fun refresh() {
        val manager = cm ?: return
        _metered.value = runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
    }
}
