package zw.whitekid.vpn.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.Inet4Address

object NetInfo {
    /** True when the phone is on mobile data (and not Wi-Fi). Check before the VPN is up. */
    fun onMobile(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * The phone's real (non-VPN) network that the tunnel rides on. Android's own "validated" flag is NOT required:
     * zero-rated bundles and walled gardens often fail that test while the tunnel still works.
     */
    @Suppress("DEPRECATION")
    fun realNetwork(ctx: Context): android.net.Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .sortedByDescending { (_, c) ->
                (if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) + (if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0)
            }
            .firstOrNull()?.first
    }

    /** True while the phone has any real network (Wi-Fi or mobile data) to carry the tunnel. */
    @Suppress("DEPRECATION")
    fun online(ctx: Context): Boolean {
        if (realNetwork(ctx) != null) return true
        // fallback for phones that report capabilities oddly: trust the classic "connected" flag of a non-VPN network
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { cm.allNetworks.any { n -> cm.getNetworkInfo(n)?.isConnected == true && cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true } }.getOrDefault(false)
    }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Auto-connect rule from Tools: 0 = any network, 1 = mobile data only, 2 = Wi-Fi only. */
    fun ruleAllows(ctx: Context, rule: Int): Boolean = when (rule) { 1 -> onMobile(ctx); 2 -> onWifi(ctx); else -> true }

    /** Changes when the phone moves to another network (Wi-Fi to mobile data and so on). */
    fun netKey(ctx: Context): String = realNetwork(ctx)?.toString() ?: "none"

    /** "192.168.1.5 • WIFI" for the real connection, even while the VPN is on. */
    fun localIp(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "—"
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    else -> continue
                }
                val ip = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: continue
                return "$ip • $kind"
            }
            "Offline"
        } catch (_: Exception) { "—" }
    }
}

object RootCheck {
    /** A basic check. It stops casual use, but a determined user can hide root. */
    fun isRooted(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/system/app/Superuser.apk").any { File(it).exists() } ||
            (android.os.Build.TAGS?.contains("test-keys") == true)
}
