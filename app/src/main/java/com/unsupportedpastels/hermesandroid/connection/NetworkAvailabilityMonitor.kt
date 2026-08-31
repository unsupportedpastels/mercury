package com.unsupportedpastels.hermesandroid.connection

import android.net.ConnectivityManager
import android.net.Network

/**
 * Thin platform adapter. Callbacks are hints only; they never prove that a
 * loopback Hermes listener exists. The ViewModel debounces them into reducer inputs.
 * Register and unregister from the ViewModel lifetime. Not a component.
 */
class NetworkAvailabilityMonitor(
    private val connectivityManager: ConnectivityManager,
    private val onAvailable: () -> Unit,
) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onAvailable()
        }
    }

    fun register() {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }
}
