package zw.whitekid.vpn.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import zw.whitekid.vpn.data.Builtin
import zw.whitekid.vpn.data.NetInfo
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import java.util.UUID
import zw.whitekid.vpn.vpn.DnsBench
import zw.whitekid.vpn.vpn.HealthCheck
import zw.whitekid.vpn.vpn.Warp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import zw.whitekid.vpn.R
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.vpn.Pinger
import androidx.core.content.FileProvider
import java.io.File
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import zw.whitekid.vpn.data.Clock
import zw.whitekid.vpn.data.ImportResult
import zw.whitekid.vpn.data.Importer
import zw.whitekid.vpn.data.WkFile
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.vpn.Status
import zw.whitekid.vpn.vpn.VpnController
import zw.whitekid.vpn.vpn.VpnState
import zw.whitekid.vpn.vpn.rateText

const val TELEGRAM = "https://t.me/whitekidtechzw"
const val APP_TITLE = "Whitekid Virtual Private Networking"

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Store.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        readIntent(intent)
        setContent { WkTheme { Surface(Modifier.fillMaxSize(), color = WkBg) { WkApp(incoming, ::finishAndRemoveTask) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    /** A .wkp file opened from Telegram / Files lands here and goes straight to the import dialog. */
    private fun readIntent(i: Intent?) {
        val u = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        runCatching { contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText().take(200_000) } }
            .getOrNull()?.let { incoming.value = it }
    }
}

@Composable
fun WkApp(incoming: MutableState<String?>, exit: () -> Unit) {
    var unlocked by remember { mutableStateOf(Store.pinHash.isEmpty()) }
    if (unlocked) WkMain(incoming, exit) else LockScreen { unlocked = true }
}

@Composable
fun WkMain(incoming: MutableState<String?>, exit: () -> Unit) {
    val ctx = LocalContext.current
    val profiles = remember { mutableStateListOf<Profile>().apply { addAll(Store.profiles()) } }
    var selected by remember { mutableStateOf(Store.selectedId) }
    var tab by remember { mutableIntStateOf(0) }
    val status by VpnState.status.collectAsState()
    LaunchedEffect(incoming.value) { if (incoming.value != null) tab = 1 }
    LaunchedEffect(Unit) {
        if (System.currentTimeMillis() - Store.lastLinkUpdate > 6 * 3_600_000L && profiles.any { it.sourceUrl.isNotBlank() }) {
            refreshLinks(profiles); Store.saveProfiles(profiles.toList())
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    val current = profiles.firstOrNull { it.id == selected } ?: profiles.firstOrNull()
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val ver = remember { appVersion(ctx) }
    var dlg by remember { mutableStateOf("") }
    var askStop by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(ver.isNotEmpty() && Store.seenVersion != ver) }
    var pending by remember { mutableStateOf<Profile?>(null) }
    var passFor by remember { mutableStateOf<Profile?>(null) }
    val unlockedIds = remember { mutableSetOf<String>() }
    var group by remember { mutableStateOf(Store.group) }
    var newerVersion by remember { mutableStateOf(Store.newerVersion) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) (pending ?: current)?.let { p -> VpnController.connect(ctx, p) }
        pending = null
    }
    fun startConnect(p: Profile) {
        if (p.passwordHash.isNotEmpty() && p.id !in unlockedIds) { passFor = p; return }
        val i = VpnService.prepare(ctx)
        if (i != null) { pending = p; vpnConsent.launch(i) } else VpnController.connect(ctx, p)
    }
    fun quickConnect() {
        if (profiles.isEmpty()) { tab = 1; return }
        if (status == Status.CONNECTED || status == Status.CONNECTING) {
            Toast.makeText(ctx, "Disconnect first, then use Quick connect.", Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(ctx, "Finding the fastest server…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val res = profiles.toList().filter { !it.expired() && (group == "All" || it.groupName() == group) }.map { p -> async(Dispatchers.IO) { p to Pinger.tcp(p) } }.awaitAll()
            val best = res.filter { it.second >= 0 }.minByOrNull { it.second }?.first ?: res.firstOrNull { it.second == -2 }?.first
            if (best == null) { Toast.makeText(ctx, "No server answered.", Toast.LENGTH_LONG).show(); return@launch }
            selected = best.id; Store.selectedId = best.id
            AppLog.log("Auto select: ${best.name}")
            startConnect(best)
        }
    }
    fun toggle() {
        if (Store.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (status == Status.CONNECTED || status == Status.CONNECTING) {
            if (Store.confirmDisconnect && status == Status.CONNECTED) askStop = true else VpnController.disconnect(ctx)
            return
        }
        if (current == null) { tab = 1; return }
        if (Store.autoPick) { quickConnect(); return }
        startConnect(current)
    }
    DisposableEffect(view) {
        fun look() {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
            val t = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim() }.getOrNull() ?: return
            if (t.isEmpty() || t.length > 4000 || t.hashCode() == Store.lastClip) return
            if (Regex("^((vless|vmess|trojan|ss|ssh|slowdns)://|wk1:)", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                Store.lastClip = t.hashCode(); incoming.value = t
            }
        }
        val l = android.view.ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) look() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(l)
        if (view.hasWindowFocus()) look()
        onDispose { runCatching { view.viewTreeObserver.removeOnWindowFocusChangeListener(l) } }
    }
    DisposableEffect(status) {
        view.keepScreenOn = Store.keepAwake && status == Status.CONNECTED
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(Unit) {
        // check for a new app version at most every 6 hours
        if (System.currentTimeMillis() - Store.lastUpdateCheck > 6 * 3_600_000L) {
            val latest = withContext(Dispatchers.IO) { checkLatestRelease() }
            if (latest != null) {
                Store.lastUpdateCheck = System.currentTimeMillis()
                if (isNewer(latest.tag, ver)) { Store.newerVersion = latest.tag; newerVersion = latest.tag; dlg = "update" }
                else { Store.newerVersion = ""; newerVersion = "" }
            }
        }
    }
    LaunchedEffect(Unit) {
        // the owner's built-in SlowDNS server (set at build time) is always available
        if (Builtin.ensure(profiles)) {
            Store.saveProfiles(profiles.toList())
            if (selected.isEmpty()) { selected = "builtin_slowdns"; Store.selectedId = selected }
        }
    }
    LaunchedEffect(tab) {
        val fresh = Store.profiles()
        if (fresh != profiles.toList()) { profiles.clear(); profiles.addAll(fresh) }
    }
    LaunchedEffect(Unit) {
        if (Store.autoConnect && status == Status.IDLE && current != null && NetInfo.ruleAllows(ctx, Store.autoNet)) { delay(800); startConnect(current) }
    }
    fun onDrawer(k: String) {
        scope.launch { drawer.close() }
        when (k) {
            "quick" -> quickConnect()
            "servers" -> tab = 1
            "tools" -> tab = 2
            "telegram", "support" -> open(ctx, TELEGRAM)
            "share" -> ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "$APP_TITLE — get it on Telegram: $TELEGRAM"), "Share"))
            "exit" -> { VpnController.disconnect(ctx); exit() }
            else -> dlg = k
        }
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = { DrawerSheet(ver) { onDrawer(it) } }) {
    Scaffold(
        containerColor = WkBg,
        bottomBar = { WkNavBar(tab) { tab = it } }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(current, status, profiles, group, newerVersion.isNotEmpty(), ::toggle,
                    onMenu = { scope.launch { drawer.open() } },
                    onBell = { dlg = "update" },
                    onDoctor = { dlg = "doctor" },
                    onGroup = { g -> group = g; Store.group = g },
                    onPick = { k ->
                        if (k == "auto") Store.autoPick = true
                        else { Store.autoPick = false; selected = k; Store.selectedId = k }
                    },
                    onSsl = { np, go ->
                        val i = profiles.indexOfFirst { it.id == np.id }
                        if (i >= 0) profiles[i] = np else profiles.add(0, np)
                        Store.saveProfiles(profiles.toList())
                        Store.autoPick = false; selected = np.id; Store.selectedId = np.id
                        if (go) {
                            if (status == Status.CONNECTED || status == Status.CONNECTING) Toast.makeText(ctx, "Disconnect first, then connect to the new SSL server.", Toast.LENGTH_LONG).show()
                            else startConnect(np)
                        }
                    }) { tab = 1 }
                1 -> ServersScreen(profiles, current?.id ?: "", incoming, onSelect = { selected = it; Store.selectedId = it }) {
                    Store.saveProfiles(profiles.toList())
                }
                2 -> ToolsScreen(profiles, exit)
                else -> AboutScreen()
            }
        }
    }
    }
    when (dlg) {
        "ip" -> IpDialog(current, status == Status.CONNECTED) { dlg = "" }
        "history" -> HistoryDialog { dlg = "" }
        "themes" -> ThemesDialog { dlg = "" }
        "update" -> UpdateDialog { dlg = "" }
        "help" -> HelpDialog { dlg = "" }
        "licenses" -> LicensesDialog { dlg = "" }
        "doctor" -> DoctorDialog(current) { dlg = "" }
    }
    if (askStop) AlertDialog(
        onDismissRequest = { askStop = false }, containerColor = WkCard, title = { Text("Disconnect?") },
        text = { Text("Your traffic will no longer go through the VPN.") },
        confirmButton = { Button(onClick = { askStop = false; VpnController.disconnect(ctx) }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { askStop = false }) { Text("Stay connected") } })
    passFor?.let { pf ->
        ProfilePassDialog(pf, onClose = { passFor = null }) { unlockedIds.add(pf.id); passFor = null; startConnect(pf) }
    }
    if (showNew || dlg == "new") WhatsNewDialog(ver) { showNew = false; dlg = ""; Store.seenVersion = ver }
}

// ---------------------------------------------------------------- Home
fun Profile.groupName() = group.ifBlank { "My servers" }

@Composable
fun WkNavBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf("CONNECT" to Icons.Default.Home, "SERVERS" to Icons.AutoMirrored.Filled.List,
        "TOOLS" to Icons.Default.Settings, "ABOUT" to Icons.Default.Info)
    Column(Modifier.background(WkBg)) {
        HorizontalDivider(color = WkLine)
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, (l, ic) ->
                val sel = tab == i
                val sc by animateFloatAsState(if (sel) 1.18f else 1f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "navScale")
                val pill by animateFloatAsState(if (sel) 1f else 0f, tween(260), label = "navPill")
                Column(Modifier.weight(1f).clickable { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(
                        Brush.horizontalGradient(listOf(Color.Transparent, WkMint.copy(alpha = pill), Color.Transparent))))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(WkMint.copy(alpha = 0.16f * pill))
                        .padding(horizontal = 18.dp, vertical = 4.dp)) {
                        Icon(ic, l, tint = if (sel) WkMint else WkMuted, modifier = Modifier.size(22.dp).scale(sc))
                    }
                    Text(l, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold, color = if (sel) WkMint else WkMuted,
                        modifier = Modifier.padding(top = 2.dp, bottom = 9.dp))
                }
            }
        }
    }
}

@Composable
fun Picker(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(Modifier.fillMaxWidth().clickable { open = true }, color = Color.Transparent, border = BorderStroke(1.dp, WkLine), shape = RoundedCornerShape(4.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(label, color = WkMuted, fontSize = 10.sp, letterSpacing = 2.sp)
                Text(value + "  ▾", fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 14.sp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (k, l) -> DropdownMenuItem(text = { Text(l, maxLines = 1) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
fun HomeScreen(
    p: Profile?, status: Status, profiles: List<Profile>, group: String, hasNews: Boolean,
    onToggle: () -> Unit, onMenu: () -> Unit, onBell: () -> Unit, onDoctor: () -> Unit, onGroup: (String) -> Unit, onPick: (String) -> Unit,
    onSsl: (Profile, Boolean) -> Unit,
    openServers: () -> Unit
) {
    val ctx = LocalContext.current
    val rx by VpnState.rxBytes.collectAsState(); val tx by VpnState.txBytes.collectAsState()
    val secs by VpnState.seconds.collectAsState(); val msg by VpnState.message.collectAsState()
    val dnsNote by VpnState.dnsNote.collectAsState()
    val step by VpnState.step.collectAsState(); val ping by VpnState.pingMs.collectAsState()
    val rxS by VpnState.rxSpeed.collectAsState(); val txS by VpnState.txSpeed.collectAsState()
    val reconns by VpnState.reconnects.collectAsState(); val drop by VpnState.lastDrop.collectAsState()
    val exit by VpnState.exitInfo.collectAsState()
    val graph by VpnState.speedHistory.collectAsState()
    val okN by VpnState.pingOk.collectAsState(); val badN by VpnState.pingFail.collectAsState(); val avgMs by VpnState.pingAvg.collectAsState()
    val protection by VpnState.protection.collectAsState()
    val logs by AppLog.lines.collectAsState()
    val today = remember(secs / 5) { Store.usedToday() }
    val scope = rememberCoroutineScope()
    var speed by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var customDns by remember { mutableStateOf(Store.customDns) }
    var shield by remember { mutableIntStateOf(Store.shield) }
    val localIp = remember(status) { NetInfo.localIp(ctx) }
    val groups = listOf("All") + profiles.map { it.groupName() }.distinct()
    val inGroup = profiles.filter { group == "All" || it.groupName() == group }
    val on = status == Status.CONNECTED
    L3ConnectTimerEffect(status)
    val (label, col) = when (status) {
        Status.CONNECTED -> "CONNECTED" to WkOk
        Status.CONNECTING -> "CONNECTING" to WkViolet
        Status.ERROR -> "FAILED" to WkRed
        Status.IDLE -> "NOT CONNECTED" to WkMuted
    }
    val sub = when (status) {
        Status.CONNECTING -> step.ifBlank { "Starting…" }
        Status.CONNECTED -> (p?.name ?: msg) + (p?.let { "  •  " + it.proto.label } ?: "")
        Status.ERROR -> ""
        Status.IDLE -> if (p != null) "Ready: ${p.name}" else "Add a server to begin"
    }

    val glowA = when (status) { Status.IDLE -> 0.05f; Status.CONNECTING -> 0.13f; else -> 0.17f }
    Column(Modifier.fillMaxSize()
        .drawBehind {
            drawRect(Brush.radialGradient(listOf(col.copy(alpha = glowA), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.30f), radius = size.width * 1.15f))
        }
        .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp)) {
        // ---- top bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("WHITEKID", fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 3.sp, color = WkText)
                Text(localIp, color = WkMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onBell) {
                Box {
                    Icon(Icons.Default.Notifications, "News", tint = WkText)
                    if (hasNews) Box(Modifier.size(8.dp).background(WkMint).align(Alignment.TopEnd))
                }
            }
            IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Menu", tint = WkText) }
        }
        Spacer(Modifier.height(20.dp))

        // ---- headline: state on the left, session clock on the right
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(label, color = col, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp,
                    style = androidx.compose.ui.text.TextStyle(shadow = if (status == Status.IDLE) null
                        else androidx.compose.ui.graphics.Shadow(col.copy(alpha = 0.65f), Offset.Zero, 26f)))
                if (sub.isNotBlank()) Text(sub, color = WkMuted, fontSize = 13.sp, maxLines = 1)
            }
            if (on) Text("%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60), fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = WkText)
        }
        if (status == Status.ERROR && msg.isNotBlank()) {
            Text(msg, color = WkRed, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(onClick = onDoctor, modifier = Modifier.padding(top = 8.dp)) { Text("Find out why") }
        }

        L3Banner(p)

        // ---- dial
        Box(Modifier.fillMaxWidth().padding(vertical = 0.dp), contentAlignment = Alignment.Center) {
            Dial(status, rxS + txS, onToggle)
        }

        // ---- instrument readouts
        HorizontalDivider(color = WkLine)
        Row(Modifier.fillMaxWidth()) {
            val (q, qc) = if (ping >= 0) quality(ping, p?.proto == Proto.SLOWDNS) else ("" to WkText)
            Readout("DOWN", if (on) rateText(rxS) else "—", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(52.dp).background(WkLine))
            Readout("UP", if (on) rateText(txS) else "—", Modifier.weight(1f).padding(start = 14.dp))
            Box(Modifier.width(1.dp).height(52.dp).background(WkLine))
            Readout("PING", if (on && ping >= 0) "$ping ms" else "—", Modifier.weight(1f).padding(start = 14.dp), if (q.isNotEmpty()) qc else WkText)
        }
        HorizontalDivider(color = WkLine)
        Row(Modifier.fillMaxWidth()) {
            Readout("RECEIVED", bytes(rx), Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(52.dp).background(WkLine))
            Readout("SENT", bytes(tx), Modifier.weight(1f).padding(start = 14.dp))
            Box(Modifier.width(1.dp).height(52.dp).background(WkLine))
            Readout("TODAY", bytes(today), Modifier.weight(1f).padding(start = 14.dp))
        }
        HorizontalDivider(color = WkLine)
        L3Usage(today)
        if (on) SpeedGraph(graph, Modifier.padding(top = 12.dp))
        L3Insights(status, p, ping, rx, tx, secs, graph, okN, badN)

        // ---- live details
        if (on) {
            Spacer(Modifier.height(8.dp))
            if (exit.isNotBlank()) KV("EXIT", exit) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Exit IP", exit.substringAfterLast("• ")))
                Toast.makeText(ctx, "IP copied", Toast.LENGTH_SHORT).show()
            }
            if (protection.isNotBlank()) KV("PROTECTION", protection, if (protection.startsWith("Warning")) WkRed else WkOk)
            if (dnsNote.isNotBlank()) KV("DNS", dnsNote.removePrefix("⚠ "), if (dnsNote.startsWith("⚠")) WkRed else WkText)
            L4DnsPanel(status)
            L4Score(status, p, ping, okN, badN, reconns)
            L4Actions(status, p, exit, dnsNote)
            L4KeepScreenOn(status)
            if (okN + badN > 0) KV("LINK", "${okN * 100 / (okN + badN)}% answered  •  avg $avgMs ms  •  $badN lost")
            if (reconns > 0) KV("RESTORED", "$reconns× automatically (last: $drop)")
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    if (!testing && p != null) {
                        testing = true; speed = "Testing…"
                        scope.launch {
                            speed = withContext(Dispatchers.IO) { runCatching { HealthCheck.fullTest(p).text() }.getOrElse { "Speed test failed" } }
                            testing = false
                        }
                    }
                }) { Text("SPEED TEST", letterSpacing = 1.sp, fontSize = 12.sp) }
                TextButton(onClick = { VpnController.reconnectNow() }) { Text("RECONNECT", letterSpacing = 1.sp, fontSize = 12.sp) }
            }
            if (speed.isNotEmpty()) Text(speed, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = WkText)
        }
        if (p != null && p.note.isNotBlank() && status != Status.ERROR) {
            val plain = androidx.core.text.HtmlCompat.fromHtml(p.note, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
            if (plain.isNotEmpty()) Text(plain, color = WkMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        // ---- route
        Spacer(Modifier.height(22.dp))
        Text("ROUTE", color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker("NETWORK", group, groups.map { it to it }, Modifier.weight(1f)) { onGroup(it) }
            Picker("SERVER", if (Store.autoPick) "Auto select" else (p?.name ?: "None"),
                listOf("auto" to "Auto select (fastest)") + inGroup.map { it.id to it.name }, Modifier.weight(1f)) { onPick(it) }
        }
        L3FindFastest(inGroup, onPick)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Checkbox(customDns, { customDns = it; Store.customDns = it })
            Text("Custom DNS", modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(Store.effectiveDns().substringBefore(","), color = WkMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().clickable { openServers() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p?.name ?: "Tap to add a server", fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(if (p == null) "Paste a link, config or tweak code" else "${p.proto.label} • ${p.detail()}" +
                    (p.expiryText()?.let { " • $it" } ?: "") + (Store.reliability(p.id)?.let { " • $it% reliable" } ?: ""),
                    color = WkMuted, fontSize = 12.sp, maxLines = 1)
            }
            Text("›", color = WkMuted, fontSize = 22.sp)
        }

        SslPanel(onSsl)

        // ---- shield
        Spacer(Modifier.height(14.dp))
        Text("SHIELD", color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, WkLine, RoundedCornerShape(10.dp))) {
            listOf("SPEED", "BALANCED", "FORTRESS").forEachIndexed { i, l ->
                Box(Modifier.weight(1f).background(if (shield == i) Brush.horizontalGradient(listOf(WkMint.copy(alpha = 0.78f), WkMint)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable { shield = i; Store.shield = i }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text(l, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = if (shield == i) WkBg else WkText)
                }
            }
        }
        Text(when (shield) {
            0 -> "Fastest. Standard protection only."
            1 -> "Blocks IPv6 leaks around the tunnel."
            else -> "Kill switch, IPv6 block and encrypted DNS. Applies on the next connect."
        }, color = WkMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

        L3SleepChips()
        L4QuickToggles()
        L4AlertChips()
        L4WeekBars(today)

        // ---- live console
        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("LIVE LOG", color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
            HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
            logs.takeLast(4).forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 2,
                    color = if (it.contains("ERROR")) WkRed else WkMuted)
            }
        }
        Text("Build ${appVersion(ctx)} • ${profiles.size} servers", color = WkMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    }
}

/** One labelled value of the instrument panel. */
@Composable
fun Readout(k: String, v: String, m: Modifier, c: Color = WkText) {
    Column(m.padding(vertical = 10.dp)) {
        Text(k, color = WkMuted, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Text(v, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c, maxLines = 1)
    }
}

/** Key / value line of the details list. Optionally tappable (copy). */
@Composable
fun KV(k: String, v: String, c: Color = WkText, onClick: (() -> Unit)? = null) {
    val base = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()
    Row(base.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(k, color = WkMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.width(96.dp).padding(top = 2.dp))
        Text(v, color = c, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** Live speed of the last 60 seconds, drawn as a filled line. */
@Composable
fun SpeedGraph(values: List<Long>, modifier: Modifier = Modifier) {
    val col = WkMint
    Canvas(modifier.fillMaxWidth().height(54.dp)) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(2048L).toFloat()
        val stepX = size.width / 59f
        val line = androidx.compose.ui.graphics.Path()
        val fill = androidx.compose.ui.graphics.Path()
        val firstX = size.width - (values.size - 1) * stepX
        fill.moveTo(firstX, size.height)
        values.forEachIndexed { i, v ->
            val x = size.width - (values.size - 1 - i) * stepX
            val y = size.height - (v / max) * (size.height - 4.dp.toPx()) - 2.dp.toPx()
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        fill.lineTo(size.width, size.height); fill.close()
        drawPath(fill, col.copy(alpha = 0.14f))
        drawPath(line, col, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Rough quality label for a round trip in ms. DNS tunnels are slow by nature, so they get wider limits. */
fun quality(ms: Int, slow: Boolean): Pair<String, Color> {
    val k = if (slow) 6 else 1
    return when {
        ms < 150 * k -> "Excellent" to WkOk
        ms < 400 * k -> "Good" to WkOk
        ms < 900 * k -> "Fair" to WkViolet
        else -> "Poor" to WkRed
    }
}

/**
 * The power control: a gauge ring with 60 tick marks. Connected, the ring fills with live speed (log scale);
 * connecting, a short arc circles the dial; failed, the ring turns red.
 */
@Composable
fun Dial(status: Status, speed: Long, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "dial")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "spin")
    val pulse by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "pulse")
    val breathe by inf.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val on = status == Status.CONNECTED
    val col = when (status) { Status.CONNECTED -> WkOk; Status.CONNECTING -> WkViolet; Status.ERROR -> WkRed; Status.IDLE -> WkMuted }
    val target = if (on) (Math.log10(speed.toDouble() + 10.0) / 7.0).coerceIn(0.05, 1.0).toFloat() * 270f else 0f
    val sweep by animateFloatAsState(target, tween(700), label = "sweep")
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
    val track = WkLine
    val ringCol = WkMint
    Box(Modifier.size(296.dp), contentAlignment = Alignment.Center) {
        // aura: soft glow plus radar ripples that leave the dial while connected
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val dialR = 122.dp.toPx()
            val strength = when (status) { Status.CONNECTED -> breathe; Status.CONNECTING -> 0.8f; Status.ERROR -> 0.6f; Status.IDLE -> 0.22f + 0.2f * breathe }
            val glowCol = if (status == Status.IDLE) ringCol else col
            drawCircle(Brush.radialGradient(listOf(glowCol.copy(alpha = 0.38f * strength), glowCol.copy(alpha = 0.10f * strength), Color.Transparent),
                center = center, radius = r), r, center)
            if (on || status == Status.CONNECTING) {
                for (k in 0..2) {
                    val t = (pulse + k / 3f) % 1f
                    drawCircle(col.copy(alpha = (1f - t) * 0.38f), dialR + (r - dialR) * t, center, style = Stroke(1.6.dp.toPx()))
                }
            }
        }
        Box(Modifier.size(244.dp).scale(press).clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() }) },
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                for (i in 0 until 60) {
                    val a = Math.toRadians(i * 6.0 - 90.0)
                    val big = i % 5 == 0
                    val len = if (big) 10.dp.toPx() else 5.dp.toPx()
                    val o = r - 2.dp.toPx(); val n = o - len
                    val lit = on && ((i * 6 + 135) % 360) <= sweep
                    val idleSpark = status == Status.IDLE && big
                    drawLine(if (lit) col else if (idleSpark) ringCol.copy(alpha = 0.35f + 0.4f * breathe) else track,
                        Offset(center.x + (n * Math.cos(a)).toFloat(), center.y + (n * Math.sin(a)).toFloat()),
                        Offset(center.x + (o * Math.cos(a)).toFloat(), center.y + (o * Math.sin(a)).toFloat()),
                        (if (lit) 2.2.dp else 1.5.dp).toPx())
                }
                val pad = 30.dp.toPx(); val d = size.minDimension - pad * 2
                val tl = Offset(pad, pad); val sz = Size(d, d); val w = 9.dp.toPx()
                drawArc(if (status == Status.ERROR) col.copy(alpha = 0.35f) else track, 135f, 270f, false, tl, sz, style = Stroke(w))
                if (on) {
                    drawArc(col.copy(alpha = 0.22f), 135f, sweep, false, tl, sz, style = Stroke(w * 2.4f, cap = StrokeCap.Round))
                    drawArc(col, 135f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                if (status == Status.CONNECTING) {
                    drawArc(col.copy(alpha = 0.25f), spin, 80f, false, tl, sz, style = Stroke(w * 2.4f, cap = StrokeCap.Round))
                    drawArc(col, spin, 80f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
                    drawArc(col.copy(alpha = 0.5f), spin + 180f, 40f, false, tl, sz, style = Stroke(w * 0.6f, cap = StrokeCap.Round))
                }
                val c = center; val pr = 30.dp.toPx()
                if (status != Status.IDLE) drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.22f * (if (on) breathe else 1f)), Color.Transparent), c, pr * 2.2f), pr * 2.2f, c)
                val iconCol = if (status == Status.IDLE) ringCol.copy(alpha = 0.55f + 0.45f * breathe) else col
                drawArc(iconCol, -60f, 300f, false, Offset(c.x - pr, c.y - pr + 4), Size(pr * 2, pr * 2), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawLine(iconCol, Offset(c.x, c.y - pr - 8), Offset(c.x, c.y - 2), 5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** Four-step signal meter from a ping result (null = not measured yet). */
@Composable
fun SignalBars(ms: Int?) {
    val lvl = when { ms == null -> 0; ms < 0 -> 0; ms < 120 -> 4; ms < 250 -> 3; ms < 500 -> 2; else -> 1 }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 4.dp).height(16.dp)) {
        for (i in 1..4) Box(Modifier.padding(end = 2.dp).width(4.dp).height((4 * i).dp).background(if (lvl >= i) WkOk else WkLine))
    }
}

@Composable
fun Stat(k: String, v: String, m: Modifier) {
    Card(m, colors = CardDefaults.cardColors(WkCard), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(k, color = WkMuted, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(v, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

fun bytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

// ---------------------------------------------------------------- Servers
/** Re-downloads every server that came from a config link and swaps in the fresh list. */
suspend fun refreshLinks(list: MutableList<Profile>): Int {
    var n = 0
    for (u in list.mapNotNull { it.sourceUrl.ifBlank { null } }.distinct()) {
        val r = withContext(Dispatchers.IO) { runCatching { Importer.run(Importer.fetch(u), null) }.getOrNull() }
        if (r is ImportResult.Ok && r.list.isNotEmpty()) {
            val old = list.filter { it.sourceUrl == u }
            list.removeAll { it.sourceUrl == u }
            list.addAll(r.list.map { np -> np.copy(sourceUrl = u, id = old.firstOrNull { it.name == np.name }?.id ?: np.id) })
            n += r.list.size
        }
    }
    Store.lastLinkUpdate = System.currentTimeMillis()
    if (n > 0) AppLog.log("Updated $n server(s) from config links")
    return n
}

fun Profile.detail(): String =
    if (locked) "🔒 Locked config" else "$host:$port" + if (sni.isNotBlank()) " • SNI $sni" else ""

fun Profile.expiryText(): String? {
    if (expires <= 0) return null
    val left = expires - Clock.now()
    return when {
        left <= 0 -> "Expired"
        left < 3_600_000 -> "Expires in ${left / 60_000} min"
        left < 86_400_000 -> "Expires in ${left / 3_600_000} h"
        else -> "Expires in ${left / 86_400_000} d"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(list: MutableList<Profile>, selected: String, incoming: MutableState<String?>,
                  onSelect: (String) -> Unit, persist: () -> Unit) {
    val ctx = LocalContext.current
    var addDialog by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var tweakDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pings = remember { mutableStateMapOf<String, Int>() }
    var pinging by remember { mutableStateOf(false) }
    var busyWarp by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableIntStateOf(0) }
    var favs by remember { mutableStateOf(Store.favorites) }
    var actionFor by remember { mutableStateOf<Profile?>(null) }
    var renameFor by remember { mutableStateOf<Profile?>(null) }
    var qrText by remember { mutableStateOf<String?>(null) }
    var protoFilter by remember { mutableStateOf("All") }
    var onlyFavs by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<Pair<Int, Profile>>>(emptyList()) }

    fun removeWithUndo(victims: List<Profile>) {
        if (victims.isEmpty()) return
        undo = victims.map { v -> list.indexOfFirst { it.id == v.id } to v }.filter { it.first >= 0 }.sortedBy { it.first }
        list.removeAll { x -> victims.any { it.id == x.id } }
        persist()
    }
    LaunchedEffect(undo) { if (undo.isNotEmpty()) { delay(7000); undo = emptyList() } }

    fun addWarp() {
        if (busyWarp) return
        busyWarp = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Warp.create() } }
            busyWarp = false
            r.onSuccess { w ->
                list.add(w); persist(); onSelect(w.id); AppLog.log("Added Cloudflare WARP")
                Toast.makeText(ctx, "WARP added. Go to Connect and tap the power button.", Toast.LENGTH_LONG).show()
            }
            r.onFailure { e ->
                AppLog.log("ERROR: WARP setup failed: ${e.message}")
                Toast.makeText(ctx, "Couldn't create a WARP account (${e.message}). It may be blocked on your network.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun pingAll(pick: Boolean) {
        if (pinging) return
        pinging = true
        scope.launch {
            val res = list.toList().map { p -> async(Dispatchers.IO) { p.id to Pinger.tcp(p) } }.awaitAll()
            res.forEach { (id, ms) -> pings[id] = ms }
            if (pick) {
                val best = list.filter { !it.expired() && (pings[it.id] ?: -1) >= 0 }.minByOrNull { pings[it.id] ?: Int.MAX_VALUE }
                if (best != null) { onSelect(best.id); AppLog.log("Auto select: ${best.name} (${pings[best.id]} ms)") }
                else AppLog.log("Auto select: no reachable server")
            }
            pinging = false
        }
    }

    LaunchedEffect(list.size) { if (list.isNotEmpty()) pingAll(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SERVERS", fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text("VLESS • VMess • Trojan • Shadowsocks • WireGuard • SSH • SlowDNS", color = WkMuted, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { createDialog = true }) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Create file")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pingAll(false) }, enabled = !pinging && list.isNotEmpty()) { Text(if (pinging) "Testing…" else "Ping all") }
                OutlinedButton(onClick = { pingAll(true) }, enabled = !pinging && list.isNotEmpty()) { Text("Auto select") }
                OutlinedButton(onClick = { addWarp() }, enabled = !busyWarp) { Text(if (busyWarp) "Creating…" else "+ Free WARP") }
                val expiredList = list.filter { it.expired() }
                if (expiredList.isNotEmpty()) OutlinedButton(onClick = { removeWithUndo(expiredList) }) { Text("Remove expired (${expiredList.size})") }
                val dupVictims = l3DupeVictims(list)
                if (dupVictims.isNotEmpty()) OutlinedButton(onClick = { removeWithUndo(dupVictims) }) { Text("Remove duplicates (${dupVictims.size})") }
                val deadList = l4DeadServers(list, pings)
                if (deadList.isNotEmpty()) OutlinedButton(onClick = { removeWithUndo(deadList) }) { Text("Remove unreachable (${deadList.size})") }
                OutlinedButton(onClick = {
                    val r = l4Random(list, pings)
                    if (r != null) { onSelect(r.id); Toast.makeText(ctx, "Selected ${r.name}", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(ctx, "Run Ping all first", Toast.LENGTH_SHORT).show()
                }, enabled = list.isNotEmpty()) { Text("Random") }
                L4PasteButton { incoming.value = it }
                L4BackupButtons(list)
                if (list.any { it.sourceUrl.isNotBlank() }) OutlinedButton(onClick = {
                    scope.launch {
                        val oldName = list.firstOrNull { it.id == selected }?.name
                        val n = refreshLinks(list); persist()
                        list.firstOrNull { it.name == oldName }?.let { onSelect(it.id) }
                        Toast.makeText(ctx, if (n > 0) "Updated $n server(s)" else "Couldn't update. Check your internet.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Update links") }
            }
            if (list.isNotEmpty()) {
                val reach = list.count { (pings[it.id] ?: -1) >= 0 }
                val fastest = list.mapNotNull { x -> pings[x.id]?.takeIf { it >= 0 }?.let { x.name to it } }.minByOrNull { it.second }
                Text("${list.size} servers  •  $reach reachable" + (fastest?.let { "  •  fastest ${it.first} ${it.second} ms" } ?: ""),
                    color = WkMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                L4ListFacts(list, pings)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search servers") }, leadingIcon = { Icon(Icons.Default.Search, null) })
            val protos = list.map { it.proto.label }.distinct()
            val pf = if (protoFilter != "All" && protoFilter !in protos) "All" else protoFilter
            if (list.size > 1) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Show", color = WkMuted, fontSize = 12.sp)
                FilterChip(selected = onlyFavs, onClick = { onlyFavs = !onlyFavs }, label = { Text("★ Favourites") })
                FilterChip(selected = pf == "All", onClick = { protoFilter = "All" }, label = { Text("All") })
                if (protos.size > 1) protos.forEach { l -> FilterChip(selected = pf == l, onClick = { protoFilter = l }, label = { Text(l) }) }
            }
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort", color = WkMuted, fontSize = 12.sp)
                listOf("Added", "Name", "Ping", "Expiry", "Reliable", "Group", "Used").forEachIndexed { i, l -> FilterChip(selected = sortMode == i, onClick = { sortMode = i }, label = { Text(l) }) }
            }
            val shown = list.filter { (query.isBlank() || it.name.contains(query, true) || it.proto.label.contains(query, true)) &&
                    (!onlyFavs || it.id in favs) && (pf == "All" || it.proto.label == pf) }
                .sortedWith(compareByDescending<Profile> { it.id in favs }.thenBy {
                    when (sortMode) {
                        1 -> it.name.lowercase()
                        2 -> (pings[it.id]?.takeIf { ms -> ms >= 0 } ?: Int.MAX_VALUE).toString().padStart(10, '0')
                        3 -> if (it.expires > 0) it.expires.toString().padStart(15, '0') else "9".repeat(15)
                        4 -> (100 - (Store.reliability(it.id) ?: -1)).toString().padStart(4, '0')
                        5 -> it.groupName().lowercase()
                        6 -> (Long.MAX_VALUE - Store.usedBytes(it.id)).toString().padStart(20, '0')
                        else -> ""
                    }
                })
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) Text("Nothing here yet. Tap + and paste a vless://, vmess://, trojan://, ssh://, slowdns:// link, a WireGuard config, or a shared file code.", color = WkMuted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                items(shown, key = { it.id }) { p ->
                    val exp = p.expiryText()
                    val sel = p.id == selected
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).combinedClickable(onClick = { onSelect(p.id) }, onLongClick = { actionFor = p }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(if (sel) WkMint else Color.Transparent))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.proto.label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = WkMint, letterSpacing = 1.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(p.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                            }
                            Text(p.detail() + (Store.reliability(p.id)?.let { "  •  $it% reliable" } ?: ""), color = WkMuted, fontSize = 12.sp, maxLines = 1)
                            if (exp != null) Text(exp, color = when { exp == "Expired" -> WkRed; p.expires - Clock.now() < 259_200_000L -> WkViolet; else -> WkMint }, fontSize = 11.sp)
                            if (p.dataLimitMb > 0) {
                                Text("Data ${Store.usedBytes(p.id) / 1_000_000} / ${p.dataLimitMb} MB", color = WkMuted, fontSize = 11.sp)
                                L3DataBar(Store.usedBytes(p.id), p.dataLimitMb)
                            }
                            L4UsedLine(p)
                            if (p.note.isNotBlank()) Text(p.note, color = WkMuted, fontSize = 11.sp, maxLines = 2)
                        }
                        pings[p.id]?.let { ms ->
                            Text(when { ms >= 0 -> "$ms ms"; ms == -1 -> "timeout"; else -> "—" },
                                color = WkMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { scope.launch { pings[p.id] = withContext(Dispatchers.IO) { Pinger.tcp(p) } } }
                                    .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 10.dp))
                        }
                        SignalBars(pings[p.id])
                        IconButton(onClick = { favs = if (p.id in favs) favs - p.id else favs + p.id; Store.favorites = favs }) {
                            Icon(Icons.Default.Star, "Favourite", tint = if (p.id in favs) WkMint else WkLine)
                        }
                        IconButton(onClick = { removeWithUndo(listOf(p)) }) { Icon(Icons.Default.Delete, "Delete", tint = WkMuted) }
                    }
                    HorizontalDivider(color = WkLine)
                }
            }
        }
        FloatingActionButton(onClick = { addDialog = true }, containerColor = WkMint, contentColor = WkBg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Default.Add, "Add") }
        if (undo.isNotEmpty()) L3UndoBar(undo.size, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 24.dp)) {
            undo.forEach { (i, x) -> list.add(i.coerceIn(0, list.size), x) }
            persist(); undo = emptyList()
        }
    }
    if (addDialog || incoming.value != null) {
        ImportDialog(incoming.value ?: "", onClose = { addDialog = false; incoming.value = null },
            onManual = { addDialog = false; incoming.value = null; tweakDialog = true }) { found, msg ->
            list.addAll(found); persist()
            AppLog.log("Imported ${found.size} server(s)")
            if (found.isNotEmpty()) onSelect(found.first().id)
            Toast.makeText(ctx, "Imported ${found.size} server(s)" + if (msg.isNotBlank()) "\n$msg" else "", Toast.LENGTH_LONG).show()
            addDialog = false; incoming.value = null
        }
    }
    if (createDialog) CreateFileDialog(list) { createDialog = false }
    if (tweakDialog) AddTweakDialog(onClose = { tweakDialog = false }) { np ->
        list.add(np); persist(); onSelect(np.id); AppLog.log("Added tweak: ${np.name}")
        Toast.makeText(ctx, "Tweak saved.", Toast.LENGTH_SHORT).show(); tweakDialog = false
    }
    actionFor?.let { p ->
        ServerActionsDialog(p, onClose = { actionFor = null },
            onRename = { renameFor = p },
            onDuplicate = { list.add(p.copy(id = UUID.randomUUID().toString(), name = p.name + " copy")); persist() },
            onCopy = { copyCode(ctx, WkFile.encode(listOf(p), 0L, "", false, null)) },
            onQr = { qrText = WkFile.encode(listOf(p), 0L, "", false, null) },
            onResetUsage = { Store.resetUsed(p.id); persist() },
            onDelete = { removeWithUndo(listOf(p)) })
    }
    renameFor?.let { p ->
        RenameDialog(p, onClose = { renameFor = null }) { n ->
            val i = list.indexOfFirst { it.id == p.id }
            if (i >= 0) { list[i] = p.copy(name = n); persist() }
        }
    }
    qrText?.let { QrDialog(it) { qrText = null } }
}

@Composable
fun ImportDialog(initial: String, onClose: () -> Unit, onManual: () -> Unit, onDone: (List<Profile>, String) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(initial) }
    var pass by remember { mutableStateOf("") }
    var needPass by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { text = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText().orEmpty(); err = "" }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard,
        title = { Text("Add server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it; err = "" }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                    placeholder = { Text("vless://…  vmess://…  trojan://…  ss://…\nhttps://… (config link, can be updated later)\nssh://user:pass@host:22?sni=example.com\nslowdns://user:pass@ns.example.com?key=…\nwk1:… (shared file code)\n[Interface] … (WireGuard)") })
                if (needPass) OutlinedTextField(pass, { pass = it; err = "" }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("File password") })
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
                TextButton(onClick = {
                    val t = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    if (t.isNotBlank()) { text = t; err = "" } else err = "The clipboard is empty."
                }) { Text("Paste from clipboard") }
                TextButton(onClick = { picker.launch("*/*") }) { Text("Import from file") }
                TextButton(onClick = onManual) { Text("Create a tweak manually") }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                fun handle(r: ImportResult, src: String) {
                    when (r) {
                        is ImportResult.Ok -> onDone(r.list.map { it.copy(sourceUrl = src) }, r.msg)
                        is ImportResult.NeedPassword -> { needPass = true; err = "This file is password protected." }
                        is ImportResult.Fail -> err = r.why
                    }
                }
                val t = text.trim()
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    busy = true; err = ""
                    scope.launch {
                        val body = withContext(Dispatchers.IO) { runCatching { Importer.fetch(t) } }
                        busy = false
                        body.onSuccess { handle(Importer.run(it, pass.ifBlank { null }), t) }
                        body.onFailure { err = "Couldn't download that link: ${it.message}" }
                    }
                } else handle(Importer.run(text, pass.ifBlank { null }), "")
            }) { Text(if (busy) "Downloading…" else "Save") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } }
    )
}

@Composable
fun CreateFileDialog(all: List<Profile>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val usable = all.filter { !it.locked }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var days by remember { mutableIntStateOf(7) }
    var note by remember { mutableStateOf("") }
    var lock by remember { mutableStateOf(true) }
    var pass by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var device by remember { mutableStateOf("") }
    var dataMb by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }

    fun build(): String? {
        val sel = usable.filter { it.id in picked }
        if (sel.isEmpty()) { err = "Pick at least one server."; return null }
        val exp = if (days > 0) Clock.now() + days * 86_400_000L else 0L
        val code = WkFile.encode(sel, exp, note.trim(), lock, pass.trim().ifEmpty { null }, device.trim(), dataMb.toLongOrNull() ?: 0L)
        AppLog.log("Share file created (${sel.size} server(s)" + (if (days > 0) ", expires in $days d" else "") + (if (lock) ", locked" else "") + ")")
        return code
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard,
        title = { Text("Create share file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(fileName, { fileName = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("File name (e.g. Whitekid-Promo)") }, suffix = { Text(".wkp") })
                if (usable.isEmpty()) Text("Add a server first. Locked servers can't be shared again.", color = WkMuted)
                usable.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { picked = if (p.id in picked) picked - p.id else picked + p.id; err = "" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p.id in picked, { picked = if (it) picked + p.id else picked - p.id; err = "" })
                        Text("${p.name}  (${p.proto.label})", maxLines = 1)
                    }
                }
                Text("Expires after", color = WkMuted, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "1 day", 3 to "3 days", 7 to "7 days", 30 to "30 days", 0 to "No expiry").forEach { (d, l) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) })
                    }
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note for the user (optional)") })
                OutlinedTextField(device, { device = it.uppercase() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Lock to device ID (optional)") })
                OutlinedTextField(dataMb, { dataMb = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Data limit in MB (optional)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock details"); Text("Hides host, SNI and keys inside the app", color = WkMuted, fontSize = 12.sp)
                    }
                    Switch(lock, { lock = it })
                }
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Password (optional)") })
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
            }
        },
        confirmButton = { Button(onClick = { build()?.let { shareFile(ctx, it, fileName) } }) { Text("Share file") } },
        dismissButton = {
            Row {
                TextButton(onClick = { build()?.let { copyCode(ctx, it) } }) { Text("Copy code") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    )
}

fun shareFile(ctx: Context, code: String, name: String) {
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val clean = name.trim().removeSuffix(".wkp").replace(Regex("[^A-Za-z0-9._ -]"), "").trim().replace(' ', '-')
        .ifEmpty { "Whitekid-${System.currentTimeMillis() / 1000}" }
    val f = File(dir, "$clean.wkp").apply { writeText(code) }
    val uri = FileProvider.getUriForFile(ctx, "zw.whitekid.vpn.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share config file"))
}

fun copyCode(ctx: Context, code: String) {
    Store.lastClip = code.trim().hashCode()
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Whitekid code", code))
    Toast.makeText(ctx, "Code copied. Paste it in Telegram or WhatsApp.", Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------- Tools
@Composable
fun ToolsScreen(profiles: MutableList<Profile>, exit: () -> Unit) {
    val ctx = LocalContext.current
    var dns by remember { mutableStateOf(Store.dns) }
    var split by remember { mutableStateOf(Store.splitEnabled) }
    var apps by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(Store.autoReconnect) }
    var autoConn by remember { mutableStateOf(Store.autoConnect) }
    var boot by remember { mutableStateOf(Store.connectOnBoot) }
    var keepA by remember { mutableStateOf(Store.keepAwake) }
    var haptic by remember { mutableStateOf(Store.haptics) }
    var secure by remember { mutableStateOf(Store.secureScreen) }
    var sleep by remember { mutableIntStateOf(Store.sleepMinutes) }
    var pinDlg by remember { mutableStateOf(false) }
    var themeDlg by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var mux by remember { mutableStateOf(Store.mux) }
    var stay by remember { mutableStateOf(Store.stayAlive) }
    var failover by remember { mutableStateOf(Store.failover) }
    var doh by remember { mutableStateOf(Store.doh) }
    var v6 by remember { mutableStateOf(Store.blockIpv6) }
    var autoDns by remember { mutableStateOf(Store.autoDns) }
    var confirmD by remember { mutableStateOf(Store.confirmDisconnect) }
    var splitOnly by remember { mutableStateOf(Store.splitOnly) }
    var doctor by remember { mutableStateOf(false) }
    var kill by remember { mutableStateOf(Store.killSwitch) }
    var pingDlg by remember { mutableStateOf(false) }
    var adBlock by remember { mutableStateOf(Store.adBlock) }
    var autoNet by remember { mutableIntStateOf(Store.autoNet) }
    var alertMb by remember { mutableIntStateOf(Store.alertMb) }
    val tstatus by VpnState.status.collectAsState()
    var bench by remember { mutableStateOf<List<DnsBench.Result>>(emptyList()) }
    var benching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Text("TOOLS", fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)

        Section("Custom DNS") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Store.DNS_PRESETS.forEach { (n, v) ->
                    FilterChip(selected = dns == v, onClick = { dns = v; Store.dns = v }, label = { Text(n) })
                }
            }
            OutlinedTextField(dns, { dns = it; Store.dns = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Servers, comma separated") })
            OutlinedButton(onClick = {
                if (!benching) {
                    benching = true
                    scope.launch {
                        bench = withContext(Dispatchers.IO) { DnsBench.run() }
                        benching = false
                        val best = bench.firstOrNull()
                        if (best != null) {
                            dns = best.servers; Store.dns = best.servers
                            AppLog.log("Fastest DNS: ${best.name} (${best.ms} ms)")
                            Toast.makeText(ctx, "Fastest: ${best.name} (${best.ms} ms). Applied.", Toast.LENGTH_LONG).show()
                        } else Toast.makeText(ctx, "No DNS server answered. Check your internet.", Toast.LENGTH_LONG).show()
                    }
                }
            }, Modifier.fillMaxWidth()) { Text(if (benching) "Testing resolvers…" else "Find fastest DNS") }
            if (bench.isNotEmpty()) Text(bench.joinToString("\n") { "${it.name}: ${it.ms} ms" }, color = WkMuted, fontSize = 12.sp)
            ToggleRow("Pick the fastest DNS on every connect", "Tests the resolvers in the background before connecting", autoDns) { autoDns = it; Store.autoDns = it }
            ToggleRow("Block ads and trackers", "Uses AdGuard DNS. Applies on the next connect", adBlock) { adBlock = it; Store.adBlock = it }
            ToggleRow("Secure DNS (DoH)", "Encrypts lookups for Google, Cloudflare and Quad9", doh) { doh = it; Store.doh = it }
            ToggleRow("Block IPv6 leaks", "Stops IPv6 traffic going around the tunnel. Turn it off if some sites stop loading", v6) { v6 = it; Store.blockIpv6 = it }
            Text("Backup resolvers are added automatically, so one slow DNS server can't freeze lookups.", color = WkMuted, fontSize = 12.sp)
        }

        Section("Split tunneling") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Split tunneling"); Text(if (splitOnly) "${Store.bypassApps.size} apps use the VPN, everything else goes direct" else "${Store.bypassApps.size} apps skip the VPN", color = WkMuted, fontSize = 12.sp)
                }
                Switch(split, { split = it; Store.splitEnabled = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = !splitOnly, onClick = { splitOnly = false; Store.splitOnly = false }, label = { Text("These apps bypass the VPN") })
                FilterChip(selected = splitOnly, onClick = { splitOnly = true; Store.splitOnly = true }, label = { Text("Only these apps use the VPN") })
            }
            OutlinedButton(onClick = { apps = true }, enabled = split) { Text("Choose apps") }
        }

        Section("Connection") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-reconnect"); Text("Keeps you connected: retries after any drop and waits while the phone is offline", color = WkMuted, fontSize = 12.sp)
                }
                Switch(auto, { auto = it; Store.autoReconnect = it })
            }
            ToggleRow("Kill switch", "If the tunnel drops, block all traffic until it is back (no leaks). Applies on the next connect", kill) { kill = it; Store.killSwitch = it }
            ToggleRow("Smart server failover", "If a server keeps failing, switch to the next one in the same network", failover) { failover = it; Store.failover = it }
            ToggleRow("Stay-alive lock", "Keeps the CPU and Wi-Fi awake while connected so Android can't freeze the tunnel", stay) { stay = it; Store.stayAlive = it }
            ToggleRow("Confirm before disconnecting", "Protects you from an accidental tap", confirmD) { confirmD = it; Store.confirmDisconnect = it }
            OutlinedButton(onClick = {
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) Toast.makeText(ctx, "Already allowed to run in the background", Toast.LENGTH_SHORT).show()
                else runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, Modifier.fillMaxWidth()) { Text("Allow background running (battery)") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                Modifier.fillMaxWidth()) { Text("Kill switch (Android VPN settings)") }
            Text("Open it, tap the gear next to Whitekid VPN, then turn on Always-on VPN and Block connections without VPN.", color = WkMuted, fontSize = 12.sp)
        }

        Section("This device") {
            Text("Device ID: ${Store.deviceId}", fontWeight = FontWeight.Bold)
            Text("Send this ID to whoever makes your config file so it can be locked to your phone.", color = WkMuted, fontSize = 12.sp)
            OutlinedButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Device ID", Store.deviceId))
                Toast.makeText(ctx, "Device ID copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy device ID") }
        }

        Section("Startup and comfort") {
            ToggleRow("Connect when the app opens", "Uses your selected server", autoConn) { autoConn = it; Store.autoConnect = it }
            ToggleRow("Connect after phone restarts", "Needs VPN permission already granted", boot) { boot = it; Store.connectOnBoot = it }
            Text("Auto-connect (app open / restart) only on:", color = WkMuted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Any network", 1 to "Mobile data", 2 to "Wi-Fi").forEach { (m, l) ->
                    FilterChip(selected = autoNet == m, onClick = { autoNet = m; Store.autoNet = m }, label = { Text(l) })
                }
            }
            ToggleRow("Keep screen on while connected", "", keepA) { keepA = it; Store.keepAwake = it }
            ToggleRow("Vibrate on connect and disconnect", "", haptic) { haptic = it; Store.haptics = it }
            Text("Sleep timer: disconnect after", color = WkMuted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (m, l) ->
                    FilterChip(selected = sleep == m, onClick = { sleep = m; Store.sleepMinutes = m }, label = { Text(l) })
                }
            }
            Text("Tip: add the Whitekid VPN tile to your Quick Settings panel for one-tap connect.", color = WkMuted, fontSize = 12.sp)
        }

        Section("Performance") {
            ToggleRow("Multiplexing (mux)", "Can speed up VLESS, VMess and Trojan on some servers", mux) { mux = it; Store.mux = it }
        }

        Section("Appearance and security") {
            OutlinedButton(onClick = { themeDlg = true }, Modifier.fillMaxWidth()) { Text("Themes, light and dark mode") }
            OutlinedButton(onClick = { pinDlg = true }, Modifier.fillMaxWidth()) { Text(if (Store.pinHash.isEmpty()) "Set an app PIN" else "Change or remove app PIN") }
            ToggleRow("Block screenshots", "Also hides the app in recent apps", secure) { v ->
                secure = v; Store.secureScreen = v
                ctx.findActivity()?.window?.let { w ->
                    if (v) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        Section("Backup and data") {
            OutlinedButton(onClick = {
                val usable = profiles.filter { !it.locked }
                if (usable.isEmpty()) Toast.makeText(ctx, "No servers to back up.", Toast.LENGTH_SHORT).show()
                else shareFile(ctx, WkFile.encode(usable, 0L, "Backup", false, null), "Whitekid-backup")
            }, Modifier.fillMaxWidth()) { Text("Back up my servers") }
            Text("To restore: Servers → + → Import from file.", color = WkMuted, fontSize = 12.sp)
            OutlinedButton(onClick = { confirmReset = true }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WkRed)) { Text("Reset the app (delete all data)") }
        }

        Section("Data usage") {
            Text("Today: ${bytes(Store.usedToday())}", fontWeight = FontWeight.SemiBold)
            Text("This month: ${bytes(Store.usedMonth())}", fontWeight = FontWeight.SemiBold)
            val days = Store.lastDays(7)
            val top = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
            days.forEach { (d, b) ->
                val n = (b * 12 / top).toInt()
                Text(d.padEnd(4) + "█".repeat(n) + "░".repeat(12 - n) + "  " + bytes(b), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Text("Warn me when a day passes:", color = WkMuted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 500 to "500 MB", 1000 to "1 GB", 2000 to "2 GB", 5000 to "5 GB").forEach { (m, l) ->
                    FilterChip(selected = alertMb == m, onClick = { alertMb = m; Store.alertMb = m; Store.alertDay = "" }, label = { Text(l) })
                }
            }
            Text("Counted while the VPN is connected.", color = WkMuted, fontSize = 12.sp)
        }

        Section("Diagnostics") {
            OutlinedButton(onClick = { doctor = true }, Modifier.fillMaxWidth()) { Text("Connection doctor") }
            Text("Checks your network, DNS and the selected server and tells you what is wrong.", color = WkMuted, fontSize = 12.sp)
            OutlinedButton(onClick = { pingDlg = true }, Modifier.fillMaxWidth()) { Text("Ping tool") }
            OutlinedButton(onClick = { logs = true }, Modifier.fillMaxWidth()) { Text("View connection logs") }
            OutlinedButton(onClick = {
                val cur = profiles.firstOrNull { it.id == Store.selectedId } ?: profiles.firstOrNull()
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, buildReport(ctx, cur)), "Send support report"))
            }, Modifier.fillMaxWidth()) { Text("Send support report") }
            Text("The report has your app version, phone model, settings and the log. It never includes passwords or keys.", color = WkMuted, fontSize = 12.sp)
        }

        LabsSection(profiles)
        Labs2Section(profiles)

        Section("Community") {
            Button(onClick = { open(ctx, TELEGRAM) }, Modifier.fillMaxWidth()) { Text("Join Telegram channel") }
            OutlinedButton(onClick = { open(ctx, TELEGRAM) }, Modifier.fillMaxWidth()) { Text("Check for updates") }
            OutlinedButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "$APP_TITLE — get it on Telegram: $TELEGRAM"), "Share"))
            }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share app") }
        }

        OutlinedButton(onClick = { VpnController.disconnect(ctx); exit() }, Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WkRed)) { Text("Disconnect & exit") }
    }
    if (apps) AppPicker { apps = false }
    if (logs) LogsDialog { logs = false }
    if (pingDlg) PingToolDialog(profiles.firstOrNull { it.id == Store.selectedId } ?: profiles.firstOrNull(), tstatus == Status.CONNECTED) { pingDlg = false }
    if (doctor) DoctorDialog(profiles.firstOrNull { it.id == Store.selectedId } ?: profiles.firstOrNull()) { doctor = false }
    if (pinDlg) PinDialog { pinDlg = false }
    if (themeDlg) ThemesDialog { themeDlg = false }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, containerColor = WkCard, title = { Text("Reset the app?") },
        text = { Text("This deletes all servers, settings, logs and history, and closes the app.") },
        confirmButton = { Button(onClick = { VpnController.disconnect(ctx); Store.wipe(); AppLog.clear(); exit() }) { Text("Delete everything") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}

@Composable
fun LogsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lines by AppLog.lines.collectAsState()
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard, title = { Text("Connection logs") },
        text = {
            if (lines.isEmpty()) Text("No logs yet. Connect to a server and the steps will show up here.", color = WkMuted)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(lines.asReversed()) {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                        color = if (it.contains("ERROR")) WkRed else WkText, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Whitekid logs", AppLog.text()))
                    Toast.makeText(ctx, "Logs copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                TextButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, AppLog.text()), "Share logs"))
                }) { Text("Share") }
                TextButton(onClick = { AppLog.clear() }) { Text("Clear") }
            }
        }
    )
}

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (sub.isNotEmpty()) Text(sub, color = WkMuted, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun Section(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title.uppercase(), color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
        HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = body)
    }
}

@Composable
fun AppPicker(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var chosen by remember { mutableStateOf(Store.bypassApps) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != ctx.packageName }
                .map { it.loadLabel(pm).toString() to it.packageName }.sortedBy { it.first.lowercase() }
        }
    }
    AlertDialog(onDismissRequest = onClose, containerColor = WkCard, title = { Text(if (Store.splitOnly) "Apps that use the VPN" else "Apps that bypass VPN") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.second }) { (label, pkg) ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (pkg in chosen) chosen - pkg else chosen + pkg },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in chosen, { chosen = if (it) chosen + pkg else chosen - pkg }); Text(label, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { Button({ Store.bypassApps = chosen; onClose() }) { Text("Done") } })
}

// ---------------------------------------------------------------- About
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(150.dp).clip(RoundedCornerShape(32.dp)))
        Spacer(Modifier.height(16.dp))
        Text("WHITEKID", fontSize = 34.sp, fontWeight = FontWeight.Black, color = WkMint, letterSpacing = 8.sp)
        Text(APP_TITLE, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Version ${appVersion(ctx)} • by Whitekid Tech", color = WkMuted, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        Text("Protocols: VLESS, VMess, Trojan, Shadowsocks, WireGuard, SSH and SlowDNS, with SNI, custom DNS, split tunneling, device-locked expiring config files, data limits and auto-reconnect.",
            color = WkMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { open(ctx, TELEGRAM) }) { Text("t.me/whitekidtechzw") }
    }
}

fun open(c: Context, url: String) = c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
