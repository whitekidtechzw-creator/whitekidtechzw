package zw.whitekid.vpn.vpn

import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Confirms DNS really works through the tunnel.
 *
 * Why the old check showed "DNS 8.8.8.8 isn't answering" even when browsing worked: it asked ONE resolver once, with a
 * 5 s limit. Over SSH / SlowDNS the first query takes longer than that, and some SSH servers refuse port 53 to a raw IP
 * even though they resolve names perfectly. Now it tries your resolver twice with a patient limit, then the backup
 * resolvers, and finally proves that names resolve through the tunnel. It only reports a problem when ALL of that fails.
 */
object DnsCheck {
    /** server = the resolver that answered, how = "" (direct answer), "backup" or "tunnel" (names resolve through the tunnel). */
    data class Result(val server: String, val ms: Int, val how: String)

    private class Refused(msg: String) : Exception(msg)

    private fun readFully(i: java.io.InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = i.read(b, off, n - off); if (r < 0) error("connection closed"); off += r }
        return b
    }

    /** Milliseconds for one query to [dnsIp] through the tunnel. Throws if it fails. */
    fun run(p: Profile, dnsIp: String, timeoutMs: Int = 6000): Int {
        val addr = InetAddress.getByName(dnsIp).address
        if (addr.size != 4) return 0 // IPv6 resolvers aren't checked
        val q = DnsBench.packet()
        val t = System.nanoTime()
        if (p.proto == Proto.WIREGUARD) {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                s.send(DatagramPacket(q, q.size, InetAddress.getByAddress(addr), 53))
                s.receive(DatagramPacket(ByteArray(512), 512))
            }
        } else {
            Socket().use { s ->
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_IN), 5000)
                val o = s.getOutputStream(); val i = s.getInputStream()
                o.write(byteArrayOf(5, 1, 0)); o.flush()
                val g = readFully(i, 2); if (g[1].toInt() != 0) throw Refused("proxy refused")
                o.write(byteArrayOf(5, 1, 0, 1) + addr + byteArrayOf(0, 53)); o.flush()
                val rep = readFully(i, 10); if (rep[1].toInt() != 0) throw Refused("tunnel refused the DNS server (code ${rep[1]})")
                o.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte())); o.write(q); o.flush()
                val len = readFully(i, 2); val n = ((len[0].toInt() and 0xff) shl 8) or (len[1].toInt() and 0xff)
                val resp = readFully(i, minOf(n, 512))
                if (resp.size < 12) error("bad DNS reply")
            }
        }
        return ((System.nanoTime() - t) / 1_000_000).toInt()
    }

    /**
     * Full verification. [preferred] is the resolver list in use (the first one is the one the user chose).
     * Returns null only when no resolver answers AND no name can be reached through the tunnel.
     */
    fun verify(p: Profile, preferred: List<String>): Result? {
        val slow = p.proto == Proto.SLOWDNS
        val ssh = p.proto == Proto.SSH
        val tmo = if (slow) 15_000 else if (ssh) 9_000 else 6_000
        val order = (preferred + listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "9.9.9.9")).distinct().take(5)
        var refused = false
        for ((idx, ip) in order.withIndex()) {
            if (refused) break
            val tries = if (idx == 0) 2 else 1
            for (n in 1..tries) {
                try {
                    val ms = run(p, ip, tmo)
                    return Result(ip, ms, if (idx == 0) "" else "backup")
                } catch (e: Refused) {
                    AppLog.log("DNS check: ${e.message}")
                    refused = true; break      // the server forbids raw DNS ports: other resolvers won't help, go to the name test
                } catch (e: Exception) {
                    AppLog.log("DNS check $ip (try $n): ${e.message ?: e.javaClass.simpleName}")
                    if (idx >= 2) break
                }
            }
        }
        // Last proof: a real request by NAME. The core resolves the name with its own DNS chain; if this works, DNS works.
        return try {
            val ms = HealthCheck.run(p, tmo)
            Result(preferred.firstOrNull() ?: "8.8.8.8", ms, "tunnel")
        } catch (e: Exception) {
            AppLog.log("DNS check: name test failed: ${e.message}")
            null
        }
    }

    /** Text for the DNS line on the Connect screen. */
    fun describe(r: Result?, wanted: String): String = when {
        r == null -> "⚠ DNS $wanted isn't answering. Tap RETEST DNS, or switch DNS below"
        r.how == "tunnel" -> "DNS working • names resolve through the tunnel (${r.ms} ms)"
        r.how == "backup" -> "DNS ${r.server} • ${r.ms} ms (backup; $wanted was silent)"
        else -> "DNS ${r.server} • ${r.ms} ms"
    }
}
