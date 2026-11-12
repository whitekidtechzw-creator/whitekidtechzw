package zw.whitekid.vpn.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import zw.whitekid.vpn.R
import zw.whitekid.vpn.data.History
import androidx.core.text.HtmlCompat
import zw.whitekid.vpn.data.Builtin
import zw.whitekid.vpn.data.Importer
import zw.whitekid.vpn.data.LinkParser
import zw.whitekid.vpn.data.Updater
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.vpn.HealthCheck
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val GITHUB_REPO = "whitekid-tech/whitekid-vpn"

fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" }.getOrDefault("")

private fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
fun isNewer(latest: String, current: String): Boolean {
    val a = nums(latest); val b = nums(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

fun fmtDur(s: Long): String = "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)

// ------------------------------------------------------------------ drawer ("3 lines" menu)
@Composable
fun DrawerSheet(version: String, onItem: (String) -> Unit) {
    val items: List<Triple<String, String, ImageVector>> = listOf(
        Triple("quick", "Quick connect (fastest server)", Icons.Default.Star),
        Triple("ip", "IP Hunter: my IP and location", Icons.Default.Place),
        Triple("history", "Connection history and stats", Icons.Default.Info),
        Triple("servers", "My servers", Icons.Default.Menu),
        Triple("tools", "Split tunneling, DNS and tools", Icons.Default.Settings),
        Triple("themes", "Themes and appearance", Icons.Default.Edit),
        Triple("update", "Check for update", Icons.Default.Refresh),
        Triple("telegram", "Telegram channel", Icons.AutoMirrored.Filled.Send),
        Triple("share", "Share the app", Icons.Default.Share),
        Triple("help", "Help and FAQ", Icons.Default.Info),
        Triple("new", "What's new", Icons.Default.Check),
        Triple("licenses", "Open-source licenses", Icons.Default.Info),
        Triple("support", "Contact support", Icons.Default.Person),
        Triple("exit", "Disconnect and exit", Icons.Default.Close)
    )
    ModalDrawerSheet(drawerContainerColor = WkCard) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("WHITEKID VPN", fontWeight = FontWeight.Black, color = WkMint, letterSpacing = 3.sp)
                    Text("Version $version", color = WkMuted, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = WkLine)
            Spacer(Modifier.height(8.dp))
            items.forEach { (k, label, icon) ->
                NavigationDrawerItem(
                    label = { Text(label) }, selected = false, onClick = { onItem(k) },
                    icon = { Icon(icon, null) }, modifier = Modifier.padding(horizontal = 12.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent, unselectedTextColor = WkText, unselectedIconColor = WkMint)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ small info dialogs
@Composable
fun InfoDialog(title: String, onClose: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = body) },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

data class Release(val tag: String, val page: String, val apk: String, val notes: String)

/** Latest GitHub release of the app, including the direct link of its .apk. Call off the main thread. */
fun checkLatestRelease(): Release? = runCatching {
    val j = JSONObject(Importer.fetch("https://api.github.com/repos/$GITHUB_REPO/releases/latest"))
    var apk = ""
    val assets = j.optJSONArray("assets")
    if (assets != null) for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a.optString("browser_download_url"); break }
    }
    Release(j.getString("tag_name"), j.getString("html_url"), apk, j.optString("body"))
}.getOrNull()

@Composable
fun UpdateDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("Checking…") }
    var notes by remember { mutableStateOf("") }
    var apk by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(-1) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val cur = appVersion(ctx)
        val r = withContext(Dispatchers.IO) { checkLatestRelease() }
        if (r == null) { msg = "Couldn't check for updates. Check your internet. If no release has been published yet, there is nothing to download."; return@LaunchedEffect }
        if (isNewer(r.tag, cur)) { msg = "Update available: ${r.tag} (you have $cur)"; notes = r.notes.take(600); apk = r.apk; page = r.page; Store.newerVersion = r.tag }
        else { msg = "You're up to date ($cur)."; Store.newerVersion = "" }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Check for update") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(msg)
                if (notes.isNotBlank()) Text(notes, color = WkMuted, fontSize = 13.sp)
                if (progress in 0..99) { LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("Downloading… $progress%", fontSize = 12.sp, color = WkMuted) }
                if (progress == 100) Text("Downloaded. Tap Install when Android asks. If nothing opens, allow installs from Whitekid VPN and tap Download again.", fontSize = 12.sp, color = WkMuted)
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
            }
        },
        confirmButton = {
            when {
                apk.isNotEmpty() -> Button(enabled = progress !in 0..99, onClick = {
                    err = ""; progress = 0
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Updater.download(ctx, apk) { pct -> progress = pct } } }
                        r.onSuccess { f -> progress = 100; Updater.install(ctx, f) }
                        r.onFailure { err = "Download failed: ${it.message}"; progress = -1 }
                    }
                }) { Text("Download and install") }
                page.isNotEmpty() -> Button(onClick = { open(ctx, page) }) { Text("Open download page") }
                else -> TextButton(onClick = onClose) { Text("OK") }
            }
        },
        dismissButton = { if (apk.isNotEmpty() || page.isNotEmpty()) TextButton(onClick = onClose) { Text("Later") } }
    )
}

@Composable
fun IpDialog(p: Profile?, connected: Boolean, onClose: () -> Unit) {
    var msg by remember { mutableStateOf("Looking up…") }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = if (connected && p != null) HealthCheck.open(p, "https://ipinfo.io/json")
                else (URL("https://ipinfo.io/json").openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 10000 }
                val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                JSONObject(body)
            }
        }
        r.onSuccess { j ->
            msg = "IP: ${j.optString("ip")}\nLocation: ${listOf(j.optString("city"), j.optString("region"), j.optString("country")).filter { it.isNotBlank() }.joinToString(", ")}\n" +
                "Network: ${j.optString("org")}\n\n" + if (connected) "Measured through the VPN tunnel." else "VPN is off, so this is your real IP."
        }
        r.onFailure { msg = "Couldn't look up your IP (${it.message}). Check your internet." }
    }
    InfoDialog("IP Hunter", onClose) { Text(msg) }
}

@Composable
fun HistoryDialog(onClose: () -> Unit) {
    var list by remember { mutableStateOf(History.list()) }
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Connection history") },
        text = {
            Column {
                Text("Total: ↓ ${bytes(Store.totalRx)}   ↑ ${bytes(Store.totalTx)}", fontWeight = FontWeight.SemiBold)
                Text("${Store.sessions} sessions • longest ${fmtDur(Store.longestSec)}", color = WkMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (list.isEmpty()) Text("No sessions yet.", color = WkMuted)
                else LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(list) { e ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(e.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${fmt.format(Date(e.start))} • ${fmtDur(e.seconds)} • ↓ ${bytes(e.rx)} ↑ ${bytes(e.tx)}", color = WkMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { TextButton(onClick = { History.clear(); list = emptyList() }) { Text("Clear") } }
    )
}

@Composable
fun ThemesDialog(onClose: () -> Unit) {
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    var mode by remember { mutableIntStateOf(Store.themeMode) }
    var accent by remember { mutableIntStateOf(Store.accent) }
    InfoDialog("Themes and appearance", onClose) {
        Text("Look", color = WkMuted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Follow my phone", "Light", "Dark").forEachIndexed { i, l ->
                FilterChip(selected = mode == i, onClick = { mode = i; Store.themeMode = i; applyTheme(sysDark) }, label = { Text(l) })
            }
        }
        Text("Accent colour", color = WkMuted, fontSize = 12.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ACCENTS.forEachIndexed { i, (name, c) ->
                FilterChip(selected = accent == i, onClick = { accent = i; Store.accent = i; accentBase = c },
                    label = { Text(name) }, leadingIcon = { Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(c)) })
            }
        }
        Text("Text size and font follow your phone's settings.", color = WkMuted, fontSize = 12.sp)
    }
}

@Composable
fun HelpDialog(onClose: () -> Unit) {
    InfoDialog("Help and FAQ", onClose) {
        listOf(
            "How do I add a server?" to "Servers → + → paste a link (vless, vmess, trojan, ss, ssh, slowdns), a WireGuard config, a wk1: code or an https config link → Save.",
            "How do I share configs?" to "Servers → Create file. Pick servers, set a file name, expiry, optional data limit, note, password and device lock, then share the .wkp file or copy the code.",
            "What is the Device ID?" to "Tools → This device. Send it to the person who makes your file so the file only opens on your phone.",
            "It says the config isn't working" to "The app connected but no internet passes through it. Check the server details, try another server, or ask the owner for a new file.",
            "What does CONNECTED mean?" to "You see CONNECTING while the app builds the tunnel. CONNECTED appears only once real internet traffic was proven through your server, so it is always true.",
            "It says Waiting for network" to "Your phone has no Wi-Fi or mobile data right now. The VPN stays ready and reconnects by itself the moment a network is back.",
            "Will it disconnect by itself?" to "No. If the link drops it retries again and again (and can switch to another server of the same network). Only you, an expired file, a data limit or the sleep timer stop it.",
            "Something won't connect" to "Tools → Connection doctor checks your network, DNS and server and tells you what is wrong.",
            "How do I get a kill switch?" to "Tools → Kill switch → Android VPN settings → turn on Always-on VPN and Block connections without VPN.",
            "Why is SlowDNS slow?" to "It carries your data inside DNS queries, which is slow by nature. Use it when nothing else works."
        ).forEach { (q, a) ->
            Text(q, fontWeight = FontWeight.SemiBold)
            Text(a, color = WkMuted, fontSize = 13.sp)
        }
    }
}

@Composable
fun WhatsNewDialog(version: String, onClose: () -> Unit) {
    InfoDialog("What's new in $version", onClose) {
        listOf(
            "🔌 Connect screen: link quality verdict, average and peak speed, connect time, ping trend, expiry and data warnings, monthly budget bar, yesterday vs today, Find fastest, sleep timer chips, share stats, last session card",
            "🖧 Servers screen: Favourites and protocol filters, new sort modes (Expiry, Reliable, Group), undo for deletes, one-tap Remove expired and Remove duplicates, data limit bars, reliability and expiry colours, tap a ping to re-test it, summary line",
            "🧪 Labs II: 30 more tools (11-40) in Tools, in four groups: Network, Servers, Data, Setup",
            "🌐 Network: network details, DNS lookup, IP lookup, latency map, upload and download speed test with history, clock check, ping watchlist, Private DNS check",
            "🖧 Servers: rank by ping, reliability scoreboard, remove duplicates, delete a group, inventory, share list as CSV, copy backup code, reset counters, reorder",
            "📊 Data: 30-day report with streak, monthly budget with forecast, data cost calculator, usage CSV",
            "🧰 Setup: health check with one-tap fixes, settings shortcuts, Base64, hash generator, password generator Pro, link inspector, share-the-app QR",
            "🌑 AMOLED pure black mode (Tools, item 40)",
            "🧪 Whitekid Labs (Tools): IP and ISP info, Cloudflare trace, website checker, port checker, jitter and packet-loss test",
            "🧰 Expiry tracker, remove expired servers, UUID and password generator, share usage report, clear cache",
            "🎛 New look: carbon and bone with a signal-orange accent, gauge dial, instrument readouts, flat lists",
            "🛡 Shield levels, a real kill switch, real-IP vs exit-IP protection check and a live log on Home",
            "📶 Server signal bars with automatic ping when you open Servers",
            "⚡ Real-time network watcher, auto-heal retries and a live speed graph with link health",
            "🛠 Ping tool, support report, ad blocking DNS, data alerts and a 7-day usage chart",
            "🔌 Clean connect: CONNECTING, then CONNECTED only when real traffic is proven",
            "♻️ Never gives up: retries forever, waits when offline, resumes when the network returns",
            "🐢 Stronger SlowDNS: resolver failover, patient timeouts and an engine watchdog",
            "📡 Live speed, ping and quality, exit IP, keep-alive pings and a live notification",
            "🛡 Secure DNS, DNS backups, IPv6 leak guard, stay-alive lock and server failover",
            "🩺 Connection doctor, data usage today and this month, full speed test",
            "☰ Side menu with quick connect, IP Hunter, history and more",
            "🌗 Light and dark mode follow your phone, with your font size",
            "⭐ Favourites, search, sorting, rename, duplicate, QR and copy for servers",
            "📊 Connection history and lifetime stats",
            "⏱ Sleep timer, connect on launch, connect on boot, Quick Settings tile",
            "🔒 App PIN and screenshot blocking",
            "🧹 Backup servers, reset app, family-safe and malware-blocking DNS"
        ).forEach { Text(it) }
    }
}

@Composable
fun LicensesDialog(onClose: () -> Unit) {
    InfoDialog("Open-source licenses", onClose) {
        listOf(
            "WireGuard (wireguard-android): Apache-2.0", "Xray-core (via AndroidLibXrayLite): MPL-2.0",
            "JSch (mwiede fork): BSD", "dnstt (SlowDNS client): public domain", "ZXing: Apache-2.0", "Jetpack Compose and AndroidX: Apache-2.0"
        ).forEach { Text(it) }
        Text("Licenses are as published by each project. Whitekid VPN is by Whitekid Tech.", color = WkMuted, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ QR + server actions
@Composable
fun QrDialog(text: String, onClose: () -> Unit) {
    val bmp = remember(text) {
        runCatching {
            val n = 640
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, n, n, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(n * n) { i -> if (m[i % n, i / n]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
    InfoDialog("QR code", onClose) {
        if (bmp != null) {
            Box(Modifier.fillMaxWidth().background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), null, Modifier.size(260.dp))
            }
            Text("Scan it with another phone's QR reader and paste the text into Servers → +.", color = WkMuted, fontSize = 12.sp)
        } else Text("This server is too big for a QR code. Use Copy share code instead.")
    }
}

@Composable
fun ServerActionsDialog(
    p: Profile, onClose: () -> Unit, onRename: () -> Unit, onDuplicate: () -> Unit, onCopy: () -> Unit,
    onQr: () -> Unit, onResetUsage: () -> Unit, onDelete: () -> Unit
) {
    @Composable fun row(label: String, color: Color = WkText, act: () -> Unit) {
        TextButton(onClick = { act(); onClose() }, Modifier.fillMaxWidth()) {
            Text(label, color = color, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
        }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text(p.name, maxLines = 1) },
        text = {
            Column {
                if (!p.locked) {
                    row("Rename", act = onRename); row("Duplicate", act = onDuplicate)
                    row("Copy share code", act = onCopy); row("Show QR code", act = onQr)
                }
                if (p.dataLimitMb > 0) row("Reset data usage", act = onResetUsage)
                row("Delete", WkRed, onDelete)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
fun RenameDialog(p: Profile, onClose: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Rename server") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name.trim()); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ PIN lock
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(WkBg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(110.dp).clip(RoundedCornerShape(26.dp)))
        Spacer(Modifier.height(20.dp))
        Text("Enter your PIN", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pin, {
            pin = it.filter { c -> c.isDigit() }.take(8); err = ""
            if (pin.length >= 4 && Store.hashPin(pin) == Store.pinHash) onUnlock()
        }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("PIN") })
        if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { if (Store.hashPin(pin) == Store.pinHash) onUnlock() else err = "Wrong PIN" }) { Text("Unlock") }
    }
}

@Composable
fun PinDialog(onClose: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val has = Store.pinHash.isNotEmpty()
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text(if (has) "Change or remove PIN" else "Set a PIN") },
        text = {
            Column {
                OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(8); err = "" }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), label = { Text("New PIN (4 to 8 digits)") })
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { if (pin.length < 4) err = "Use at least 4 digits" else { Store.pinHash = Store.hashPin(pin); onClose() } }) { Text("Save") } },
        dismissButton = { Row { if (has) TextButton(onClick = { Store.pinHash = ""; onClose() }) { Text("Remove") }; TextButton(onClick = onClose) { Text("Cancel") } } }
    )
}


// ------------------------------------------------------------------ news, password lock, Add Tweak
@Composable
fun ProfilePassDialog(p: Profile, onClose: () -> Unit, onOk: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Password required") },
        text = {
            Column {
                Text("${p.name} is protected by a password.", color = WkMuted, fontSize = 13.sp)
                OutlinedTextField(pw, { pw = it; err = "" }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { if (Store.sha256(pw) == p.passwordHash) onOk() else err = "Wrong password." }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
fun AddTweakDialog(onClose: () -> Unit, onSave: (Profile) -> Unit) {
    val modes = listOf("ssl" to "SSL (SSH + SNI)", "ssh" to "SSH", "slowdns" to "SlowDNS", "vless" to "VLESS",
        "vmess" to "VMess", "trojan" to "Trojan", "ss" to "Shadowsocks", "wg" to "WireGuard")
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("ssl") }
    var menu by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var sni by remember { mutableStateOf("") }
    var proxy by remember { mutableStateOf("") }
    var ns by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var xExp by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("7") }
    var xHw by remember { mutableStateOf(false) }
    var hw by remember { mutableStateOf("") }
    var xPw by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var xMob by remember { mutableStateOf(false) }
    var xRoot by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    fun enc(s: String) = java.net.URLEncoder.encode(s.trim(), "UTF-8")
    fun save() {
        if (name.isBlank()) { err = "Enter a tweak name."; return }
        val url = when (mode) {
            "ssl" -> "ssh://${enc(user)}:${enc(pass)}@${host.trim()}:${port.ifBlank { "443" }.trim()}?sni=${enc(sni)}#${enc(name)}"
            "ssh" -> "ssh://${enc(user)}:${enc(pass)}@${host.trim()}:${port.ifBlank { "22" }.trim()}" +
                (if (proxy.isNotBlank()) "?proxy=${enc(proxy)}" else "") + "#${enc(name)}"
            "slowdns" -> "slowdns://${enc(user)}:${enc(pass)}@${ns.trim()}?key=${enc(key)}#${enc(name)}"
            else -> link
        }
        val builtin = if (mode == "slowdns") Builtin.slowDns() else null
        val parsed = builtin?.copy(id = java.util.UUID.randomUUID().toString())
            ?: runCatching { LinkParser.parseAny(url).firstOrNull() }.getOrNull()
        if (parsed == null) { err = "Those details couldn't be read. Check the fields."; return }
        val plain = HtmlCompat.fromHtml(msg, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        val d = days.toLongOrNull() ?: 0L
        onSave(parsed.copy(
            name = name.trim(), note = plain,
            expires = if (xExp && d > 0) System.currentTimeMillis() + d * 86_400_000L else 0L,
            device = if (xHw) hw.trim().uppercase() else "",
            passwordHash = if (xPw && pw.isNotBlank()) Store.sha256(pw) else "",
            mobileOnly = xMob, blockRooted = xRoot))
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Add Tweak") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; err = "" }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tweak Name") })
                OutlinedTextField(msg, { msg = it }, Modifier.fillMaxWidth(), label = { Text("Tweak Message") }, placeholder = { Text("Optional (HTML supported)") })
                Text("Tweak Mode", color = WkMuted, fontSize = 12.sp)
                Box {
                    OutlinedButton(onClick = { menu = true }, Modifier.fillMaxWidth()) { Text(modes.first { it.first == mode }.second + "  ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        modes.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { mode = k; menu = false; err = "" }) }
                    }
                }
                when (mode) {
                    "ssl", "ssh" -> {
                        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SSH host") })
                        OutlinedTextField(port, { port = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Port") }, placeholder = { Text(if (mode == "ssl") "443" else "22") })
                        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
                        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") })
                        if (mode == "ssl") OutlinedTextField(sni, { sni = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("SNI Host") }, placeholder = { Text("Ex: m.google.com") })
                        else OutlinedTextField(proxy, { proxy = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("HTTP proxy (optional)") }, placeholder = { Text("host:port") })
                    }
                    "slowdns" -> {
                        Text("DNS Server: Google 8.8.8.8 (fixed)", fontWeight = FontWeight.SemiBold)
                        if (Builtin.slowDns() != null) {
                            Text("This uses the built-in Whitekid SlowDNS server. Nothing else to fill in, just give it a name and save.", color = WkMuted, fontSize = 13.sp)
                        } else {
                            Text("No built-in server in this build, so enter your own SlowDNS server below.", color = WkMuted, fontSize = 13.sp)
                            OutlinedTextField(ns, { ns = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nameserver domain") }, placeholder = { Text("ns.example.com") })
                            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server public key (hex)") })
                            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SSH username") })
                            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SSH password") })
                        }
                    }
                    else -> OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp),
                        label = { Text(if (mode == "wg") "WireGuard config" else "Server link") })
                }
                Text("Extras", color = WkMuted, fontSize = 12.sp)
                @Composable fun chk(label: String, v: Boolean, set: (Boolean) -> Unit) {
                    Row(Modifier.fillMaxWidth().clickable { set(!v) }, verticalAlignment = Alignment.CenterVertically) { Checkbox(v, { set(it) }); Text(label) }
                }
                chk("Set Expiration Date", xExp) { xExp = it }
                if (xExp) OutlinedTextField(days, { days = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Expires after (days)") })
                chk("HWID Lock", xHw) { xHw = it }
                if (xHw) OutlinedTextField(hw, { hw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Device ID (Tools → This device)") })
                chk("Password Lock", xPw) { xPw = it }
                if (xPw) OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") })
                chk("Mobile Data Only", xMob) { xMob = it }
                chk("Block Rooted Devices", xRoot) { xRoot = it }
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { save() }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
