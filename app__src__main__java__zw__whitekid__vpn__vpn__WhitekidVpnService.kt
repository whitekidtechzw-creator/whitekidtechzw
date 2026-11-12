package zw.whitekid.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import zw.whitekid.vpn.R
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.NetInfo
import zw.whitekid.vpn.data.RootCheck
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.ui.MainActivity
import kotlin.concurrent.thread

/**
 * Hosts the TUN for VLESS / VMess / Trojan / SSH / SlowDNS (all routed through the Xray core).
 * WireGuard uses WireGuardEngine/GoBackend instead.
 *
 * This service only builds and tears down the engines. VpnController supervises them: it decides when to
 * connect, retry or wait, and it never stops this service just because a connection dropped.
 *
 * NOTE: this app's own package is excluded from the VPN, so the core's and SSH's outbound sockets
 * never loop back into the tunnel (no protect() needed).
 */
class WhitekidVpnService : VpnService() {

    companion object {
        const val ACTION_START = "zw.whitekid.vpn.START"
        const val ACTION_STOP = "zw.whitekid.vpn.STOP"
        const val ACTION_RECONNECT = "zw.whitekid.vpn.RECONNECT"
        const val EXTRA_ID = "id"
        private const val CH = "wk_vpn"
        @Volatile var instance: WhitekidVpnService? = null
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var core: CoreController? = null
    @Volatile private var ssh: SshEngine? = null
    @Volatile private var dnstt: DnsttEngine? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { VpnController.disconnect(applicationContext); return START_NOT_STICKY }
            ACTION_RECONNECT -> { VpnController.reconnectNow(); return START_STICKY }
        }
        // a restart by Android after it killed the process has no intent: resume the server the user last wanted
        val id = intent?.getStringExtra(EXTRA_ID).orEmpty().ifEmpty { Store.wantedId }
        val profile = Store.profiles().firstOrNull { it.id == id }
        if (profile == null) { VpnState.set(Status.ERROR, "No server selected"); stopSelf(); return START_NOT_STICKY }
        startForeground(1, notification("Connecting: ${profile.name}"))
        if (!VpnController.isRunning()) VpnController.connect(applicationContext, profile, auto = true, fromService = true)
        return START_STICKY
    }

    /** Called by VpnController to stop the tunnel for good. */
    fun shutdown() {
        if (instance === this) instance = null
        stopEngines()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun step(s: String) { VpnState.step.value = s }

    /** Builds the whole tunnel (SSH / SlowDNS helpers, TUN, Xray core). Blocks until it runs; throws if it can't. */
    fun startEngines(p: Profile, attempt: Int) {
        stopEngines(keepTun = Store.killSwitch)
        acquireLocks()
        val dns = Store.dnsList()
        when (p.proto) {
            Proto.SSH -> {
                step("Logging in…")
                AppLog.log("SSH: connecting" + if (p.sni.isNotBlank() && !p.locked) " (TLS, SNI ${p.sni})" else "")
                val e = SshEngine(p) { true }; ssh = e; e.start()
                AppLog.log("SSH: session up")
            }
            Proto.SLOWDNS -> {
                step("Starting SlowDNS…")
                AppLog.log("SlowDNS: starting engine")
                val d = DnsttEngine(this, p); dnstt = d; d.start(attempt)
                step("Logging in over SlowDNS…")
                AppLog.log("SlowDNS: tunnel listening, logging in over SSH")
                val e = SshEngine(p.copy(host = "127.0.0.1", port = DnsttEngine.PORT, sni = "", proxy = "")) { true }; ssh = e; e.start()
                AppLog.log("SSH: session up")
            }
            else -> if (!DnsResolve.isIp(p.host)) step("Resolving server…")
        }

        step("Building tunnel…")
        AppLog.log("TUN: establishing (DNS ${dns.joinToString(",")})")
        // auto-heal: every retry changes the settings that most often break a tunnel (packet size, DNS mode, mux)
        val mtu = when (attempt % 3) { 0 -> 1400; 1 -> 1280; else -> 1500 }
        if (attempt > 0) AppLog.log("Auto-heal: try ${attempt + 1} uses packet size $mtu" + if (attempt >= 2) ", plain DNS, no mux" else "")
        val b = Builder().setSession("Whitekid VPN").setMtu(mtu)
            .addAddress("10.77.0.1", 30).addRoute("0.0.0.0", 0)
        if (Store.blockIpv6 && attempt == 0) runCatching { b.addAddress("fd77:77::1", 64); b.addRoute("::", 0) }   // IPv6 can't leak around the tunnel
        dns.forEach { runCatching { b.addDnsServer(it) } }
        applyApps(b)
        val fresh = b.establish() ?: error("VPN permission was not granted")
        val old = tun; tun = fresh; runCatching { old?.close() }

        step("Starting engine…")
        AppLog.log("Core: starting")
        Libv2ray.initCoreEnv(filesDir.absolutePath, "")
        val handler = object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        }
        core = startCore(handler, p, dns, mtu, attempt)
        AppLog.log("Core running: ${p.name}")
    }

    /** First try with Xray's built-in TUN; if this engine version refuses that config, retry the classic way. */
    private fun startCore(handler: CoreCallbackHandler, p: Profile, dns: List<String>, mtu: Int, heal: Int): CoreController {
        val first = Libv2ray.newCoreController(handler)
        try {
            first.startLoop(XrayConfig.build(p, dns, true, mtu, heal), tun!!.fd)
            AppLog.log("Core: started with the built-in TUN")
            return first
        } catch (e: Exception) {
            AppLog.log("Core: TUN mode refused (${e.message}); trying the classic mode")
            runCatching { first.stopLoop() }
        }
        val second = Libv2ray.newCoreController(handler)
        second.startLoop(XrayConfig.build(p, dns, false, mtu, heal), tun!!.fd)
        AppLog.log("Core: started in classic mode")
        return second
    }

    /** Takes every engine down but keeps the service itself (and its notification) alive for the next attempt. */
    fun stopEngines(keepTun: Boolean = false) {
        runCatching { core?.stopLoop() }; core = null
        runCatching { ssh?.stop() }; ssh = null
        runCatching { dnstt?.stop() }; dnstt = null
        if (!keepTun) { runCatching { tun?.close() }; tun = null }
        releaseLocks()
    }

    /** Which apps use the tunnel (split tunneling rules). Shared by the real tunnel and the kill-switch tunnel. */
    private fun applyApps(b: Builder) {
        var allowed = 0
        if (Store.splitEnabled && Store.splitOnly) Store.bypassApps.forEach { if (runCatching { b.addAllowedApplication(it) }.isSuccess) allowed++ }
        if (allowed == 0) {
            b.addDisallowedApplication(packageName)
            if (Store.splitEnabled && !Store.splitOnly) Store.bypassApps.forEach { runCatching { b.addDisallowedApplication(it) } }
        }
    }

    /**
     * Kill switch: a tunnel that carries nothing. While the real tunnel is being rebuilt, apps keep sending into it and
     * their traffic goes nowhere, instead of leaking out on the open network.
     */
    fun blackhole() {
        if (!Store.killSwitch) return
        runCatching {
            val b = Builder().setSession("Whitekid VPN (protecting)").setMtu(1400)
                .addAddress("10.77.0.1", 30).addRoute("0.0.0.0", 0)
            runCatching { b.addAddress("fd77:77::1", 64); b.addRoute("::", 0) }
            applyApps(b)
            val t = b.establish()
            if (t != null) { val old = tun; tun = t; runCatching { old?.close() } }
            AppLog.log("Kill switch: traffic is blocked until the VPN is back")
        }
    }

    /** false when the SlowDNS process or the SSH session has died (the watchdog then restarts the tunnel). */
    fun engineAlive(): Boolean = (dnstt?.isAlive() ?: true) && (ssh?.isAlive() ?: true)

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (!Store.stayAlive) return
        runCatching {
            if (wake?.isHeld != true) {
                val w = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whitekid:vpn")
                w.setReferenceCounted(false); w.acquire(); wake = w
            }
        }
        runCatching {
            if (wifi?.isHeld != true) {
                val l = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "whitekid:vpn")
                l.setReferenceCounted(false); l.acquire(); wifi = l
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wake?.isHeld == true) wake?.release() }; wake = null
        runCatching { if (wifi?.isHeld == true) wifi?.release() }; wifi = null
    }

    override fun onRevoke() { VpnController.disconnect(applicationContext) }
    override fun onDestroy() { if (instance === this) instance = null; stopEngines(); super.onDestroy() }

    /** Changes the text of the VPN notification (Connecting, Connected with live speed, Waiting for network). */
    fun updateNote(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(1, notification(text)) }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "VPN status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, WhitekidVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val again = PendingIntent.getService(this, 2,
            Intent(this, WhitekidVpnService::class.java).setAction(ACTION_RECONNECT), PendingIntent.FLAG_IMMUTABLE)
        val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_fg)
        return Notification.Builder(this, CH).setContentTitle("Whitekid VPN").setContentText(text)
            .setSmallIcon(R.drawable.ic_fg).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "Reconnect", again).build())
            .addAction(Notification.Action.Builder(icon, "Disconnect", stop).build()).build()
    }
}

/**
 * Entry points used by the UI, plus the supervisor that keeps the connection alive.
 *
 * Rules: CONNECTED is shown only after real traffic was proven through the tunnel. After that the tunnel is never given up
 * by itself: drops are retried forever with a short back-off, and when the phone has no network at all it waits and
 * resumes the moment a network returns. Only the user (Disconnect), an expired/limited config file or the sleep timer stop it.
 */
object VpnController {
    @Volatile private var runGen = 0            // bumped on every connect/disconnect; old supervisors notice and exit
    @Volatile private var wanted = false
    @Volatile private var forceReconnect = false
    private var expiryGen = 0
    private var sleepGen = 0
    private var limitFired = false
    private var appCtx: Context? = null
    private val engineLock = Any()

    fun isRunning() = wanted
    private fun alive(g: Int) = wanted && g == runGen
    private fun svc() = WhitekidVpnService.instance

    private fun deviceAllowed(list: String): Boolean {
        val ids = list.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return ids.isEmpty() || Store.deviceId.uppercase() in ids
    }

    private fun blockReason(ctx: Context, p: Profile): String? {
        return when {
            p.expired() -> "This config has expired. Ask the owner for a new file."
            !deviceAllowed(p.device) -> "This file is locked to another device (yours is ${Store.deviceId})."
            p.mobileOnly && !NetInfo.onMobile(ctx) -> "This config works on mobile data only. Turn off Wi-Fi and try again."
            p.blockRooted && RootCheck.isRooted() -> "This config can't be used on rooted phones."
            p.dataLimitMb > 0 && Store.usedBytes(p.id) >= p.dataLimitMb * 1_000_000L -> "Data limit reached for this config."
            else -> null
        }
    }

    fun connect(ctx: Context, p: Profile, auto: Boolean = false, fromService: Boolean = false) {
        val app = ctx.applicationContext; appCtx = app
        blockReason(app, p)?.let {
            AppLog.log("Blocked: ${p.name} - $it")
            if (fromService) runCatching { svc()?.shutdown() }
            VpnState.set(Status.ERROR, it); return
        }
        if (!auto) { VpnState.reconnects.value = 0; VpnState.lastDrop.value = ""; limitFired = false }
        val wasRunning = wanted
        wanted = true; forceReconnect = false
        val g = ++runGen
        Store.wantedId = p.id
        NetWatcher.start(app)
        if (!auto) { VpnState.resetLink(); VpnState.realIp.value = ""; fetchRealIp() }
        AppLog.log((if (auto) "Reconnecting: " else "Connecting: ") + "${p.name} (${p.proto.label})")
        VpnState.set(Status.CONNECTING, p.name)
        VpnState.step.value = "Starting…"
        VpnState.activeId = p.id
        VpnState.limitBytes = p.dataLimitMb * 1_000_000L
        VpnState.onLimit = { limitHit() }
        watchExpiry(app, p)
        if (!auto) scheduleSleep(app)

        if (p.proto != Proto.WIREGUARD && !fromService) {
            try {
                app.startForegroundService(Intent(app, WhitekidVpnService::class.java)
                    .setAction(WhitekidVpnService.ACTION_START).putExtra(WhitekidVpnService.EXTRA_ID, p.id))
            } catch (e: Exception) {
                AppLog.log("ERROR: couldn't start the VPN service: ${e.message}")
                fail(g, "Couldn't start the VPN service."); return
            }
        }
        thread(name = "wk-supervisor", isDaemon = true) { supervise(app, p, g, wasRunning) }
    }

    /** The user (or the system) stops the VPN: everything goes down and stays down. */
    fun disconnect(ctx: Context) {
        val app = ctx.applicationContext
        wanted = false; runGen++; expiryGen++; sleepGen++; forceReconnect = false
        VpnState.onLimit = null; VpnState.activeId = ""
        Store.wantedId = ""
        AppLog.log("Disconnect requested")
        sessionReport(app)
        NetWatcher.stop(app)
        val s = svc()
        thread(name = "wk-stop", isDaemon = true) {
            if (s != null) runCatching { s.stopEngines() }       // breaks a login that is still in progress
            synchronized(engineLock) {
                runCatching { WireGuardEngine.down(app) }
                if (s != null) runCatching { s.shutdown() }
            }
        }
        VpnState.set(Status.IDLE)
    }

    /** Re-runs the DNS check on demand (RETEST DNS button). */
    fun retestDns() {
        val p = Store.profiles().firstOrNull { it.id == VpnState.activeId } ?: return
        if (VpnState.status.value != Status.CONNECTED) return
        VpnState.dnsNote.value = "Checking DNS…"
        thread(name = "wk-dns-retest", isDaemon = true) {
            val want = Store.dnsList().first()
            val r = DnsCheck.verify(p, Store.dnsList())
            if (VpnState.status.value == Status.CONNECTED) VpnState.dnsNote.value = DnsCheck.describe(r, want)
        }
    }

    /** Switches to another DNS (for example Google) and rebuilds the tunnel so it is really applied. */
    fun switchDns(servers: String) {
        Store.dns = servers; Store.customDns = true; Store.adBlock = false
        AppLog.log("DNS switched to $servers")
        if (wanted) reconnectNow()
    }

    /** Notification "Reconnect" button: drop the current tunnel and build a fresh one. */
    fun reconnectNow() { if (wanted) { AppLog.log("Reconnect requested"); forceReconnect = true } }

    /** A real failure the user has to know about (wrong login, expired file...). Stops everything. */
    private fun fail(g: Int, msg: String) {
        if (g != runGen) return
        AppLog.log("ERROR: $msg")
        wanted = false; runGen++; expiryGen++; sleepGen++
        VpnState.onLimit = null; VpnState.activeId = ""
        Store.wantedId = ""
        val app = appCtx
        app?.let { NetWatcher.stop(it) }
        val s = svc()
        thread(name = "wk-stop", isDaemon = true) {
            if (s != null) runCatching { s.stopEngines() }
            synchronized(engineLock) {
                if (app != null) runCatching { WireGuardEngine.down(app) }
                runCatching { svc()?.shutdown() }
            }
        }
        VpnState.set(Status.ERROR, msg)
    }

    private fun sleep(g: Int, ms: Long) {
        var left = ms
        while (left > 0 && alive(g)) { val s = minOf(left, 250L); Thread.sleep(s); left -= s }
    }

    private fun isFatal(msg: String, everUp: Boolean): Boolean {
        val m = msg.lowercase()
        return m.contains("vpn permission") || m.contains("engine is missing") || m.contains("needs the server public key") ||
            (!everUp && m.contains("auth fail"))
    }

    private fun friendly(msg: String): String =
        if (msg.lowercase().contains("auth fail")) "The server refused the login. Check the username and password." else msg

    /** The same network's next server (smart failover), or null when there is no other one. */
    private fun nextServer(app: Context, p: Profile): Profile? {
        val same = Store.profiles().filter { it.group == p.group && it.passwordHash.isEmpty() && blockReason(app, it) == null }
        if (same.size < 2) return null
        val i = same.indexOfFirst { it.id == p.id }
        return same[(i + 1) % same.size]
    }

    private fun supervise(app: Context, start: Profile, g: Int, interrupt: Boolean) {
        // a previous attempt may still be stuck in a slow login: closing its engines makes it give up so we can take over
        if (interrupt) runCatching { svc()?.stopEngines() }
        var p = start
        var attempt = 0        // failed tries in a row
        var everUp = false     // has this session been connected at least once?
        var wait = 1
        while (alive(g)) {
            try {
                if (!NetInfo.online(app)) {
                    VpnState.set(Status.CONNECTING, p.name)
                    waitOnline(app, g)
                    if (!alive(g)) return
                    sleep(g, 1500)             // let the new network settle
                }
                blockReason(app, p)?.let { fail(g, it); return }
                VpnState.set(Status.CONNECTING, p.name)
                if (attempt > 0) VpnState.step.value = "Reconnecting (try ${attempt + 1})…"
                if (attempt == 0 && !everUp && Store.autoDns && Store.customDns) {
                    VpnState.step.value = "Picking the fastest DNS…"
                    DnsBench.fastest()?.let { Store.dns = it.servers; AppLog.log("Fastest DNS: ${it.name} (${it.ms} ms)") }
                }
                synchronized(engineLock) {
                    if (!alive(g)) return
                    startEngines(app, p, attempt)
                }
                if (!alive(g)) return
                proveTraffic(app, p, g)
                if (!alive(g)) return

                VpnState.set(Status.CONNECTED, p.name)
                VpnState.step.value = ""
                Store.recordResult(p.id, true)
                everUp = true; attempt = 0; wait = 1
                AppLog.log("Connected: ${p.name}")
                svc()?.updateNote("Connected: ${p.name}")
                afterConnected(app, p, g)

                val why = hold(app, p, g)
                if (why == null || !alive(g)) return
                if (!Store.autoReconnect && why != "reconnect requested" && why != "network lost") {
                    fail(g, "Connection lost ($why). Auto-reconnect is turned off in Tools."); return
                }
                AppLog.log("Link lost ($why). Reconnecting…")
                VpnState.lastDrop.value = why
                VpnState.reconnects.value = VpnState.reconnects.value + 1
                VpnState.set(Status.CONNECTING, p.name)
                VpnState.step.value = if (why == "network lost") "Network lost…" else "Reconnecting…"
                synchronized(engineLock) { stopQuiet(app); svc()?.blackhole() }
                forceReconnect = false
                sleep(g, 800)
                continue
            } catch (e: Exception) {
                if (!alive(g)) return
                val msg = e.message ?: e.javaClass.simpleName
                synchronized(engineLock) { stopQuiet(app); if (everUp) svc()?.blackhole() }
                if (!NetInfo.online(app)) continue                       // offline is not a failure: wait for the network instead
                AppLog.log("ERROR: $msg")
                Store.recordResult(p.id, false)
                attempt++
                if (isFatal(msg, everUp)) { fail(g, friendly(msg)); return }
                if (!everUp && attempt >= 5) { fail(g, "Couldn't connect after $attempt tries: ${friendly(msg)}"); return }
                if (Store.failover && attempt % 3 == 0) {
                    nextServer(app, p)?.let { n ->
                        AppLog.log("Server keeps failing - switching to ${n.name}")
                        p = n; Store.wantedId = n.id; VpnState.activeId = n.id
                        VpnState.set(Status.CONNECTING, n.name)
                    }
                }
                VpnState.step.value = "Retrying in ${wait}s…"
                sleep(g, wait * 1000L)
                wait = minOf(wait * 2, 20)
            }
        }
    }

    private fun waitOnline(app: Context, g: Int) {
        VpnState.step.value = "Waiting for network…"
        svc()?.updateNote("Waiting for network…")
        AppLog.log("Network is off - waiting for it to come back")
        var seen = NetWatcher.version
        while (alive(g) && !NetInfo.online(app)) {
            var i = 0
            while (i < 8 && alive(g) && NetWatcher.version == seen) { Thread.sleep(250); i++ }
            seen = NetWatcher.version
        }
        if (alive(g)) AppLog.log("Network is back")
    }

    private fun startEngines(app: Context, p: Profile, attempt: Int) {
        if (p.proto == Proto.WIREGUARD) {
            VpnState.step.value = "Starting WireGuard…"
            runCatching { WireGuardEngine.down(app) }
            val only = Store.splitEnabled && Store.splitOnly
            WireGuardEngine.up(app, p.raw, Store.dnsList(), if (Store.splitEnabled) Store.bypassApps else emptySet(), only)
            return
        }
        val s = awaitService(app, p)
        s.startEngines(p, attempt)
    }

    private fun awaitService(app: Context, p: Profile): WhitekidVpnService {
        val t0 = System.currentTimeMillis()
        var retried = false
        while (System.currentTimeMillis() - t0 < 9000) {
            svc()?.let { return it }
            if (!retried && System.currentTimeMillis() - t0 > 2000) {
                retried = true
                runCatching {
                    app.startForegroundService(Intent(app, WhitekidVpnService::class.java)
                        .setAction(WhitekidVpnService.ACTION_START).putExtra(WhitekidVpnService.EXTRA_ID, p.id))
                }
            }
            Thread.sleep(150)
        }
        error("The VPN service didn't start")
    }

    private fun stopQuiet(app: Context) {
        runCatching { WireGuardEngine.down(app) }
        runCatching { svc()?.stopEngines() }
    }

    /** Proves that real traffic passes through the server before CONNECTED is shown. Throws if it doesn't. */
    private fun proveTraffic(app: Context, p: Profile, g: Int) {
        VpnState.step.value = "Testing internet…"
        val slow = p.proto == Proto.SLOWDNS
        // SSH and SlowDNS already proved the server by logging in, so they connect right away like other VPN apps;
        // the live monitor still catches a bad link. Other protocols have no login, so they need the traffic proof.
        val loginProved = slow || p.proto == Proto.SSH
        val tries = if (loginProved) 1 else 3
        val timeout = if (slow) 20_000 else if (loginProved) 6_000 else 8_000
        var last = ""
        Thread.sleep(600)
        for (i in 1..tries) {
            if (!alive(g)) return
            try {
                val ms = HealthCheck.run(p, timeout)
                VpnState.pingMs.value = ms; VpnState.recordPing(ms)
                AppLog.log("Verified: traffic flows through ${p.name} ($ms ms)")
                return
            } catch (e: Exception) {
                last = e.message ?: e.javaClass.simpleName
                AppLog.log("Check $i/$tries failed: $last")
                if (i < tries) Thread.sleep(1500)
            }
        }
        if (loginProved) { AppLog.log("Login worked but the first test was slow ($last); continuing, the monitor keeps watching"); return }
        throw IllegalStateException("Connected, but no internet passes through the server ($last)")
    }

    /** The phone's real IP. This app is outside the tunnel, so the answer is always the real one. */
    private fun fetchRealIp() {
        thread(name = "wk-realip", isDaemon = true) {
            runCatching {
                val c = java.net.URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                val ip = try { c.inputStream.bufferedReader().use { it.readText() }.trim() } finally { c.disconnect() }
                if (ip.length in 7..45) VpnState.realIp.value = ip
            }
        }
    }

    /** Nice-to-have details after CONNECTED. None of this can delay or break the connection. */
    private fun afterConnected(app: Context, p: Profile, g: Int) {
        // DNS check on its own thread, patient and repeated, so a slow tunnel never shows a false error
        thread(name = "wk-dns", isDaemon = true) {
            val want = Store.dnsList().first()
            VpnState.dnsNote.value = "Checking DNS…"
            var note = ""
            for (wait in longArrayOf(1500, 6000, 15000)) {
                Thread.sleep(wait)
                if (!alive(g) || VpnState.status.value != Status.CONNECTED) return@thread
                val r = DnsCheck.verify(p, Store.dnsList())
                note = DnsCheck.describe(r, want)
                if (r != null) break
                if (alive(g)) VpnState.dnsNote.value = "Checking DNS again…"
            }
            if (alive(g) && VpnState.status.value == Status.CONNECTED) VpnState.dnsNote.value = note
        }
        thread(name = "wk-after", isDaemon = true) {
            runCatching {
                val c = HealthCheck.open(p, "https://ipinfo.io/json", if (p.proto == Proto.SLOWDNS) 20_000 else 8_000)
                val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                val j = org.json.JSONObject(body)
                val where = listOf(j.optString("country"), j.optString("city")).filter { it.isNotBlank() }.joinToString(", ")
                val ip = j.optString("ip")
                val info = (if (where.isNotEmpty()) "$where • " else "") + ip
                val real = VpnState.realIp.value
                val verdict = when {
                    ip.isEmpty() -> ""
                    real.isEmpty() -> "Exit IP checked"
                    ip != real -> "Your real IP is hidden"
                    else -> "Warning: exit IP equals your real IP"
                }
                if (alive(g) && VpnState.status.value == Status.CONNECTED) VpnState.protection.value = verdict
                if (alive(g) && VpnState.status.value == Status.CONNECTED) VpnState.exitInfo.value = info
            }
            if (p.proto != Proto.WIREGUARD) {
                if (TunCheck.waitValidated(app, 10_000)) AppLog.log("Android confirmed internet through the tunnel")
                else AppLog.log("Android hasn't validated the tunnel (normal on zero-rated bundles)")
            }
        }
    }

    private fun noteText(): String {
        val s = VpnState.seconds.value
        val ping = VpnState.pingMs.value
        return "Connected • ↓ ${rateText(VpnState.rxSpeed.value)} ↑ ${rateText(VpnState.txSpeed.value)} • " +
            "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) + if (ping >= 0) " • $ping ms" else ""
    }

    /**
     * Watches a connected tunnel in real time. Returns why it was lost, or null when the user stopped it.
     * Network events from NetWatcher are handled at once; a keep-alive request goes through the tunnel every 10 s
     * (20 s for SlowDNS) and feeds the live ping and link health.
     */
    private fun hold(app: Context, p: Profile, g: Int): String? {
        val slow = p.proto == Proto.SLOWDNS
        val every = if (slow) 20_000L else 10_000L
        var net = NetInfo.netKey(app)
        var seen = NetWatcher.version
        var nextPing = System.currentTimeMillis() + every
        var lastNote = 0L
        var lastTick = 0L
        var fails = 0
        var afterChange = false
        while (alive(g)) {
            Thread.sleep(250)
            if (!alive(g)) return null
            if (forceReconnect) { forceReconnect = false; return "reconnect requested" }
            val now = System.currentTimeMillis()
            val event = NetWatcher.version != seen
            if (event || now - lastTick >= 1000) {
                lastTick = now
                if (event) { seen = NetWatcher.version; AppLog.log("Network event") }
                if (!NetInfo.online(app)) return "network lost"
                val key = NetInfo.netKey(app)
                if (key != net) {
                    net = key; afterChange = true; nextPing = now + 1500
                    AppLog.log("Network changed - checking the tunnel")
                }
                if (p.proto != Proto.WIREGUARD && svc()?.engineAlive() == false) return "engine stopped"
            }
            if (now >= nextPing) {
                try {
                    val ms = HealthCheck.run(p, if (slow) 20_000 else 7_000)
                    VpnState.pingMs.value = ms; VpnState.recordPing(ms)
                    fails = 0; afterChange = false
                } catch (e: Exception) {
                    if (!alive(g)) return null
                    if (!NetInfo.online(app)) return "network lost"
                    fails++
                    VpnState.recordPing(null)
                    AppLog.log("Link check failed ($fails): ${e.message}")
                    val limit = if (afterChange) 2 else if (slow) 4 else 3
                    if (fails >= limit) return if (afterChange) "network changed" else "tunnel stopped responding"
                }
                nextPing = System.currentTimeMillis() + (if (fails > 0) 3_000L else every)
            }
            if (now - lastNote >= 4000) {
                lastNote = now
                svc()?.updateNote(noteText())
                dataAlert(app)
            }
        }
        return null
    }

    /** One notification per day when usage passes the limit chosen in Tools. */
    private fun dataAlert(app: Context) {
        val mb = Store.alertMb
        if (mb <= 0 || Store.alertDay == Store.today() || Store.usedToday() < mb * 1_000_000L) return
        Store.alertDay = Store.today()
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("wk_alert", "Data alerts", NotificationManager.IMPORTANCE_DEFAULT))
            val n = Notification.Builder(app, "wk_alert").setContentTitle("Data alert")
                .setContentText("You have used over $mb MB today through Whitekid VPN.")
                .setSmallIcon(R.drawable.ic_fg).setAutoCancel(true).build()
            nm.notify(2, n)
        }
    }

    /** Short summary shown when the user disconnects. */
    private fun sessionReport(app: Context) {
        if (VpnState.status.value != Status.CONNECTED || VpnState.seconds.value <= 0) return
        val s = VpnState.seconds.value
        fun mb(b: Long) = if (b >= 1_000_000_000L) "%.2f GB".format(b / 1e9) else "%.1f MB".format(b / 1e6)
        val text = "Session %d:%02d:%02d • ↓ %s ↑ %s".format(s / 3600, s / 60 % 60, s % 60, mb(VpnState.rxBytes.value), mb(VpnState.txBytes.value))
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** Sleep timer: disconnect after the number of minutes chosen in Tools. */
    private fun scheduleSleep(ctx: Context) {
        val mine = ++sleepGen
        val mins = Store.sleepMinutes
        if (mins <= 0) return
        thread(name = "wk-sleep", isDaemon = true) {
            Thread.sleep(mins * 60_000L)
            if (mine == sleepGen && wanted) {
                AppLog.log("Sleep timer ended - disconnecting")
                disconnect(ctx)
            }
        }
    }

    private fun limitHit() {
        if (limitFired) return
        limitFired = true
        val c = appCtx ?: return
        AppLog.log("Data limit reached - disconnecting")
        disconnect(c)
        VpnState.set(Status.ERROR, "Data limit reached for this config.")
    }

    /** Disconnects the moment an expiring file runs out, even mid-session. */
    private fun watchExpiry(ctx: Context, p: Profile) {
        val mine = ++expiryGen
        if (p.expires <= 0) return
        thread(name = "wk-expiry", isDaemon = true) {
            while (mine == expiryGen) {
                Thread.sleep(15_000)
                if (mine != expiryGen) break
                if (p.expired()) {
                    AppLog.log("Expired: ${p.name} - disconnecting")
                    disconnect(ctx)
                    VpnState.set(Status.ERROR, "This config has expired. Ask the owner for a new file.")
                    break
                }
            }
        }
    }
}
