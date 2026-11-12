package zw.whitekid.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object TunCheck {
    /**
     * Android tests every VPN network itself, from outside this app, through the tunnel. When that test passes
     * it marks the network VALIDATED, which proves phone traffic really flows through the TUN.
     */
    @Suppress("DEPRECATION")
    fun waitValidated(ctx: Context, timeoutMs: Long): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val ok = cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true
            }
            if (ok) return true
            Thread.sleep(500)
        }
        return false
    }
}
