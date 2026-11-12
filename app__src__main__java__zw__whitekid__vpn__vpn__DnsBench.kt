package zw.whitekid.vpn.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/** Measures how fast public DNS resolvers answer, so the app can pick the quickest one. */
object DnsBench {
    data class Result(val name: String, val servers: String, val ms: Int)

    val CANDIDATES = linkedMapOf(
        "Cloudflare" to "1.1.1.1,1.0.0.1",
        "Google" to "8.8.8.8,8.8.4.4",
        "Quad9" to "9.9.9.9,149.112.112.112",
        "AdGuard" to "94.140.14.14,94.140.15.15",
        "OpenDNS" to "208.67.222.222,208.67.220.220",
        "CleanBrowsing" to "185.228.168.9,185.228.169.9",
        "Control D" to "76.76.2.0,76.76.10.0"
    )

    fun packet(): ByteArray {
        val label = "wk" + Random.nextInt(100000, 999999)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (part in listOf(label, "example", "com")) { out.write(part.length); out.write(part.toByteArray()) }
        out.write(0); out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun once(ip: String): Int {
        DatagramSocket().use { s ->
            s.soTimeout = 1500
            val q = packet()
            val t = System.nanoTime()
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 53))
            val r = DatagramPacket(ByteArray(512), 512)
            s.receive(r)
            return ((System.nanoTime() - t) / 1_000_000).toInt()
        }
    }

    /** Sorted fastest first. Resolvers that don't answer are left out. */
    fun run(): List<Result> = CANDIDATES.mapNotNull { (name, servers) ->
        val times = (1..3).mapNotNull { runCatching { once(servers.substringBefore(",")) }.getOrNull() }
        if (times.size >= 2) Result(name, servers, times.sum() / times.size) else null
    }.sortedBy { it.ms }

    /** Quick parallel test (about 2 s): the fastest resolver that answers, or null. */
    fun fastest(): Result? {
        val out = java.util.concurrent.ConcurrentLinkedQueue<Result>()
        val ts = CANDIDATES.map { (name, servers) ->
            kotlin.concurrent.thread(isDaemon = true) {
                val t = runCatching { once(servers.substringBefore(",")) }.getOrNull()
                if (t != null) out.add(Result(name, servers, t))
            }
        }
        ts.forEach { runCatching { it.join(2500) } }
        return out.minByOrNull { it.ms }
    }
}
