package zw.whitekid.vpn.ui

import android.content.Intent
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zw.whitekid.vpn.data.Clock
import zw.whitekid.vpn.data.History
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.vpn.Pinger
import zw.whitekid.vpn.vpn.Status
import zw.whitekid.vpn.vpn.rateText

/**
 * v1.11.0: new pieces for the Connect screen (HomeScreen) and the Servers screen.
 * Everything here is UI only: no changes to the tunnel or the service.
 */

private fun l3Dur(s: Long): String = when {
    s >= 3600 -> "%dh %02dm".format(s / 3600, s / 60 % 60)
    s >= 60 -> "%d min".format(s / 60)
    else -> "$s s"
}

/** Measures how long the last connect took (CONNECTING to CONNECTED). */
object L3Timer {
    var t0 = 0L
    var tookMs = 0L
    var connectedAt = 0L
}

@Composable
fun L3ConnectTimerEffect(status: Status) {
    LaunchedEffect(status) {
        val now = System.currentTimeMillis()
        if (status == Status.CONNECTING) {
            if (L3Timer.t0 == 0L) L3Timer.t0 = now
        } else if (status == Status.CONNECTED) {
            if (L3Timer.t0 > 0L) { L3Timer.tookMs = now - L3Timer.t0; L3Timer.connectedAt = now; L3Timer.t0 = 0L }
        } else {
            L3Timer.t0 = 0L
        }
    }
}

// ---------------------------------------------------------------- Connect screen
/** C4: warns when the selected server is about to expire, expired, or nearly out of data. */
@Composable
fun L3Banner(p: Profile?) {
    if (p == null) return
    var text = ""
    var severe = false
    if (p.expires > 0) {
        val left = p.expires - Clock.now()
        if (left <= 0) { text = "This server has expired."; severe = true }
        else if (left < 259_200_000L) text = "Heads up: " + (p.expiryText()?.lowercase() ?: "expires soon") + "."
    }
    if (p.dataLimitMb > 0) {
        val pct = Store.usedBytes(p.id) * 100 / (p.dataLimitMb * 1_000_000L)
        if (pct >= 100) { text = "Data limit reached for this server."; severe = true }
        else if (pct >= 80 && !severe) text = if (text.isEmpty()) "$pct% of this server's data is used." else "$text  $pct% of its data is used."
    }
    if (text.isEmpty()) return
    val c = if (severe) WkRed else WkViolet
    Box(Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, c).padding(10.dp)) {
        Text(text, color = c, fontSize = 12.sp)
    }
}

/** C3 and C9: yesterday vs today, and the monthly budget bar set in Labs II. */
@Composable
fun L3Usage(today: Long) {
    val key = today / 200_000
    val yesterday = remember(key) { Store.lastDaysDated(2).firstOrNull()?.second ?: 0L }
    val month = remember(key) { Store.usedMonth() }
    val budgetMb = Store.extraLong("budgetMb")
    if (yesterday <= 0L && budgetMb <= 0L) return
    Column(Modifier.padding(top = 10.dp)) {
        if (yesterday > 0L) {
            val pct = (today - yesterday) * 100 / yesterday
            val arrow = if (pct >= 0) "▲ $pct%" else "▼ ${-pct}%"
            Text("YESTERDAY ${bytes(yesterday)}  •  TODAY $arrow", color = WkMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        if (budgetMb > 0L) {
            val b = budgetMb * 1_048_576L
            val frac = (month.toFloat() / b.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                color = if (frac > 0.9f) WkRed else if (frac > 0.7f) WkViolet else WkMint, trackColor = WkLine)
            Text("MONTH ${bytes(month)} OF ${bytes(b)} (${(frac * 100).toInt()}%)", color = WkMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** C1, C2, C7, C8, C10: session averages, link verdict, ping trend, share, and the last-session card. */
@Composable
fun L3Insights(status: Status, p: Profile?, ping: Int, rx: Long, tx: Long, secs: Long, graph: List<Long>, okN: Int, badN: Int) {
    val ctx = LocalContext.current
    val trend = remember { mutableStateListOf<Int>() }
    LaunchedEffect(secs) {
        if (status == Status.CONNECTED && ping >= 0) {
            trend.add(ping)
            while (trend.size > 30) trend.removeAt(0)
        } else if (status != Status.CONNECTED && trend.isNotEmpty()) {
            trend.clear()
        }
    }
    if (status == Status.CONNECTED) {
        val avgRate = (rx + tx) / maxOf(secs, 1L)
        val peak = graph.maxOrNull() ?: 0L
        val fresh = L3Timer.tookMs > 0 && Math.abs((System.currentTimeMillis() - secs * 1000) - L3Timer.connectedAt) < 8000
        Row(Modifier.fillMaxWidth()) {
            Readout("AVG SPEED", rateText(avgRate), Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(52.dp).background(WkLine))
            Readout("PEAK", rateText(peak), Modifier.weight(1f).padding(start = 14.dp))
            Box(Modifier.width(1.dp).height(52.dp).background(WkLine))
            Readout("CONNECTED IN", if (fresh) "%.1f s".format(L3Timer.tookMs / 1000.0) else "—", Modifier.weight(1f).padding(start = 14.dp))
        }
        HorizontalDivider(color = WkLine)
        val loss = if (okN + badN > 0) badN * 100 / (okN + badN) else 0
        val slow = p?.proto == Proto.SLOWDNS
        if (ping >= 0 || okN + badN > 0) {
            val (title, tip, col) = when {
                loss >= 20 -> Triple("POOR", "Many packets are getting lost. Try another server or a stronger signal.", WkRed)
                loss >= 8 || ping > (if (slow) 900 else 400) -> Triple("FAIR", "Slow or patchy. Tap FIND FASTEST in Route to try a better server.", WkViolet)
                ping > (if (slow) 500 else 150) -> Triple("GOOD", "Fine for chat and browsing.", WkOk)
                else -> Triple("EXCELLENT", "Smooth for calls and streaming.", WkOk)
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LINK QUALITY  ", color = WkMuted, fontSize = 11.sp, letterSpacing = 1.sp)
                Text(title, color = col, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
            Text(tip, color = WkMuted, fontSize = 12.sp)
        }
        if (trend.size >= 3) {
            val lo = trend.minOrNull() ?: 0
            val hi = trend.maxOrNull() ?: 0
            val span = (hi - lo).coerceAtLeast(1)
            val blocks = "▁▂▃▄▅▆▇█"
            val line = trend.joinToString("") { blocks[((it - lo) * 7 / span).coerceIn(0, 7)].toString() }
            Text("PING TREND  $line", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("best $lo ms  •  worst $hi ms", color = WkMuted, fontSize = 11.sp)
        }
        TextButton(onClick = {
            val txt = "Whitekid VPN session: ${l3Dur(secs)}, down ${bytes(rx)}, up ${bytes(tx)}, average ${rateText(avgRate)}" +
                (if (ping >= 0) ", ping $ping ms" else "")
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Share session stats"))
        }) { Text("SHARE STATS", letterSpacing = 1.sp, fontSize = 12.sp) }
    } else if (status == Status.IDLE) {
        val last = remember(status) { History.list().firstOrNull() }
        if (last != null) {
            Column(Modifier.padding(top = 12.dp)) {
                Text("LAST SESSION", color = WkMuted, fontSize = 11.sp, letterSpacing = 1.sp)
                Text("${last.name}  •  ${l3Dur(last.seconds)}  •  ${bytes(last.rx + last.tx)}", fontSize = 13.sp, maxLines = 1)
                Text("${Store.sessions} sessions so far  •  longest ${l3Dur(Store.longestSec)}", color = WkMuted, fontSize = 11.sp)
            }
        }
    }
}

/** C5: pings the servers of the chosen network and picks the quickest one. */
@Composable
fun L3FindFastest(candidates: List<Profile>, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !busy && candidates.isNotEmpty(), onClick = {
            busy = true; result = ""
            scope.launch {
                val res = withContext(Dispatchers.IO) { candidates.map { p -> async { p to Pinger.tcp(p) } }.awaitAll() }
                val best = res.filter { !it.first.expired() && it.second >= 0 }.minByOrNull { it.second }
                if (best != null) { onPick(best.first.id); result = "Picked ${best.first.name} (${best.second} ms)" }
                else result = "No reachable server"
                busy = false
            }
        }) { Text(if (busy) "TESTING…" else "FIND FASTEST", letterSpacing = 1.sp, fontSize = 12.sp) }
        if (result.isNotEmpty()) Text(result, color = WkMuted, fontSize = 11.sp, maxLines = 1)
    }
}

/** C6: sleep timer chips right on the Connect screen (same setting as Tools). */
@Composable
fun L3SleepChips() {
    var sleep by remember { mutableIntStateOf(Store.sleepMinutes) }
    Spacer(Modifier.height(18.dp))
    Text("SLEEP TIMER", color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
    HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours").forEach { (m, l) ->
            FilterChip(selected = sleep == m, onClick = { sleep = m; Store.sleepMinutes = m }, label = { Text(l) })
        }
    }
    Text("Disconnects by itself. Starts counting from the next connect.", color = WkMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
}

// ---------------------------------------------------------------- Servers screen
/** S7: thin progress bar for servers that have a data limit. */
@Composable
fun L3DataBar(used: Long, limitMb: Long) {
    val f = (used.toFloat() / (limitMb * 1_000_000f)).coerceIn(0f, 1f)
    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 8.dp).height(3.dp),
        color = if (f > 0.9f) WkRed else WkMint, trackColor = WkLine)
}

/** S3: small bar that lets you take back a delete. */
@Composable
fun L3UndoBar(count: Int, modifier: Modifier, onUndo: () -> Unit) {
    Row(modifier.background(WkCard).border(1.dp, WkLine).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Removed $count server(s)", fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onUndo) { Text("UNDO", color = WkMint, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
    }
}

/** S10: servers that are exact copies of an earlier one (the first copy is kept). */
fun l3DupeVictims(list: List<Profile>): List<Profile> {
    val seen = HashSet<String>()
    val out = ArrayList<Profile>()
    list.forEach { p ->
        val k = listOf(p.proto.name, p.host.lowercase(), p.port.toString(), p.user, p.secret, p.network, p.sni, p.path, p.raw.trim()).joinToString("|")
        if (!seen.add(k)) out.add(p)
    }
    return out
}
