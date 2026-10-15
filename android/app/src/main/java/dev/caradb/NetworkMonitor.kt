package dev.caradb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

/** Observe the real default network, including on API 23 where callbacks cover other networks too. */
internal class NetworkMonitor(context: Context, private val changed: () -> Unit) {
    internal data class Link(
        val network: Network,
        val addresses: List<String>,
        val dnsServers: List<String>,
    )

    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var legacyReceiverRegistered = false
    private val legacyDefaultChange = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = changed()
    }

    fun start(): Link? {
        if (Build.VERSION.SDK_INT >= 24) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            val request = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.registerNetworkCallback(request, callback)
        }
        registered = true
        if (Build.VERSION.SDK_INT == 23) {
            // A broad LISTEN callback can miss a default switch between two already-available
            // networks. Marshmallow's dynamic connectivity broadcast supplies that extra trigger.
            @Suppress("DEPRECATION")
            app.registerReceiver(legacyDefaultChange, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            legacyReceiverRegistered = true
        }
        // Registration need not report the initial default network immediately.
        return current()
    }

    fun current(): Link? {
        val network = connectivity.activeNetwork ?: return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        val addresses = properties.linkAddresses.filter {
            !it.address.isAnyLocalAddress && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        }.map { "${it.address.hostAddress}/${it.prefixLength}" }.sorted()
        if (addresses.isEmpty()) return null
        // Deliberately do not require NET_CAPABILITY_VALIDATED (or a public Internet probe).
        return Link(network, addresses, properties.dnsServers.mapNotNull { it.hostAddress }.sorted())
    }

    fun stop() {
        if (legacyReceiverRegistered) {
            legacyReceiverRegistered = false
            try { app.unregisterReceiver(legacyDefaultChange) } catch (_: Exception) { }
        }
        if (!registered) return
        registered = false
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // A callback already removed by Android owns no remaining runtime resources.
        }
    }
}
