package zw.whitekid.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Store
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.UUID

/** v1.9.0: ten extra tools, shown in Tools under "Whitekid Labs". */
private fun httpGet(url: String, timeout: Int = 8000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeout; c.readTimeout = timeout
    c.setRequestProperty("User-Agent", "WhitekidVPN")
    return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

private fun tcpMs(host: String, port: Int, timeout: Int = 4000): Int = try {
    val t = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
    ((System.nanoTime() - t) / 1_000_000).toInt()
} catch (_: Exception) { -1 }

private fun copyText(ctx: Context, label: String, text: String) {
    Store.lastClip = text.trim().hashCode()
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
fun LabsSection(profiles: MutableList<Profile>) {
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf("") }
    Section("Whitekid Labs") {
        listOf(
            "ipinfo" to "1. My IP and ISP info",
            "trace" to "2. Cloudflare trace",
            "sites" to "3. Website checker (WhatsApp, YouTube...)",
            "port" to "4. Port checker",
            "jitter" to "5. Jitter and packet loss test",
            "expiry" to "6. Expiry tracker",
            "gen" to "8. UUID and password generator"
        ).forEach { (k, l) -> OutlinedButton(onClick = { dlg = k }, Modifier.fillMaxWidth()) { Text(l) } }
        OutlinedButton(onClick = {
            val n = profiles.count { it.expired() }
            if (n == 0) Toast.makeText(ctx, "No expired servers.", Toast.LENGTH_SHORT).show()
            else { profiles.removeAll { it.expired() }; Store.saveProfiles(profiles); AppLog.log("Removed $n expired server(s)")
                Toast.makeText(ctx, "Removed $n expired server(s).", Toast.LENGTH_SHORT).show() }
        }, Modifier.fillMaxWidth()) { Text("7. Remove expired servers") }
        OutlinedButton(onClick = {
            val days = Store.lastDays(7)
            val txt = buildString {
                append("Whitekid VPN usage report\n")
                append("Today: ${bytes(Store.usedToday())}\nThis month: ${bytes(Store.usedMonth())}\n\nLast 7 days:\n")
                days.forEach { (d, b) -> append("$d  ${bytes(b)}\n") }
            }
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Share usage report"))
        }, Modifier.fillMaxWidth()) { Text("9. Share usage report") }
        OutlinedButton(onClick = {
            var freed = 0L
            runCatching { ctx.cacheDir.listFiles()?.forEach { f -> freed += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }; f.deleteRecursively() } }
            Toast.makeText(ctx, "Cache cleared (${bytes(freed)}).", Toast.LENGTH_SHORT).show()
        }, Modifier.fillMaxWidth()) { Text("10. Clear cache and temp files") }
        Text("Run the network tests while connected to see the result through your VPN.", color = WkMuted, fontSize = 12.sp)
    }

    when (dlg) {
        "ipinfo" -> IpInfoDialog { dlg = "" }
        "trace" -> TraceDialog { dlg = "" }
        "sites" -> SiteCheckDialog { dlg = "" }
        "port" -> PortCheckDialog { dlg = "" }
        "jitter" -> JitterDialog { dlg = "" }
        "expiry" -> ExpiryDialog(profiles) { dlg = "" }
        "gen" -> GeneratorDialog { dlg = "" }
    }
}

@Composable
private fun IpInfoDialog(onClose: () -> Unit) {
    var msg by remember { mutableStateOf("Looking up…") }
    LaunchedEffect(Unit) {
        msg = withContext(Dispatchers.IO) {
            runCatching {
                val o = JSONObject(httpGet("https://ipinfo.io/json"))
                "IP: ${o.optString("ip")}\nCity: ${o.optString("city")}, ${o.optString("region")}\nCountry: ${o.optString("country")}\n" +
                    "ISP: ${o.optString("org")}\nTime zone: ${o.optString("timezone")}"
            }.getOrElse { "Couldn't reach the lookup service (${it.message}). Check your internet." }
        }
    }
    InfoDialog("My IP and ISP info", onClose) { Text(msg) }
}

@Composable
private fun TraceDialog(onClose: () -> Unit) {
    var msg by remember { mutableStateOf("Tracing…") }
    LaunchedEffect(Unit) {
        msg = withContext(Dispatchers.IO) {
            runCatching {
                val m = httpGet("https://1.1.1.1/cdn-cgi/trace").lines().filter { it.contains("=") }
                    .associate { it.substringBefore("=") to it.substringAfter("=") }
                "IP: ${m["ip"]}\nCountry: ${m["loc"]}\nCloudflare datacenter: ${m["colo"]}\nWARP: ${m["warp"]}\nTLS: ${m["tls"]}\nHTTP: ${m["http"]}"
            }.getOrElse { "Trace failed (${it.message})." }
        }
    }
    InfoDialog("Cloudflare trace", onClose) { Text(msg) }
}

@Composable
private fun SiteCheckDialog(onClose: () -> Unit) {
    val sites = listOf("WhatsApp" to "https://www.whatsapp.com", "Telegram" to "https://telegram.org", "YouTube" to "https://www.youtube.com",
        "Facebook" to "https://www.facebook.com", "TikTok" to "https://www.tiktok.com", "Instagram" to "https://www.instagram.com",
        "Google" to "https://www.google.com", "Netflix" to "https://www.netflix.com")
    val res = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run() {
        if (busy) return
        busy = true; res.clear()
        scope.launch {
            sites.map { (n, u) -> async(Dispatchers.IO) {
                val t = System.currentTimeMillis()
                val r = try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                    val code = c.responseCode; c.disconnect()
                    "OK ($code) ${System.currentTimeMillis() - t} ms"
                } catch (_: Exception) { "BLOCKED or no internet" }
                res[n] = r
            } }.awaitAll()
            busy = false
        }
    }
    LaunchedEffect(Unit) { run() }
    InfoDialog("Website checker", onClose) {
        sites.forEach { (n, _) -> Text("$n: ${res[n] ?: "…"}", color = if (res[n]?.startsWith("BLOCKED") == true) WkRed else WkText) }
        TextButton(onClick = { run() }) { Text(if (busy) "Checking…" else "Check again") }
    }
}

@Composable
private fun PortCheckDialog(onClose: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfoDialog("Port checker", onClose) {
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Host or IP") })
        OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Port") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && host.isNotBlank(), onClick = {
                busy = true; out = "Testing…"
                scope.launch {
                    val p = port.toIntOrNull() ?: 443
                    val ms = withContext(Dispatchers.IO) { tcpMs(host, p) }
                    out = if (ms >= 0) "$host:$p is OPEN ($ms ms)" else "$host:$p is CLOSED or blocked"
                    busy = false
                }
            }) { Text("Test") }
            OutlinedButton(enabled = !busy && host.isNotBlank(), onClick = {
                busy = true; out = "Testing common ports…"
                scope.launch {
                    val ports = listOf(22, 53, 80, 443, 8080, 8443)
                    val r = withContext(Dispatchers.IO) { ports.map { p -> async { p to tcpMs(host, p, 3000) } }.awaitAll() }
                    out = r.joinToString("\n") { (p, ms) -> "Port $p: " + if (ms >= 0) "open ($ms ms)" else "closed" }
                    busy = false
                }
            }) { Text("Common ports") }
        }
        if (out.isNotEmpty()) Text(out, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun JitterDialog(onClose: () -> Unit) {
    var out by remember { mutableStateOf("Sending 15 probes…") }
    LaunchedEffect(Unit) {
        out = withContext(Dispatchers.IO) {
            val r = ArrayList<Int>(); var lost = 0
            repeat(15) { val ms = tcpMs("1.1.1.1", 443, 2500); if (ms >= 0) r.add(ms) else lost++; Thread.sleep(150) }
            if (r.isEmpty()) "All probes failed. No internet."
            else {
                val jit = if (r.size > 1) r.zipWithNext { a, b -> Math.abs(a - b) }.average() else 0.0
                "Min: ${r.min()} ms\nAvg: ${r.average().toInt()} ms\nMax: ${r.max()} ms\nJitter: ${"%.1f".format(jit)} ms\nPacket loss: ${lost * 100 / 15}%\n\n" +
                    when { lost > 3 || jit > 60 -> "Unstable connection."; r.average() < 120 -> "Excellent."; else -> "Good for browsing, slow for gaming." }
            }
        }
    }
    InfoDialog("Jitter and packet loss", onClose) { Text(out, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun ExpiryDialog(profiles: List<Profile>, onClose: () -> Unit) {
    InfoDialog("Expiry tracker", onClose) {
        if (profiles.isEmpty()) Text("No servers yet.", color = WkMuted)
        profiles.sortedBy { if (it.expires > 0) it.expires else Long.MAX_VALUE }.forEach { p ->
            val e = p.expiryText()
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(e ?: "No expiry", color = if (e == "Expired") WkRed else WkMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GeneratorDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var uuid by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    fun pw(): String { val r = SecureRandom(); return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("") }
    var pass by remember { mutableStateOf(pw()) }
    InfoDialog("UUID and password generator", onClose) {
        Text("UUID (VLESS / VMess)", color = WkMuted, fontSize = 12.sp)
        Text(uuid, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Row { TextButton(onClick = { uuid = UUID.randomUUID().toString() }) { Text("New") }; TextButton(onClick = { copyText(ctx, "UUID", uuid) }) { Text("Copy") } }
        Text("Strong password", color = WkMuted, fontSize = 12.sp)
        Text(pass, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        Row { TextButton(onClick = { pass = pw() }) { Text("New") }; TextButton(onClick = { copyText(ctx, "Password", pass) }) { Text("Copy") } }
    }
}
