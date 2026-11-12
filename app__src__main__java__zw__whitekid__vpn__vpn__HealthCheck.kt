package zw.whitekid.vpn.vpn

import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Proves a tunnel really carries traffic. WireGuard captures this app's own traffic, so a plain request goes
 * through it. Xray/SSH/SlowDNS are tested through the local SOCKS port of the core.
 */
object HealthCheck {
    private val URLS = listOf("http://cp.cloudflare.com/generate_204", "http://connectivitycheck.gstatic.com/generate_204")

    data class SpeedResult(val ping: Int, val jitter: Int, val down: Double, val up: Double) {
        fun text(): String {
            val d = if (down >= 0) "↓ %.1f".format(down) else "↓ –"
            val u = if (up >= 0) "↑ %.1f".format(up) else "↑ –"
            return "$d  $u Mbps\n$ping ms • jitter ±$jitter ms"
        }
    }

    fun open(p: Profile, url: String, timeoutMs: Int = 6000): HttpURLConnection {
        val u = URL(url)
        val c = if (p.proto == Proto.WIREGUARD) u.openConnection()
        else u.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_IN)))
        return (c as HttpURLConnection).apply { connectTimeout = timeoutMs; readTimeout = timeoutMs; instanceFollowRedirects = false }
    }

    /** Round-trip time in ms, or throws with a readable reason. Both test URLs run at once; the first answer wins. */
    fun run(p: Profile, timeoutMs: Int = 6000): Int {
        val best = java.util.concurrent.atomic.AtomicInteger(-1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        for (url in URLS) kotlin.concurrent.thread(isDaemon = true) {
            val t = System.nanoTime()
            try {
                val c = open(p, url, timeoutMs)
                val code = try { c.responseCode } finally { c.disconnect() }
                if (code == 204 || code == 200) best.compareAndSet(-1, ((System.nanoTime() - t) / 1_000_000).toInt())
                else errors.add("unexpected reply $code")
            } catch (e: Exception) { errors.add(e.message ?: e.javaClass.simpleName) }
            finally { done.incrementAndGet() }
        }
        val end = System.currentTimeMillis() + timeoutMs + 2000
        while (best.get() < 0 && done.get() < URLS.size && System.currentTimeMillis() < end) Thread.sleep(50)
        if (best.get() >= 0) return best.get()
        throw IllegalStateException(errors.firstOrNull() ?: "no reply")
    }

    /** Average round trip and jitter over a few quick requests. */
    fun pingStats(p: Profile, samples: Int = 5): Pair<Int, Int> {
        val t = ArrayList<Int>()
        repeat(samples) {
            val s = System.nanoTime()
            try {
                val c = open(p, URLS[0], 8000)
                val code = try { c.responseCode } finally { c.disconnect() }
                if (code == 204 || code == 200) t.add(((System.nanoTime() - s) / 1_000_000).toInt())
            } catch (_: Exception) {}
        }
        if (t.isEmpty()) throw IllegalStateException("no reply")
        val jitter = if (t.size < 2) 0 else (1 until t.size).sumOf { Math.abs(t[it] - t[it - 1]) } / (t.size - 1)
        return t.sum() / t.size to jitter
    }

    /** Rough download speed in Mbit/s (up to 8 MB or 8 seconds). */
    fun speedMbps(p: Profile): Double {
        val c = open(p, "https://speed.cloudflare.com/__down?bytes=8000000")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val start = System.nanoTime()
        var total = 0L
        try {
            c.inputStream.use { s ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    total += n
                    if ((System.nanoTime() - start) > 8_000_000_000L) break
                }
            }
        } finally { c.disconnect() }
        val secs = (System.nanoTime() - start) / 1e9
        if (total == 0L || secs <= 0) throw IllegalStateException("no data")
        return total * 8 / 1_000_000.0 / secs
    }

    /** Rough upload speed in Mbit/s (up to 4 MB or 6 seconds). */
    fun uploadMbps(p: Profile): Double {
        val c = open(p, "https://speed.cloudflare.com/__up", 8000)
        c.requestMethod = "POST"; c.doOutput = true; c.setChunkedStreamingMode(16 * 1024)
        val start = System.nanoTime()
        var sent = 0L
        try {
            val os = c.outputStream
            val buf = ByteArray(16 * 1024)
            while (sent < 4_000_000L && System.nanoTime() - start < 6_000_000_000L) { os.write(buf); sent += buf.size }
            os.flush()
        } finally { runCatching { c.disconnect() } }
        val secs = (System.nanoTime() - start) / 1e9
        if (sent == 0L || secs <= 0) throw IllegalStateException("no data")
        return sent * 8 / 1_000_000.0 / secs
    }

    /** Ping, jitter, download and upload in one go. A part that fails shows as – instead of failing the whole test. */
    fun fullTest(p: Profile): SpeedResult {
        val (ping, jitter) = pingStats(p)
        val down = runCatching { speedMbps(p) }.getOrDefault(-1.0)
        val up = runCatching { uploadMbps(p) }.getOrDefault(-1.0)
        return SpeedResult(ping, jitter, down, up)
    }
}
