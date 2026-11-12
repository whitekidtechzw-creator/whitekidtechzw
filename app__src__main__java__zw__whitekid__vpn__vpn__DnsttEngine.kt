package zw.whitekid.vpn.vpn

import android.content.Context
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Profile
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SlowDNS (dnstt): runs the bundled dnstt-client binary, which tunnels TCP through DNS queries and
 * listens on 127.0.0.1:PORT. SshEngine then logs in to the SSH server behind it.
 *
 * Resolver failover: Google DNS first (8.8.8.8, then 8.8.4.4), then Cloudflare and Quad9. Every resolver is probed at the
 * same time; each new attempt moves on to the next one that answers, so one blocked resolver never stops SlowDNS.
 */
class DnsttEngine(private val ctx: Context, private val p: Profile) {
    companion object {
        const val PORT = 7000
        val RESOLVERS = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "9.9.9.9")
    }

    @Volatile private var proc: Process? = null

    /** false once the dnstt process has exited (the watchdog in VpnController then restarts everything) */
    fun isAlive(): Boolean {
        val pr = proc ?: return false
        return try { pr.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }

    fun start(attempt: Int = 0) {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libdnstt.so")
        if (!bin.exists()) error("SlowDNS engine is missing from this build")
        if (p.publicKey.isBlank()) error("SlowDNS needs the server public key")
        val own = p.resolver.trim().takeIf { DnsResolve.isIp(it) && !it.contains(':') }
        val all = (listOfNotNull(own) + RESOLVERS).distinct()
        val up = DnsResolve.aliveList(all).ifEmpty { all }
        val pick = up[attempt % up.size]
        AppLog.log("SlowDNS: resolver $pick (${up.size} of ${all.size} answering, try ${attempt + 1})")
        val pb = ProcessBuilder(bin.absolutePath, "-udp", "$pick:53", "-pubkey", p.publicKey, p.host, "127.0.0.1:$PORT")
            .redirectErrorStream(true)
        if (p.locked) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File("/dev/null")))
        val pr = pb.start()
        proc = pr
        if (!p.locked) thread(name = "dnstt-log", isDaemon = true) {
            runCatching { pr.inputStream.bufferedReader().forEachLine { AppLog.log("dnstt: $it") } }
        }
        // DNS tunnels are slow to wake up, so be patient
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end) {
            if (!isAlive()) error("SlowDNS engine stopped. Check the nameserver and public key")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 300) }
                return
            } catch (_: Exception) { Thread.sleep(250) }
        }
        error("SlowDNS didn't start in time")
    }

    fun stop() {
        val pr = proc; proc = null
        if (pr != null) runCatching {
            pr.destroy()
            if (!pr.waitFor(1500, TimeUnit.MILLISECONDS)) pr.destroyForcibly()
        }
    }
}
