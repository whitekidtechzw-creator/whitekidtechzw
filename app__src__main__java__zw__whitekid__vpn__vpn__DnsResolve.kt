package zw.whitekid.vpn.vpn

import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Store
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Looks names up straight against Google DNS (or the DNS chosen in Tools) over UDP.
 * This app is outside the VPN, so these queries go out directly on the real network. Used for the VPN server's own
 * name, which has to be found before the tunnel exists.
 */
object DnsResolve {
    private val cache = ConcurrentHashMap<String, String>()
    private val ip4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    fun isIp(s: String) = ip4.matches(s) || s.contains(':')
    fun clear() = cache.clear()

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun query(host: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (part in host.trim('.').split('.')) { out.write(part.length); out.write(part.toByteArray()) }
        out.write(0); out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))   // type A, class IN
        return out.toByteArray()
    }

    /** First IPv4 address in the answer, or null. */
    private fun parseA(b: ByteArray, len: Int, id: Int): String? {
        if (len < 12 || u16(b, 0) != id || (b[3].toInt() and 0x0f) != 0) return null
        val qd = u16(b, 4); val an = u16(b, 6)
        var pos = 12
        fun skipName() {
            while (pos < len) {
                val l = b[pos].toInt() and 0xff
                if (l == 0) { pos++; return }
                if ((l and 0xC0) == 0xC0) { pos += 2; return }
                pos += l + 1
            }
        }
        repeat(qd) { skipName(); pos += 4 }
        repeat(an) {
            if (pos >= len) return null
            skipName()
            if (pos + 10 > len) return null
            val type = u16(b, pos); val rdlen = u16(b, pos + 8)
            pos += 10
            if (type == 1 && rdlen == 4 && pos + 4 <= len)
                return (0 until 4).joinToString(".") { (b[pos + it].toInt() and 0xff).toString() }
            pos += rdlen
        }
        return null
    }

    private fun ask(server: String, host: String): String? {
        val id = Random.nextInt(0, 65536)
        val q = query(host, id)
        return DatagramSocket().use { s ->
            s.soTimeout = 2000
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1232)
            val r = DatagramPacket(buf, buf.size)
            s.receive(r)
            parseA(buf, r.length, id)
        }
    }

    /** True if this resolver answers on the current network. */
    fun alive(server: String): Boolean = (1..2).any { runCatching { ask(server, "dns.google") }.getOrNull() != null }

    /** The resolvers from [list] that answer right now, in their original order. All are probed at the same time. */
    fun aliveList(list: List<String>): List<String> {
        val ok = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val ts = list.map { s -> kotlin.concurrent.thread(isDaemon = true) { if (alive(s)) ok.add(s) } }
        ts.forEach { runCatching { it.join(5000) } }
        return list.filter { it in ok }
    }

    /** IPv4 for [host] (an IP is returned as is), asking the chosen DNS first and Google as backup. Null if nothing answered. */
    fun resolve(host: String, servers: List<String> = Store.dnsList()): String? {
        if (isIp(host)) return host
        cache[host]?.let { return it }
        for (srv in (servers + listOf("8.8.8.8", "8.8.4.4")).distinct()) {
            for (attempt in 1..2) {
                val ip = runCatching { ask(srv, host) }.getOrNull()
                if (ip != null) { cache[host] = ip; AppLog.log("DNS: server name resolved through $srv"); return ip }
            }
        }
        return null
    }
}
