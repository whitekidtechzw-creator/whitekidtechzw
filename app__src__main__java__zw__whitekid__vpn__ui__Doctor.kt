package zw.whitekid.vpn.ui

import android.content.Context
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.vpn.HealthCheck
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zw.whitekid.vpn.data.NetInfo
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.vpn.DnsResolve
import zw.whitekid.vpn.vpn.Pinger
import java.io.File

/** Connection doctor: checks the phone's network, public DNS and the selected server, and says what is wrong in plain words. */
object Doctor {
    fun run(ctx: Context, p: Profile?): List<String> {
        val out = ArrayList<String>()
        if (NetInfo.online(ctx)) out.add("✅ Phone network is up (${NetInfo.localIp(ctx)})")
        else { out.add("❌ The phone has no Wi-Fi or mobile data. Turn one on and try again."); return out }

        val dns = DnsResolve.aliveList(listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "9.9.9.9"))
        if (dns.isEmpty()) out.add("❌ No public DNS answers on this network (tried 8.8.8.8, 8.8.4.4, 1.1.1.1, 9.9.9.9). Switch network or bundle.")
        else out.add("✅ DNS answering: ${dns.joinToString(", ")}")

        if (p == null) { out.add("ℹ️ No server is selected yet."); return out }
        out.add("ℹ️ Server: ${p.name} (${p.proto.label})")
        when (p.proto) {
            Proto.SLOWDNS -> {
                out.add(if (File(ctx.applicationInfo.nativeLibraryDir, "libdnstt.so").exists()) "✅ SlowDNS engine is in this build"
                else "❌ SlowDNS engine is missing from this build")
                out.add(if (p.publicKey.isBlank()) "❌ The server public key is missing" else "✅ Server public key is set")
                out.add(if (dns.contains("8.8.8.8") || dns.contains("8.8.4.4")) "✅ Google DNS is reachable for SlowDNS"
                else "⚠ Google DNS is blocked here. SlowDNS will rotate to another resolver by itself.")
            }
            Proto.WIREGUARD -> out.add("ℹ️ WireGuard uses UDP, so it can't be pre-tested. Just connect.")
            else -> {
                if (DnsResolve.resolve(p.host) == null) out.add("❌ The server name can't be resolved. The address may be wrong or its DNS record is gone.")
                else out.add("✅ Server name resolves")
                val ms = Pinger.tcp(p)
                out.add(if (ms >= 0) "✅ Server port is reachable ($ms ms)" else "❌ The server port is not reachable. The server may be down, or this network blocks it.")
            }
        }
        return out
    }
}

@Composable
fun DoctorDialog(p: Profile?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.IO) { runCatching { Doctor.run(ctx, p) }.getOrElse { listOf("❌ ${it.message}") } }
        busy = false
    }
    InfoDialog("Connection doctor", onClose) {
        if (busy) Text("Checking your phone, DNS and server…", color = WkMuted)
        lines.forEach { Text(it, fontSize = 13.sp) }
    }
}

/** Times a few requests to any website, through the VPN when it is connected. */
fun pingHost(p: Profile?, via: Boolean, host: String): String {
    if (host.isEmpty()) return "Type a website first."
    val times = ArrayList<Int>()
    var err = ""
    val url = "http://" + host.removePrefix("http://").removePrefix("https://")
    repeat(4) {
        val t = System.nanoTime()
        try {
            val c = if (via && p != null) HealthCheck.open(p, url, 8000)
            else (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; instanceFollowRedirects = false }
            try { c.responseCode } finally { c.disconnect() }
            times.add(((System.nanoTime() - t) / 1_000_000).toInt())
        } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
    }
    if (times.isEmpty()) return "No reply ($err)"
    return (if (via) "Through the VPN" else "Direct (VPN is off)") + ": ${times.size} of 4 replied\n" +
        "fastest ${times.minOrNull() ?: 0} • average ${times.sum() / times.size} • slowest ${times.maxOrNull() ?: 0} ms"
}

@Composable
fun PingToolDialog(p: Profile?, connected: Boolean, onClose: () -> Unit) {
    var host by remember { mutableStateOf("google.com") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfoDialog("Ping tool", onClose) {
        OutlinedTextField(host, { host = it }, singleLine = true, label = { Text("Website or host") })
        Button(onClick = {
            if (!busy) {
                busy = true; out = "Pinging…"
                scope.launch { out = withContext(Dispatchers.IO) { pingHost(p, connected, host.trim()) }; busy = false }
            }
        }) { Text(if (busy) "Working…" else "Ping") }
        if (out.isNotEmpty()) Text(out, fontSize = 13.sp)
    }
}

/** Text for the "Send support report" button. No passwords, keys or server names. */
fun buildReport(ctx: Context, p: Profile?): String {
    val sb = StringBuilder()
    sb.appendLine("Whitekid VPN ${appVersion(ctx)}")
    sb.appendLine("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    sb.appendLine("Network: ${NetInfo.localIp(ctx)} online=${NetInfo.online(ctx)}")
    sb.appendLine("Server type: ${p?.proto?.label ?: "none"}")
    sb.appendLine("DNS: ${Store.effectiveDns()} | DoH=${Store.doh} | adblock=${Store.adBlock} | ipv6block=${Store.blockIpv6} | mux=${Store.mux}")
    sb.appendLine("Split=${Store.splitEnabled}/${Store.splitOnly} | autoReconnect=${Store.autoReconnect} | failover=${Store.failover} | stayAlive=${Store.stayAlive}")
    sb.appendLine("--- last log lines ---")
    AppLog.lines.value.takeLast(80).forEach { sb.appendLine(it) }
    return sb.toString()
}
