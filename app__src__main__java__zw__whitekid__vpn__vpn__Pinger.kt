package zw.whitekid.vpn.vpn

import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import java.net.InetSocketAddress
import java.net.Socket

object Pinger {
    /** TCP connect time in ms. -1 = unreachable, -2 = not applicable (UDP / DNS based protocols). */
    fun tcp(p: Profile): Int {
        if (p.proto == Proto.WIREGUARD || p.proto == Proto.SLOWDNS) return -2
        val t = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(p.host, p.port), 3000) }
            ((System.nanoTime() - t) / 1_000_000).toInt()
        } catch (_: Exception) { -1 }
    }
}
