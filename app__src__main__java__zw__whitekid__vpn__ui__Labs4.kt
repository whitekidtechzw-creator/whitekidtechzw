package zw.whitekid.vpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.data.WkFile
import zw.whitekid.vpn.vpn.Status
import zw.whitekid.vpn.vpn.VpnController
import zw.whitekid.vpn.vpn.VpnState
import zw.whitekid.vpn.vpn.rateText

/**
 * v1.12.0 "Cockpit II": more features for the Connect and Servers screens.
 * UI only. The DNS fix lives in DnsCheck.kt and WhitekidVpnService.kt.
 */

@Composable
private fun l4Title(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
    HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
}

// ---------------------------------------------------------------- Connect screen

/** C1: DNS health. RETEST DNS plus one-tap switch to Google, Cloudflare or Quad9 (rebuilds the tunnel to apply it). */
@Composable
fun L4DnsPanel(status: Status) {
    if (status != Status.CONNECTED) return
    val note by VpnState.dnsNote.collectAsState()
    var active by remember { mutableStateOf(Store.dns) }
    val ctx = LocalContext.current
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { VpnController.retestDns() }) { Text("RETEST DNS", letterSpacing = 1.sp, fontSize = 12.sp) }
            listOf("Google" to Store.GOOGLE_DNS, "Cloudflare" to "1.1.1.1,1.0.0.1", "Quad9" to "9.9.9.9,149.112.112.112").forEach { (n, v) ->
                FilterChip(selected = Store.customDns && !Store.adBlock && active == v, onClick = {
                    active = v
                    VpnController.switchDns(v)
                    Toast.makeText(ctx, "DNS set to $n. Reconnecting to apply it…", Toast.LENGTH_SHORT).show()
                }, label = { Text(n) })
            }
        }
        if (note.startsWith("⚠")) Text("Browsing can still work: the app also uses backup resolvers. Tap Google to reset DNS.", color = WkMuted, fontSize = 11.sp)
    }
}

/** C2: one-tap switches for the protections people change most. */
@Composable
fun L4QuickToggles() {
    var kill by remember { mutableStateOf(Store.killSwitch) }
    var auto by remember { mutableStateOf(Store.autoReconnect) }
    var v6 by remember { mutableStateOf(Store.blockIpv6) }
    var doh by remember { mutableStateOf(Store.doh) }
    l4Title("QUICK SWITCHES")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = kill, onClick = { kill = !kill; Store.killSwitch = kill }, label = { Text("Kill switch") })
        FilterChip(selected = auto, onClick = { auto = !auto; Store.autoReconnect = auto }, label = { Text("Auto-reconnect") })
        FilterChip(selected = v6, onClick = { v6 = !v6; Store.blockIpv6 = v6 }, label = { Text("Block IPv6") })
        FilterChip(selected = doh, onClick = { doh = !doh; Store.doh = doh }, label = { Text("Secure DNS") })
    }
    Text("Changes apply on the next connect.", color = WkMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

/** C3: keeps the screen on while you watch the dial (screen only, never the tunnel). */
@Composable
fun L4KeepScreenOn(status: Status) {
    val view = LocalView.current
    var keep by remember { mutableStateOf(false) }
    DisposableEffect(keep, status) {
        view.keepScreenOn = keep && status == Status.CONNECTED
        onDispose { view.keepScreenOn = false }
    }
    if (status == Status.CONNECTED) {
        FilterChip(selected = keep, onClick = { keep = !keep }, label = { Text("Keep screen on") }, modifier = Modifier.padding(top = 4.dp))
    }
}

/** C4: last 7 days of data as small bars. */
@Composable
fun L4WeekBars(today: Long) {
    val key = today / 500_000
    val days = remember(key) { Store.lastDaysDated(7) }
    val top = days.maxOfOrNull { it.second } ?: 0L
    if (top <= 0L) return
    l4Title("LAST 7 DAYS")
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        days.forEach { (_, v) ->
            val f = (v.toFloat() / top.toFloat()).coerceIn(0.04f, 1f)
            Box(Modifier.weight(1f).fillMaxHeight(f).background(if (v == days.last().second) WkMint else WkLine))
        }
    }
    val total = days.sumOf { it.second }
    Text("Total ${bytes(total)}  •  average ${bytes(total / 7)} per day  •  best day ${bytes(top)}", color = WkMuted, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
}

/** C5: quality score from 0 to 100 built from ping, lost packets and restores. */
@Composable
fun L4Score(status: Status, p: Profile?, ping: Int, okN: Int, badN: Int, reconns: Int) {
    if (status != Status.CONNECTED || ping < 0) return
    val slow = p?.proto == Proto.SLOWDNS
    val k = if (slow) 2.5 else 1.0
    val lossPct = if (okN + badN > 0) badN * 100 / (okN + badN) else 0
    var s = 100.0
    s -= (ping / k / 12.0).coerceAtMost(45.0)
    s -= (lossPct * 2.0).coerceAtMost(40.0)
    s -= (reconns * 4.0).coerceAtMost(15.0)
    val score = s.toInt().coerceIn(0, 100)
    val c = if (score >= 70) WkOk else if (score >= 40) WkViolet else WkRed
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SCORE  ", color = WkMuted, fontSize = 11.sp, letterSpacing = 1.sp)
        Text("$score / 100", color = c, fontWeight = FontWeight.Black, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
    LinearProgressIndicator(progress = { score / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp), color = c, trackColor = WkLine)
}

/** C6: copy the exit IP, share it, or copy a full diagnostic report for support. */
@Composable
fun L4Actions(status: Status, p: Profile?, exit: String, dnsNote: String) {
    if (status != Status.CONNECTED) return
    val ctx = LocalContext.current
    val ping = VpnState.pingMs.value
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            val ip = exit.substringAfterLast("• ").trim()
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Exit IP", ip))
            Toast.makeText(ctx, "IP copied", Toast.LENGTH_SHORT).show()
        }, enabled = exit.isNotBlank()) { Text("COPY IP", letterSpacing = 1.sp, fontSize = 12.sp) }
        TextButton(onClick = {
            val rep = buildString {
                append("Whitekid VPN report\n")
                append("Server type: ${p?.proto?.label ?: "?"}\n")
                append("Ping: ${if (ping >= 0) "$ping ms" else "unknown"}\n")
                append("DNS: ${dnsNote.removePrefix("⚠ ")}\n")
                append("Exit: ${exit.ifBlank { "unknown" }}\n")
                append("Restores: ${VpnState.reconnects.value}\n\nLast log lines:\n")
                AppLog.lines.value.takeLast(12).forEach { append(it).append('\n') }
            }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Report", rep))
            Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show()
        }) { Text("COPY REPORT", letterSpacing = 1.sp, fontSize = 12.sp) }
    }
}

/** C7: daily data alert as chips (same setting as Tools). */
@Composable
fun L4AlertChips() {
    var mb by remember { mutableIntStateOf(Store.alertMb) }
    l4Title("DAILY DATA ALERT")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Off", 100 to "100 MB", 250 to "250 MB", 500 to "500 MB", 1000 to "1 GB", 2000 to "2 GB").forEach { (v, l) ->
            FilterChip(selected = mb == v, onClick = { mb = v; Store.alertMb = v }, label = { Text(l) })
        }
    }
}

// ---------------------------------------------------------------- Servers screen

/** S1: quick facts about the whole list. */
@Composable
fun L4ListFacts(list: List<Profile>, pings: Map<String, Int>) {
    if (list.isEmpty()) return
    val ok = list.mapNotNull { pings[it.id]?.takeIf { ms -> ms >= 0 } }
    val avg = if (ok.isEmpty()) "—" else "${ok.sum() / ok.size} ms"
    val soon = list.count { it.expires > 0 && !it.expired() && it.expires - zw.whitekid.vpn.data.Clock.now() < 259_200_000L }
    val used = list.sumOf { Store.usedBytes(it.id) }
    Text("average ping $avg  •  $soon expiring soon  •  ${bytes(used)} used in total", color = WkMuted, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
}

/** S2: servers that did not answer the last ping test. */
fun l4DeadServers(list: List<Profile>, pings: Map<String, Int>): List<Profile> =
    list.filter { (pings[it.id] ?: 0) == -1 }

/** S3: paste a link straight from the clipboard into the Add dialog. */
@Composable
fun L4PasteButton(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        val t = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (t.isBlank()) Toast.makeText(ctx, "The clipboard is empty.", Toast.LENGTH_SHORT).show() else onText(t)
    }) { Text("Paste link") }
}

/** S4: copy every visible server as one backup code, or share it. */
@Composable
fun L4BackupButtons(list: List<Profile>) {
    val ctx = LocalContext.current
    if (list.isEmpty()) return
    OutlinedButton(onClick = {
        val code = WkFile.encode(list, 0L, "", false, null)
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Backup", code))
        Toast.makeText(ctx, "Backup of ${list.size} server(s) copied", Toast.LENGTH_SHORT).show()
    }) { Text("Copy backup") }
}

/** S5: pick a random reachable server (handy when one network is busy). */
fun l4Random(list: List<Profile>, pings: Map<String, Int>): Profile? =
    list.filter { !it.expired() && (pings[it.id] ?: -2) >= 0 }.randomOrNull()

/** S6: how much data this server carried in total. */
@Composable
fun L4UsedLine(p: Profile) {
    val u = Store.usedBytes(p.id)
    if (u > 0L) Text("Used ${bytes(u)} so far", color = WkMuted, fontSize = 11.sp)
}
