package zw.whitekid.vpn.vpn

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import zw.whitekid.vpn.data.History
import zw.whitekid.vpn.data.Store

enum class Status { IDLE, CONNECTING, CONNECTED, ERROR }

/** "1.2 MB/s" style text for a speed in bytes per second. */
fun rateText(b: Long): String = when {
    b >= 1_000_000L -> "%.1f MB/s".format(b / 1_000_000.0)
    b >= 1_000L -> "%.0f KB/s".format(b / 1_000.0)
    else -> "$b B/s"
}

object VpnState {
    val status = MutableStateFlow(Status.IDLE)
    val message = MutableStateFlow("")
    val rxBytes = MutableStateFlow(0L)
    val txBytes = MutableStateFlow(0L)
    val seconds = MutableStateFlow(0L)
    /** live speed in bytes per second */
    val rxSpeed = MutableStateFlow(0L)
    val txSpeed = MutableStateFlow(0L)
    /** what the app is doing right now while CONNECTING ("Logging in…", "Waiting for network…") */
    val step = MutableStateFlow("")
    /** round trip through the tunnel in ms, -1 = unknown */
    val pingMs = MutableStateFlow(-1)
    /** how many times this session was restored automatically, and why the last drop happened */
    val reconnects = MutableStateFlow(0)
    val lastDrop = MutableStateFlow("")
    /** "ZA, Johannesburg • 1.2.3.4" once known */
    val exitInfo = MutableStateFlow("")
    val dnsNote = MutableStateFlow("")
    /** the phone's real IP (looked up outside the tunnel) and the verdict after comparing it with the exit IP */
    val realIp = MutableStateFlow("")
    val protection = MutableStateFlow("")
    /** last 60 seconds of total speed (bytes/s) for the live graph */
    val speedHistory = MutableStateFlow<List<Long>>(emptyList())
    /** keep-alive results: how many answered and failed, and the average answer time */
    val pingOk = MutableStateFlow(0)
    val pingFail = MutableStateFlow(0)
    val pingAvg = MutableStateFlow(0)
    private var pingSum = 0L

    fun recordPing(ms: Int?) {
        if (ms == null) { pingFail.value = pingFail.value + 1; return }
        pingSum += ms; pingOk.value = pingOk.value + 1; pingAvg.value = (pingSum / pingOk.value).toInt()
    }
    fun resetLink() { pingSum = 0; pingOk.value = 0; pingFail.value = 0; pingAvg.value = 0 }

    @Volatile var activeId = ""
    @Volatile var limitBytes = 0L
    @Volatile var onLimit: (() -> Unit)? = null
    private var lastTotal = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var meter: Job? = null

    fun set(s: Status, msg: String = "") {
        // a session ends only when the user disconnects or the attempt fails for good; reconnecting keeps the timer running
        if ((s == Status.IDLE || s == Status.ERROR) && meter != null && seconds.value > 0) {
            History.add(History.Entry(message.value, System.currentTimeMillis() - seconds.value * 1000, seconds.value, rxBytes.value, txBytes.value))
        }
        if (s != Status.CONNECTED) { dnsNote.value = ""; exitInfo.value = ""; pingMs.value = -1; protection.value = "" }
        if (s == Status.IDLE || s == Status.ERROR) step.value = ""
        status.value = s; message.value = msg
        if (s == Status.CONNECTED) startMeter() else if (s == Status.IDLE || s == Status.ERROR) stopMeter()
    }

    private fun startMeter() {
        if (meter?.isActive == true) return
        val uid = Process.myUid()
        val rx0 = TrafficStats.getUidRxBytes(uid); val tx0 = TrafficStats.getUidTxBytes(uid)
        val t0 = System.currentTimeMillis()
        lastTotal = 0L
        meter = scope.launch {
            var lastRx = 0L; var lastTx = 0L
            while (isActive) {
                val r = (TrafficStats.getUidRxBytes(uid) - rx0).coerceAtLeast(0)
                val t = (TrafficStats.getUidTxBytes(uid) - tx0).coerceAtLeast(0)
                rxBytes.value = r; txBytes.value = t
                rxSpeed.value = (r - lastRx).coerceAtLeast(0); txSpeed.value = (t - lastTx).coerceAtLeast(0)
                lastRx = r; lastTx = t
                speedHistory.value = (speedHistory.value + (rxSpeed.value + txSpeed.value)).takeLast(60)
                val total = r + t
                val delta = (total - lastTotal).coerceAtLeast(0); lastTotal = total
                val id = activeId
                if (delta > 0) Store.addDaily(delta)
                if (id.isNotEmpty() && delta > 0) {
                    Store.addUsed(id, delta)
                    if (limitBytes > 0 && Store.usedBytes(id) >= limitBytes) onLimit?.invoke()
                }
                seconds.value = (System.currentTimeMillis() - t0) / 1000
                delay(1000)
            }
        }
    }

    private fun stopMeter() {
        meter?.cancel(); meter = null
        rxBytes.value = 0; txBytes.value = 0; seconds.value = 0; rxSpeed.value = 0; txSpeed.value = 0
        speedHistory.value = emptyList(); resetLink()
    }
}
