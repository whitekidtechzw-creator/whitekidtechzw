package zw.whitekid.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Real-time network events. Android tells us the instant Wi-Fi or mobile data appears or disappears;
 * [version] changes each time, so the connection supervisor reacts at once instead of waiting for its next poll.
 */
object NetWatcher {
    @Volatile var version = 0
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun start(ctx: Context) {
        if (cb != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { version++ }
            override fun onLost(network: Network) { version++ }
        }
        runCatching { cm.registerNetworkCallback(req, c); cb = c }
    }

    fun stop(ctx: Context) {
        val c = cb ?: return
        runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(c) }
        cb = null
    }
}
