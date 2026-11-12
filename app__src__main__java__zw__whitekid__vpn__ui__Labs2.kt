package zw.whitekid.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Clock
import zw.whitekid.vpn.data.LinkParser
import zw.whitekid.vpn.data.NetInfo
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.data.WkFile
import zw.whitekid.vpn.vpn.Pinger
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** v1.10.0: "Whitekid Labs II" - thirty more tools (11 to 40), shown in Tools under four headings. */

// ------------------------------------------------------------------ helpers
private fun l2Get(url: String, timeout: Int = 8000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = timeout; c.readTimeout = timeout
    c.setRequestProperty("User-Agent", "WhitekidVPN")
    return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
}

private fun l2Tcp(host: String, port: Int, timeout: Int = 4000): Int = try {
    val t = System.nanoTime()
    Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
    ((System.nanoTime() - t) / 1_000_000).toInt()
} catch (_: Exception) { -1 }

private fun l2Copy(ctx: Context, label: String, text: String) {
    Store.lastClip = text.trim().hashCode()
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun l2Share(ctx: Context, title: String, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
}

private fun l2Open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "This phone has no screen for that setting.", Toast.LENGTH_SHORT).show() }
}

private fun l2Digest(alg: String, s: String): String =
    MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

private fun l2Csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

private fun l2Down(bytes: Int = 5_000_000): Double {
    val t = System.nanoTime()
    val c = URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 15000
    var total = 0L
    try {
        c.inputStream.use { s ->
            val buf = ByteArray(32768)
            while (true) { val n = s.read(buf); if (n < 0) break; total += n }
        }
    } finally { c.disconnect() }
    val sec = (System.nanoTime() - t) / 1e9
    return total * 8 / 1e6 / sec
}

private fun l2Up(bytes: Int = 2_000_000): Double {
    val data = ByteArray(bytes)
    val c = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.connectTimeout = 8000; c.readTimeout = 20000
    c.setFixedLengthStreamingMode(bytes)
    c.setRequestProperty("Content-Type", "application/octet-stream")
    val t = System.nanoTime()
    try {
        c.outputStream.use { it.write(data) }
        c.responseCode
    } finally { c.disconnect() }
    val sec = (System.nanoTime() - t) / 1e9
    return bytes * 8 / 1e6 / sec
}

private fun l2HistAdd(down: Double, up: Double) {
    val arr = runCatching { JSONArray(Store.extraStr("speedhist", "[]")) }.getOrDefault(JSONArray())
    arr.put(JSONObject().put("t", System.currentTimeMillis()).put("d", down).put("u", up))
    val keep = JSONArray()
    for (i in maxOf(0, arr.length() - 20) until arr.length()) keep.put(arr.get(i))
    Store.setExtraStr("speedhist", keep.toString())
}

private fun l2MakePassword(len: Int, lower: Boolean, upper: Boolean, digits: Boolean, symbols: Boolean, clear: Boolean): String {
    val rnd = SecureRandom()
    fun strip(s: String) = if (clear) s.filter { it !in "O0oIl1|" } else s
    val sets = ArrayList<String>()
    if (lower) sets.add(strip("abcdefghijklmnopqrstuvwxyz"))
    if (upper) sets.add(strip("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
    if (digits) sets.add(strip("0123456789"))
    if (symbols) sets.add("!@#\$%^&*()-_=+[]{}?")
    if (sets.isEmpty()) sets.add(strip("abcdefghijklmnopqrstuvwxyz"))
    val all = sets.joinToString("")
    val out = ArrayList<Char>()
    sets.forEach { out.add(it[rnd.nextInt(it.length)]) }
    while (out.size < len) out.add(all[rnd.nextInt(all.length)])
    out.shuffle(rnd)
    return out.take(len).joinToString("")
}

private fun l2ExportServers(ctx: Context, profiles: List<Profile>) {
    if (profiles.isEmpty()) { Toast.makeText(ctx, "No servers yet.", Toast.LENGTH_SHORT).show(); return }
    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val txt = buildString {
        appendLine("name,protocol,host,port,network,security,group,expires")
        profiles.forEach { p ->
            appendLine(listOf(
                p.name, p.proto.label, if (p.locked) "(locked)" else p.host, if (p.locked) "" else p.port.toString(),
                p.network, p.security, p.groupName(), if (p.expires > 0) f.format(Date(p.expires)) else "never"
            ).joinToString(",") { l2Csv(it) })
        }
    }
    l2Share(ctx, "Share server list", txt)
}

private fun l2CopyBackup(ctx: Context, profiles: List<Profile>) {
    val usable = profiles.filter { !it.locked }
    if (usable.isEmpty()) Toast.makeText(ctx, "No servers to back up.", Toast.LENGTH_SHORT).show()
    else copyCode(ctx, WkFile.encode(usable, 0L, "Backup", false, null))
}

private fun l2ExportUsage(ctx: Context) {
    val rows = Store.lastDaysDated(30)
    val txt = "date,bytes,MB\n" + rows.joinToString("\n") { (d, b) -> "$d,$b," + "%.1f".format(b / 1048576.0) }
    l2Share(ctx, "Share usage CSV", txt)
}

private class L2Check(val ok: Boolean, val title: String, val hint: String, val fix: (() -> Unit)? = null)

// ------------------------------------------------------------------ the Tools section
@Composable
private fun L2Buttons(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    items.forEach { (k, l) -> OutlinedButton(onClick = { onPick(k) }, Modifier.fillMaxWidth()) { Text(l) } }
}

@Composable
fun Labs2Section(profiles: MutableList<Profile>) {
    val ctx = LocalContext.current
    var dlg by remember { mutableStateOf("") }
    var amoled by remember { mutableStateOf(Store.amoled) }
    val pick: (String) -> Unit = { k ->
        when (k) {
            "csv" -> l2ExportServers(ctx, profiles)
            "backup" -> l2CopyBackup(ctx, profiles)
            "usagecsv" -> l2ExportUsage(ctx)
            else -> { dlg = k }
        }
    }

    Section("Labs II · Network") {
        L2Buttons(listOf(
            "net" to "11. Network details",
            "dns" to "12. DNS lookup",
            "iplookup" to "13. IP lookup (any IP)",
            "latency" to "14. Latency to popular networks",
            "speed" to "15. Upload and download speed test",
            "speedhist" to "16. Speed test history",
            "clock" to "17. Clock check",
            "watch" to "18. Ping watchlist",
            "privdns" to "19. Private DNS check"
        ), pick)
        Text("Run the network tests while connected to see the result through your VPN.", color = WkMuted, fontSize = 12.sp)
    }

    Section("Labs II · Servers") {
        L2Buttons(listOf(
            "rank" to "20. Rank servers by ping",
            "reliab" to "21. Reliability scoreboard",
            "dupes" to "22. Find and remove duplicates",
            "delgroup" to "23. Delete a whole group",
            "inventory" to "24. Server inventory",
            "csv" to "25. Share server list (CSV)",
            "backup" to "26. Copy backup code",
            "reset" to "27. Reset data counters",
            "reorder" to "28. Reorder the list"
        ), pick)
    }

    Section("Labs II · Data") {
        L2Buttons(listOf(
            "report30" to "29. 30-day usage report",
            "budget" to "30. Monthly data budget",
            "cost" to "31. Data cost calculator",
            "usagecsv" to "32. Share usage as CSV"
        ), pick)
    }

    Section("Labs II · Setup and utilities") {
        L2Buttons(listOf(
            "health" to "33. Setup health check",
            "shortcuts" to "34. Android settings shortcuts",
            "b64" to "35. Base64 encode and decode",
            "hash" to "36. Hash generator (SHA-256, SHA-1, MD5)",
            "pass" to "37. Password generator Pro",
            "inspect" to "38. Link inspector",
            "appqr" to "39. Share the app (QR)"
        ), pick)
        ToggleRow("40. AMOLED pure black", "True black background in dark mode. Saves battery on AMOLED screens", amoled) {
            amoled = it; Store.amoled = it; amoledOn = it
        }
    }

    when (dlg) {
        "net" -> L2NetDialog { dlg = "" }
        "dns" -> L2DnsDialog { dlg = "" }
        "iplookup" -> L2IpLookupDialog { dlg = "" }
        "latency" -> L2LatencyDialog { dlg = "" }
        "speed" -> L2SpeedDialog { dlg = "" }
        "speedhist" -> L2SpeedHistoryDialog { dlg = "" }
        "clock" -> L2ClockDialog { dlg = "" }
        "watch" -> L2WatchDialog { dlg = "" }
        "privdns" -> L2PrivateDnsDialog { dlg = "" }
        "rank" -> L2RankDialog(profiles) { dlg = "" }
        "reliab" -> L2ReliabilityDialog(profiles) { dlg = "" }
        "dupes" -> L2DuplicatesDialog(profiles) { dlg = "" }
        "delgroup" -> L2DeleteGroupDialog(profiles) { dlg = "" }
        "inventory" -> L2InventoryDialog(profiles) { dlg = "" }
        "reset" -> L2ResetCountersDialog(profiles) { dlg = "" }
        "reorder" -> L2ReorderDialog(profiles) { dlg = "" }
        "report30" -> L2Report30Dialog { dlg = "" }
        "budget" -> L2BudgetDialog { dlg = "" }
        "cost" -> L2CostDialog { dlg = "" }
        "health" -> L2HealthDialog(profiles) { dlg = "" }
        "shortcuts" -> L2ShortcutsDialog { dlg = "" }
        "b64" -> L2Base64Dialog { dlg = "" }
        "hash" -> L2HashDialog { dlg = "" }
        "pass" -> L2PasswordDialog { dlg = "" }
        "inspect" -> L2LinkInspectorDialog { dlg = "" }
        "appqr" -> L2AppQrDialog { dlg = "" }
    }
}

// ------------------------------------------------------------------ 11 to 19: network
@Composable
private fun L2NetDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val n = NetInfo.realNetwork(ctx)
            if (n == null) "No real network right now (offline)."
            else {
                val lp = cm.getLinkProperties(n)
                val caps = cm.getNetworkCapabilities(n)
                val kind = when {
                    caps == null -> "Unknown"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Other"
                }
                val active = cm.getNetworkCapabilities(cm.activeNetwork)
                buildString {
                    appendLine("Type: $kind")
                    appendLine("VPN active: " + if (active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) "yes" else "no")
                    appendLine("Metered: " + if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) "no" else "yes")
                    appendLine("Interface: " + (lp?.interfaceName ?: "-"))
                    lp?.linkAddresses?.forEach { appendLine("Address: ${it.address.hostAddress}/${it.prefixLength}") }
                    appendLine("DNS: " + (lp?.dnsServers?.joinToString { it.hostAddress ?: "" } ?: "-"))
                    if (Build.VERSION.SDK_INT >= 29 && lp != null && lp.mtu > 0) appendLine("MTU: ${lp.mtu}")
                    if (Build.VERSION.SDK_INT >= 28 && lp != null) {
                        appendLine("Private DNS: " + if (lp.isPrivateDnsActive) "on (${lp.privateDnsServerName ?: "automatic"})" else "off")
                    }
                    if (caps != null) appendLine("Link speed (Android estimate): down ${caps.linkDownstreamBandwidthKbps / 1000} Mbps, up ${caps.linkUpstreamBandwidthKbps / 1000} Mbps")
                }
            }
        }.getOrElse { "Couldn't read the network (${it.message})." }
    }
    InfoDialog("Network details", onClose) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
}

@Composable
private fun L2DnsDialog(onClose: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfoDialog("DNS lookup", onClose) {
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Domain, e.g. whatsapp.com") })
        Button(enabled = !busy && host.isNotBlank(), onClick = {
            busy = true; out = "Looking up…"
            scope.launch {
                out = withContext(Dispatchers.IO) {
                    val t = System.currentTimeMillis()
                    try {
                        val all = InetAddress.getAllByName(host)
                        val ms = System.currentTimeMillis() - t
                        "Resolved in $ms ms\n" + all.joinToString("\n") { (if (it is Inet6Address) "IPv6  " else "IPv4  ") + it.hostAddress }
                    } catch (e: Exception) { "Could not resolve $host.\nThe name may not exist, or DNS is blocked." }
                }
                busy = false
            }
        }) { Text(if (busy) "Looking up…" else "Look up") }
        if (out.isNotEmpty()) Text(out, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun L2IpLookupDialog(onClose: () -> Unit) {
    var ip by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfoDialog("IP lookup", onClose) {
        OutlinedTextField(ip, { ip = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("IP address (empty = my IP)") })
        Button(enabled = !busy, onClick = {
            busy = true; out = "Looking up…"
            scope.launch {
                out = withContext(Dispatchers.IO) {
                    runCatching {
                        val q = ip.filter { c -> c.isLetterOrDigit() || c == '.' || c == ':' }
                        val o = JSONObject(l2Get("https://ipinfo.io/" + (if (q.isEmpty()) "" else "$q/") + "json"))
                        if (o.has("bogon")) "That is a private or reserved address."
                        else "IP: ${o.optString("ip")}\nHost: ${o.optString("hostname", "-")}\nCity: ${o.optString("city")}, ${o.optString("region")}\n" +
                            "Country: ${o.optString("country")}\nNetwork: ${o.optString("org")}\nTime zone: ${o.optString("timezone")}"
                    }.getOrElse { "Lookup failed (${it.message})." }
                }
                busy = false
            }
        }) { Text(if (busy) "Looking up…" else "Look up") }
        if (out.isNotEmpty()) Text(out, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun L2LatencyDialog(onClose: () -> Unit) {
    val targets = listOf("Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8", "Quad9" to "9.9.9.9", "Facebook" to "www.facebook.com",
        "WhatsApp" to "www.whatsapp.com", "YouTube" to "www.youtube.com", "TikTok" to "www.tiktok.com", "Telegram" to "telegram.org",
        "Microsoft" to "www.microsoft.com", "Amazon" to "www.amazon.com")
    var res by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun run() {
        busy = true
        scope.launch {
            res = withContext(Dispatchers.IO) {
                targets.map { (n, h) -> async { n to l2Tcp(h, 443, 3500) } }.awaitAll()
                    .sortedBy { if (it.second < 0) Int.MAX_VALUE else it.second }
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { run() }
    InfoDialog("Latency to popular networks", onClose) {
        if (busy) Text("Testing…")
        res.forEach { (n, ms) ->
            val bar = if (ms >= 0) "█".repeat((ms / 25).coerceIn(1, 14)) else ""
            Text(n.padEnd(10) + (if (ms >= 0) "$ms ms " else "blocked ") + bar, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                color = if (ms < 0) WkRed else WkText)
        }
        TextButton(onClick = { if (!busy) run() }) { Text("Test again") }
    }
}

@Composable
private fun L2SpeedDialog(onClose: () -> Unit) {
    var out by remember { mutableStateOf("Uses about 7 MB of data (5 MB down, 2 MB up).\nRun it while connected to test your VPN.") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    InfoDialog("Speed test", onClose) {
        Text(out, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Button(enabled = !busy, onClick = {
            busy = true; out = "Testing download…"
            scope.launch {
                val d = withContext(Dispatchers.IO) { runCatching { l2Down() }.getOrNull() }
                val dTxt = if (d != null) "%.1f Mbps".format(d) else "failed"
                out = "Download: $dTxt\nTesting upload…"
                val u = withContext(Dispatchers.IO) { runCatching { l2Up() }.getOrNull() }
                val uTxt = if (u != null) "%.1f Mbps".format(u) else "failed"
                out = "Download: $dTxt\nUpload:   $uTxt"
                if (d != null && u != null) l2HistAdd(d, u)
                busy = false
            }
        }) { Text(if (busy) "Testing…" else "Start test") }
    }
}

@Composable
private fun L2SpeedHistoryDialog(onClose: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val rows = remember(tick) {
        val arr = runCatching { JSONArray(Store.extraStr("speedhist", "[]")) }.getOrDefault(JSONArray())
        (0 until arr.length()).map { arr.getJSONObject(it) }.reversed()
    }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.US) }
    InfoDialog("Speed test history", onClose) {
        if (rows.isEmpty()) Text("No results yet. Run the speed test (15) first.", color = WkMuted)
        else {
            val bestD = rows.maxOf { it.optDouble("d") }
            val bestU = rows.maxOf { it.optDouble("u") }
            val avgD = rows.map { it.optDouble("d") }.average()
            val avgU = rows.map { it.optDouble("u") }.average()
            Text("Best:    down %.1f  up %.1f Mbps".format(bestD, bestU), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Average: down %.1f  up %.1f Mbps".format(avgD, avgU), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            rows.forEach {
                val line = fmt.format(Date(it.optLong("t"))) + "  down " + "%.1f".format(it.optDouble("d")) + "  up " + "%.1f".format(it.optDouble("u"))
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = WkMuted)
            }
            TextButton(onClick = { Store.setExtraStr("speedhist", "[]"); tick++ }) { Text("Clear history") }
        }
    }
}

@Composable
private fun L2ClockDialog(onClose: () -> Unit) {
    var out by remember { mutableStateOf("Checking the time…") }
    LaunchedEffect(Unit) {
        out = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://www.google.com").openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 6000; c.readTimeout = 6000
                val before = System.currentTimeMillis()
                c.connect()
                val server = c.date
                c.disconnect()
                if (server == 0L) "The server did not send a time."
                else {
                    val diff = (before - server) / 1000
                    val f = SimpleDateFormat("EEE dd MMM yyyy HH:mm:ss", Locale.US)
                    val rolled = (Clock.now() - System.currentTimeMillis()) / 60000
                    "Phone time:  ${f.format(Date(before))}\nReal time:   ${f.format(Date(server))}\nDifference:  $diff s\n\n" +
                        (if (Math.abs(diff) < 60) "Your clock is accurate."
                        else "Your clock is off. Expiring files, TLS and Reality can fail. Turn on automatic date and time in Android settings.") +
                        (if (rolled > 2) "\n\nThe app noticed the clock was wound back by about $rolled min. Expiry checks use the latest time it has seen." else "")
                }
            }.getOrElse { "Couldn't check the time (${it.message})." }
        }
    }
    InfoDialog("Clock check", onClose) { Text(out, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
}

@Composable
private fun L2WatchDialog(onClose: () -> Unit) {
    var hosts by remember { mutableStateOf(Store.extraStr("watch").lines().filter { it.isNotBlank() }) }
    var add by remember { mutableStateOf("") }
    val res = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(l: List<String>) { hosts = l; Store.setExtraStr("watch", l.joinToString("\n")) }
    InfoDialog("Ping watchlist", onClose) {
        OutlinedTextField(add, { add = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("host or host:port (default port 443)") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = add.isNotBlank() && hosts.size < 12, onClick = { if (add !in hosts) save(hosts + add); add = "" }) { Text("Add") }
            OutlinedButton(enabled = !busy && hosts.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        hosts.map { h -> async {
                            val host = h.substringBefore(":")
                            val port = h.substringAfter(":", "443").toIntOrNull() ?: 443
                            val ms = l2Tcp(host, port, 3500)
                            res[h] = if (ms >= 0) "$ms ms" else "closed"
                        } }.awaitAll()
                    }
                    busy = false
                }
            }) { Text(if (busy) "Testing…" else "Test all") }
        }
        if (hosts.isEmpty()) Text("Add the sites or servers you care about. They are saved on this phone.", color = WkMuted, fontSize = 12.sp)
        hosts.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
                Text(res[h] ?: "-", color = if (res[h] == "closed") WkRed else WkMuted, fontSize = 12.sp)
                TextButton(onClick = { save(hosts - h); res.remove(h) }) { Text("✕") }
            }
        }
    }
}

@Composable
private fun L2PrivateDnsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val mode = remember { runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull() ?: "unknown" }
    val spec = remember { runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") }.getOrNull().orEmpty() }
    InfoDialog("Private DNS check", onClose) {
        Text("Android Private DNS: " + when (mode) { "off" -> "OFF"; "opportunistic" -> "Automatic"; "hostname" -> "ON ($spec)"; else -> "unknown" },
            fontWeight = FontWeight.SemiBold)
        Text(
            if (mode == "hostname") "A fixed Private DNS host is set. If sites stop loading while connected, set Private DNS to Off or Automatic."
            else "Looks fine. If sites ever stop loading while connected, check this setting first.",
            color = WkMuted, fontSize = 13.sp)
        OutlinedButton(onClick = { l2Open(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Open network settings") }
        Text("Private DNS is under Network and internet, then Advanced.", color = WkMuted, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ 20 to 28: servers
@Composable
private fun L2RankDialog(profiles: List<Profile>, onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<Triple<String, Int, Int?>>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            profiles.map { p -> async { Triple(p.name, Pinger.tcp(p), Store.reliability(p.id)) } }.awaitAll()
                .sortedBy { when { it.second >= 0 -> it.second; it.second == -2 -> Int.MAX_VALUE - 1; else -> Int.MAX_VALUE } }
        }
        busy = false
    }
    InfoDialog("Server ranking by ping", onClose) {
        if (profiles.isEmpty()) Text("No servers yet.", color = WkMuted)
        else if (busy) Text("Pinging ${profiles.size} server(s)…")
        else {
            rows.firstOrNull { it.second >= 0 }?.let { Text("Fastest: ${it.first} (${it.second} ms)", color = WkOk, fontWeight = FontWeight.SemiBold) }
            rows.forEachIndexed { i, (n, ms, rel) ->
                Text("${i + 1}. $n", fontWeight = FontWeight.SemiBold, maxLines = 1)
                val state = when (ms) { -2 -> "UDP / DNS based, can't be pinged"; -1 -> "unreachable"; else -> "$ms ms" }
                Text(state + (rel?.let { "  •  worked $it% of the time" } ?: ""), color = if (ms == -1) WkRed else WkMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun L2ReliabilityDialog(profiles: List<Profile>, onClose: () -> Unit) {
    val rows = remember { profiles.map { it to Store.reliability(it.id) }.sortedByDescending { it.second ?: -1 } }
    InfoDialog("Reliability scoreboard", onClose) {
        if (rows.isEmpty()) Text("No servers yet.", color = WkMuted)
        rows.forEach { (p, rel) ->
            Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (rel == null) Text("Not enough data yet (needs 3 connection attempts)", color = WkMuted, fontSize = 12.sp)
            else Text("$rel% of connection attempts worked", fontSize = 12.sp,
                color = if (rel >= 80) WkOk else if (rel < 50) WkRed else WkViolet)
        }
    }
}

@Composable
private fun L2DuplicatesDialog(profiles: MutableList<Profile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val extra = remember(tick) {
        val seen = HashMap<String, Profile>()
        val dupes = ArrayList<Pair<Profile, Profile>>()
        profiles.forEach { p ->
            val k = listOf(p.proto.name, p.host.lowercase(), p.port.toString(), p.user, p.secret, p.network, p.sni, p.path, p.raw.trim()).joinToString("|")
            val first = seen[k]
            if (first == null) seen[k] = p else dupes.add(p to first)
        }
        dupes.toList()
    }
    InfoDialog("Duplicate servers", onClose) {
        if (extra.isEmpty()) Text("No duplicate servers found.")
        else {
            Text("${extra.size} duplicate(s) found. The first copy of each is kept.", color = WkMuted, fontSize = 13.sp)
            extra.forEach { Text("• ${it.first.name}  (same as ${it.second.name})", fontSize = 13.sp) }
            Button(onClick = {
                val ids = extra.map { it.first.id }.toSet()
                val sel = Store.selectedId
                extra.firstOrNull { it.first.id == sel }?.let { Store.selectedId = it.second.id }
                profiles.removeAll { it.id in ids }
                Store.saveProfiles(profiles)
                AppLog.log("Removed ${ids.size} duplicate server(s)")
                Toast.makeText(ctx, "Removed ${ids.size} duplicate(s).", Toast.LENGTH_SHORT).show()
                tick++
            }) { Text("Remove ${extra.size} duplicate(s)") }
        }
    }
}

@Composable
private fun L2DeleteGroupDialog(profiles: MutableList<Profile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf("") }
    val groups = remember(tick) { profiles.groupBy { it.groupName() }.map { it.key to it.value.size }.sortedBy { it.first.lowercase() } }
    InfoDialog("Delete a group of servers", onClose) {
        if (groups.isEmpty()) Text("No servers yet.", color = WkMuted)
        groups.forEach { (g, n) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("$n server(s)", color = WkMuted, fontSize = 12.sp)
                }
                if (confirm == g) {
                    Button(onClick = {
                        val gone = profiles.filter { it.groupName() == g }
                        profiles.removeAll { it.groupName() == g }
                        Store.saveProfiles(profiles)
                        AppLog.log("Deleted group $g (${gone.size} servers)")
                        if (gone.any { it.id == Store.selectedId }) Store.selectedId = profiles.firstOrNull()?.id ?: ""
                        Toast.makeText(ctx, "Deleted ${gone.size} server(s).", Toast.LENGTH_SHORT).show()
                        confirm = ""; tick++
                    }, colors = ButtonDefaults.buttonColors(containerColor = WkRed)) { Text("Sure?") }
                } else TextButton(onClick = { confirm = g }) { Text("Delete", color = WkRed) }
            }
        }
        Text("Deleting cannot be undone. Use Back up my servers first if unsure.", color = WkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun L2InventoryDialog(profiles: List<Profile>, onClose: () -> Unit) {
    InfoDialog("Server inventory", onClose) {
        Text("Total: ${profiles.size}", fontWeight = FontWeight.Bold)
        Proto.values().forEach { pr ->
            val n = profiles.count { it.proto == pr }
            if (n > 0) Text("${pr.label}: $n")
        }
        val now = Clock.now()
        Text("")
        Text("Favourites: ${profiles.count { it.id in Store.favorites }}")
        Text("Locked: ${profiles.count { it.locked }}")
        Text("With a data limit: ${profiles.count { it.dataLimitMb > 0 }}")
        Text("With an expiry: ${profiles.count { it.expires > 0 }}")
        Text("Expiring in 3 days: ${profiles.count { it.expires > 0 && (it.expires - now) in 1L..259_200_000L }}")
        Text("Expired: ${profiles.count { it.expired() }}", color = if (profiles.any { it.expired() }) WkRed else WkText)
        Text("Data used through servers: ${bytes(profiles.sumOf { Store.usedBytes(it.id) })}")
        Text("")
        Text("Groups", color = WkMuted, fontSize = 12.sp)
        profiles.groupBy { it.groupName() }.forEach { (g, l) -> Text("$g: ${l.size}") }
    }
}

@Composable
private fun L2ResetCountersDialog(profiles: List<Profile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val limited = profiles.count { it.dataLimitMb > 0 }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Reset data counters?") },
        text = {
            Text("Sets the used-data counter of your servers back to zero. Your usage history stays." +
                if (limited > 0) "\n\n$limited server(s) have a data limit set by their creator. Those counters are not reset." else "")
        },
        confirmButton = {
            Button(onClick = {
                profiles.filter { it.dataLimitMb == 0L }.forEach { Store.resetUsed(it.id) }
                AppLog.log("Data counters reset")
                Toast.makeText(ctx, "Counters reset.", Toast.LENGTH_SHORT).show()
                onClose()
            }) { Text("Reset") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
private fun L2ReorderDialog(profiles: MutableList<Profile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    fun reorder(how: String) {
        val sorted = when (how) {
            "name" -> profiles.sortedBy { it.name.lowercase() }
            "expiry" -> profiles.sortedBy { if (it.expires > 0) it.expires else Long.MAX_VALUE }
            else -> profiles.sortedBy { it.proto.label }
        }
        profiles.clear(); profiles.addAll(sorted); Store.saveProfiles(profiles)
        Toast.makeText(ctx, "List reordered.", Toast.LENGTH_SHORT).show()
        onClose()
    }
    InfoDialog("Reorder the list", onClose) {
        Text("Changes the order used by Sort: Added on the Servers screen.", color = WkMuted, fontSize = 13.sp)
        OutlinedButton(onClick = { reorder("name") }, Modifier.fillMaxWidth()) { Text("A to Z") }
        OutlinedButton(onClick = { reorder("expiry") }, Modifier.fillMaxWidth()) { Text("Soonest expiry first") }
        OutlinedButton(onClick = { reorder("proto") }, Modifier.fillMaxWidth()) { Text("By protocol") }
    }
}

// ------------------------------------------------------------------ 29 to 32: data
@Composable
private fun L2Report30Dialog(onClose: () -> Unit) {
    val days = remember { Store.lastDaysDated(30) }
    val total = days.sumOf { it.second }
    val active = days.count { it.second > 0 }
    val peak = days.maxByOrNull { it.second }
    val top = (peak?.second ?: 0L).coerceAtLeast(1L)
    val blocks = "▁▂▃▄▅▆▇█"
    val spark = days.joinToString("") { (_, b) -> if (b <= 0) "·" else blocks[((b * 7) / top).toInt().coerceIn(0, 7)].toString() }
    var streak = 0
    for (d in days.reversed()) { if (d.second > 0) streak++ else break }
    InfoDialog("30-day usage report", onClose) {
        Text("Total: ${bytes(total)}", fontWeight = FontWeight.Bold)
        Text("Active days: $active of 30")
        Text("Average per active day: ${bytes(if (active > 0) total / active else 0L)}")
        if (peak != null && peak.second > 0) Text("Busiest day: ${peak.first} (${bytes(peak.second)})")
        Text("Current streak: $streak day(s)")
        Text(spark, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("oldest → today", color = WkMuted, fontSize = 11.sp)
        Text("Top days", color = WkMuted, fontSize = 12.sp)
        days.sortedByDescending { it.second }.take(5).filter { it.second > 0 }.forEach {
            Text("${it.first}   ${bytes(it.second)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Text("Counted while the VPN is connected.", color = WkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun L2BudgetDialog(onClose: () -> Unit) {
    var budget by remember { mutableLongStateOf(Store.extraLong("budgetMb")) }
    var custom by remember { mutableStateOf("") }
    val used = Store.usedMonth()
    val cal = Calendar.getInstance()
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val dim = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val left = dim - day + 1
    fun setBudget(mb: Long) { budget = mb; Store.setExtraLong("budgetMb", mb) }
    InfoDialog("Monthly data budget", onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0L to "Off", 1024L to "1 GB", 2048L to "2 GB", 5120L to "5 GB", 10240L to "10 GB", 20480L to "20 GB").forEach { (m, l) ->
                FilterChip(selected = budget == m, onClick = { setBudget(m) }, label = { Text(l) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(custom, { custom = it.filter { c -> c.isDigit() }.take(7) }, Modifier.weight(1f), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Custom (MB)") })
            Button(enabled = custom.isNotBlank(), onClick = { setBudget(custom.toLongOrNull() ?: 0L); custom = "" }) { Text("Set") }
        }
        if (budget <= 0L) Text("Pick a monthly amount to see your progress, a forecast and a safe daily allowance.", color = WkMuted, fontSize = 13.sp)
        else {
            val bBytes = budget * 1048576L
            val frac = (used.toFloat() / bBytes.toFloat()).coerceIn(0f, 1f)
            val forecast = used * dim / day
            val remaining = bBytes - used
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(),
                color = if (frac > 0.9f) WkRed else if (frac > 0.7f) WkViolet else WkMint, trackColor = WkLine)
            Text("Used ${bytes(used)} of ${bytes(bBytes)} (${(frac * 100).toInt()}%)", fontWeight = FontWeight.SemiBold)
            Text("Daily average so far: ${bytes(used / day)}")
            Text("Forecast for the month end: ${bytes(forecast)}", color = if (forecast > bBytes) WkRed else WkText)
            if (remaining > 0) Text("Safe daily allowance for the next $left day(s): ${bytes(remaining / left)}")
            else Text("The budget is used up.", color = WkRed)
        }
        Text("Counts only traffic that went through the VPN.", color = WkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun L2CostDialog(onClose: () -> Unit) {
    var price by remember { mutableStateOf(Store.extraStr("bundlePrice")) }
    var gb by remember { mutableStateOf(Store.extraStr("bundleGb")) }
    var cur by remember { mutableStateOf(Store.extraStr("currency", "USD")) }
    val p = price.replace(',', '.').toDoubleOrNull()
    val g = gb.replace(',', '.').toDoubleOrNull()
    val perGb = if (p != null && g != null && g > 0) p / g else null
    InfoDialog("Data cost calculator", onClose) {
        OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == '.' || c == ',' }; Store.setExtraStr("bundlePrice", price) },
            Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("Bundle price") })
        OutlinedTextField(gb, { gb = it.filter { c -> c.isDigit() || c == '.' || c == ',' }; Store.setExtraStr("bundleGb", gb) },
            Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), label = { Text("Bundle size (GB)") })
        OutlinedTextField(cur, { cur = it.take(4); Store.setExtraStr("currency", cur) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Currency") })
        if (perGb == null) Text("Enter what you pay and how many GB you get.", color = WkMuted, fontSize = 13.sp)
        else {
            val today = Store.usedToday() / 1073741824.0
            val month = Store.usedMonth() / 1073741824.0
            Text("1 GB costs ${"%.2f".format(perGb)} $cur", fontWeight = FontWeight.Bold)
            Text("Today: ${bytes(Store.usedToday())} ≈ ${"%.2f".format(perGb * today)} $cur")
            Text("This month: ${bytes(Store.usedMonth())} ≈ ${"%.2f".format(perGb * month)} $cur")
            Text("100 MB costs about ${"%.3f".format(perGb / 10.24)} $cur", color = WkMuted)
        }
        Text("Based on VPN traffic only. Your provider's own meter may differ.", color = WkMuted, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ 33 to 39: setup and utilities
@Composable
private fun L2HealthDialog(profiles: List<Profile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val checks = remember(tick) {
        val list = ArrayList<L2Check>()
        val pm = ctx.getSystemService(PowerManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        list.add(L2Check(runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false), "VPN permission",
            "Android asks for it the first time you connect. Tap Connect on Home and allow it."))
        list.add(L2Check(NetInfo.online(ctx), "Internet", "The phone has no Wi-Fi or mobile data right now."))
        list.add(L2Check(profiles.any { !it.expired() }, "A usable server", "You have no servers, or all of them expired. Add one in Servers."))
        list.add(L2Check(profiles.any { it.id == Store.selectedId && !it.expired() }, "A selected server", "Pick a server in the Servers tab."))
        val batt = runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
        list.add(L2Check(batt, "Allowed to run in the background", "Battery saver can freeze the tunnel. Allow background running.") {
            l2Open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        })
        list.add(L2Check(runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(true), "Notifications on",
            "The live connection notification is hidden. Turn notifications on.") {
            l2Open(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        })
        val saver = runCatching { cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED }.getOrDefault(false)
        list.add(L2Check(!saver, "Data Saver does not limit the app", "Android Data Saver can block background data. Allow unrestricted data for this app.") {
            l2Open(ctx, Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        })
        val mode = runCatching { Settings.Global.getString(ctx.contentResolver, "private_dns_mode") }.getOrNull()
        list.add(L2Check(mode != "hostname", "Private DNS", "A fixed Private DNS host can break lookups while connected. Set it to Off or Automatic.") {
            l2Open(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS))
        })
        list
    }
    InfoDialog("Setup health check", onClose) {
        Text("${checks.count { it.ok }} of ${checks.size} checks passed", fontWeight = FontWeight.Bold,
            color = if (checks.all { it.ok }) WkOk else WkViolet)
        checks.forEach { c ->
            Row(verticalAlignment = Alignment.Top) {
                Text(if (c.ok) "✓" else "✗", color = if (c.ok) WkOk else WkRed, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.SemiBold)
                    if (!c.ok) Text(c.hint, color = WkMuted, fontSize = 12.sp)
                }
                if (!c.ok) c.fix?.let { f -> TextButton(onClick = { f() }) { Text("Fix") } }
            }
        }
        TextButton(onClick = { tick++ }) { Text("Check again") }
    }
}

@Composable
private fun L2ShortcutsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val items = listOf(
        "Android VPN settings (Always-on, block without VPN)" to Intent(Settings.ACTION_VPN_SETTINGS),
        "Mobile data usage" to Intent("android.settings.DATA_USAGE_SETTINGS"),
        "Network and Internet" to Intent(Settings.ACTION_WIRELESS_SETTINGS),
        "Wi-Fi" to Intent(Settings.ACTION_WIFI_SETTINGS),
        "Battery optimisation list" to Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        "Date and time (automatic clock)" to Intent(Settings.ACTION_DATE_SETTINGS),
        "Whitekid VPN app info" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        "Notification settings" to Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    )
    InfoDialog("Android settings shortcuts", onClose) {
        items.forEach { (l, i) -> OutlinedButton(onClick = { l2Open(ctx, i) }, Modifier.fillMaxWidth()) { Text(l, fontSize = 13.sp) } }
    }
}

@Composable
private fun L2Base64Dialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    InfoDialog("Base64 encode and decode", onClose) {
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6, label = { Text("Text") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = input.isNotEmpty(), onClick = { out = Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP) }) { Text("Encode") }
            OutlinedButton(enabled = input.isNotBlank(), onClick = {
                out = runCatching { Base64.decode(input.trim().replace('-', '+').replace('_', '/'), Base64.DEFAULT).decodeToString() }
                    .getOrElse { "That is not valid Base64." }
            }) { Text("Decode") }
        }
        if (out.isNotEmpty()) {
            Text(out, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            TextButton(onClick = { l2Copy(ctx, "Base64", out) }) { Text("Copy result") }
        }
    }
}

@Composable
private fun L2HashDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    InfoDialog("Hash generator", onClose) {
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Text or password") })
        if (input.isNotEmpty()) {
            val s256 = l2Digest("SHA-256", input)
            Text("SHA-256", color = WkMuted, fontSize = 12.sp)
            Text(s256, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            TextButton(onClick = { l2Copy(ctx, "SHA-256", s256) }) { Text("Copy SHA-256") }
            Text("SHA-1", color = WkMuted, fontSize = 12.sp)
            Text(l2Digest("SHA-1", input), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text("MD5", color = WkMuted, fontSize = 12.sp)
            Text(l2Digest("MD5", input), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Text("A password-protected server file stores the SHA-256 of its password.", color = WkMuted, fontSize = 12.sp)
    }
}

@Composable
private fun L2PasswordDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var len by remember { mutableFloatStateOf(20f) }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var clear by remember { mutableStateOf(true) }
    var pass by remember { mutableStateOf(l2MakePassword(20, true, true, true, true, true)) }
    val n = len.toInt()
    val pool = (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (digits) 10 else 0) + (if (symbols) 20 else 0)
    val bits = if (pool > 0) n * Math.log(pool.toDouble()) / Math.log(2.0) else 0.0
    val label = when { bits < 50 -> "weak"; bits < 70 -> "fair"; bits < 100 -> "strong"; else -> "excellent" }
    InfoDialog("Password generator Pro", onClose) {
        Text(pass, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
        Text("Strength: $label (about ${bits.toInt()} bits)", color = if (bits < 50) WkRed else WkMuted, fontSize = 12.sp)
        Text("Length: $n", color = WkMuted, fontSize = 12.sp)
        Slider(value = len, onValueChange = { len = it.toInt().toFloat() }, valueRange = 8f..64f)
        ToggleRow("Lowercase", "", lower) { lower = it }
        ToggleRow("Uppercase", "", upper) { upper = it }
        ToggleRow("Numbers", "", digits) { digits = it }
        ToggleRow("Symbols", "", symbols) { symbols = it }
        ToggleRow("Skip look-alike characters", "No O, 0, l, 1, I", clear) { clear = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pass = l2MakePassword(n, lower, upper, digits, symbols, clear) }) { Text("Generate") }
            OutlinedButton(onClick = { l2Copy(ctx, "Password", pass) }) { Text("Copy") }
        }
    }
}

@Composable
private fun L2LinkInspectorDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Profile>?>(null) }
    InfoDialog("Link inspector", onClose) {
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text("Paste a server link") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                input = runCatching { ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
            }) { Text("Paste") }
            Button(enabled = input.isNotBlank(), onClick = { found = runCatching { LinkParser.parseAny(input) }.getOrDefault(emptyList()) }) { Text("Inspect") }
        }
        found?.let { list ->
            if (list.isEmpty()) Text("No valid server found in that text.", color = WkRed)
            list.forEach { p ->
                Text(p.name, fontWeight = FontWeight.Bold, maxLines = 1)
                val lines = buildList {
                    add("Protocol: ${p.proto.label}")
                    if (p.host.isNotBlank()) add("Server: ${p.host}:${p.port}")
                    add("Transport: ${p.network}   Security: ${p.security}")
                    if (p.sni.isNotBlank()) add("SNI: ${p.sni}")
                    if (p.hostHeader.isNotBlank()) add("Host header: ${p.hostHeader}")
                    if (p.path.isNotBlank()) add("Path: ${p.path}")
                    if (p.flow.isNotBlank()) add("Flow: ${p.flow}")
                    if (p.user.isNotBlank()) add("ID / user: ${p.user.take(4)}…")
                }
                Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                val warn = buildList {
                    if ((p.proto == Proto.VLESS || p.proto == Proto.TROJAN) && p.security == "none") add("Transport security is off: traffic is not protected by TLS or Reality.")
                    if (p.insecure) add("Accepts self-signed certificates: weak against interception.")
                    if (p.security == "reality" && p.publicKey.isBlank()) add("Reality needs a public key and this link has none.")
                    if (p.security == "tls" && p.sni.isBlank()) add("No SNI set: some networks and CDNs need one.")
                }
                if (warn.isEmpty()) Text("No problems spotted.", color = WkOk, fontSize = 12.sp)
                else warn.forEach { Text("⚠ $it", color = WkViolet, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun L2AppQrDialog(onClose: () -> Unit) {
    val text = "$APP_TITLE - get it on Telegram: $TELEGRAM"
    val bmp = remember {
        runCatching {
            val n = 640
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, n, n, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(n * n) { i -> if (m[i % n, i / n]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    InfoDialog("Share the app", onClose) {
        if (bmp != null) {
            Box(Modifier.fillMaxWidth().background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), null, Modifier.size(240.dp))
            }
        }
        Text("A friend scans this to open the Whitekid Telegram channel and get the app.", color = WkMuted, fontSize = 12.sp)
    }
}
