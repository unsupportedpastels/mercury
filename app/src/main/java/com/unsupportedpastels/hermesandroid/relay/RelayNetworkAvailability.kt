package com.unsupportedpastels.hermesandroid.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Content-free default-network signal; owned and closed with the ViewModel. */
internal class RelayNetworkAvailability(context: Context) : AutoCloseable {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mutableAvailable = MutableStateFlow(false)
    val available: StateFlow<Boolean> = mutableAvailable.asStateFlow()
    private var current: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network
            mutableAvailable.value = false // capabilities arrive next; avoid premature retries
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == current) {
                mutableAvailable.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }

        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                mutableAvailable.value = false
            }
        }
    }

    init {
        // Registering reports the existing default network as well as future changes.
        manager.registerDefaultNetworkCallback(callback)
    }

    override fun close() {
        manager.unregisterNetworkCallback(callback)
    }
}
