package zw.whitekid.vpn.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    private lateinit var sp: SharedPreferences
    lateinit var deviceId: String

    fun init(c: Context) {
        sp = c.getSharedPreferences("whitekid", Context.MODE_PRIVATE)
        val raw = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID) ?: "whitekid"
        deviceId = MessageDigest.getInstance("SHA-256").digest(("wk:" + raw).toByteArray())
            .joinToString("") { "%02X".format(it) }.take(10)
    }

    var autoReconnect: Boolean
        get() = sp.getBoolean("autoReconnect", true)
        set(v) { sp.edit().putBoolean("autoReconnect", v).apply() }

    var lastLinkUpdate: Long
        get() = sp.getLong("lastLinkUpdate", 0L)
        set(v) { sp.edit().putLong("lastLinkUpdate", v).apply() }

    /** 0 = follow the phone, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) { sp.edit().putInt("themeMode", v).apply() }
    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) { sp.edit().putInt("accent", v).apply() }
    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("favorites", v).apply() }
    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", false)
        set(v) { sp.edit().putBoolean("autoConnect", v).apply() }
    var connectOnBoot: Boolean
        get() = sp.getBoolean("connectOnBoot", false)
        set(v) { sp.edit().putBoolean("connectOnBoot", v).apply() }
    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", false)
        set(v) { sp.edit().putBoolean("keepAwake", v).apply() }
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) { sp.edit().putBoolean("haptics", v).apply() }
    var secureScreen: Boolean
        get() = sp.getBoolean("secureScreen", false)
        set(v) { sp.edit().putBoolean("secureScreen", v).apply() }
    var sleepMinutes: Int
        get() = sp.getInt("sleepMinutes", 0)
        set(v) { sp.edit().putInt("sleepMinutes", v).apply() }
    var pinHash: String
        get() = sp.getString("pinHash", "") ?: ""
        set(v) { sp.edit().putString("pinHash", v).apply() }
    var seenVersion: String
        get() = sp.getString("seenVersion", "") ?: ""
        set(v) { sp.edit().putString("seenVersion", v).apply() }
    var historyJson: String
        get() = sp.getString("history", "[]") ?: "[]"
        set(v) { sp.edit().putString("history", v).apply() }
    var totalRx: Long
        get() = sp.getLong("totalRx", 0L)
        set(v) { sp.edit().putLong("totalRx", v).apply() }
    var totalTx: Long
        get() = sp.getLong("totalTx", 0L)
        set(v) { sp.edit().putLong("totalTx", v).apply() }
    var sessions: Int
        get() = sp.getInt("sessions", 0)
        set(v) { sp.edit().putInt("sessions", v).apply() }
    var longestSec: Long
        get() = sp.getLong("longestSec", 0L)
        set(v) { sp.edit().putLong("longestSec", v).apply() }

    var customDns: Boolean
        get() = sp.getBoolean("customDns", true)
        set(v) { sp.edit().putBoolean("customDns", v).apply() }
    var mux: Boolean
        get() = sp.getBoolean("mux", false)
        set(v) { sp.edit().putBoolean("mux", v).apply() }
    var newerVersion: String
        get() = sp.getString("newerVersion", "") ?: ""
        set(v) { sp.edit().putString("newerVersion", v).apply() }
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }
    var autoPick: Boolean
        get() = sp.getBoolean("autoPick", false)
        set(v) { sp.edit().putBoolean("autoPick", v).apply() }
    var group: String
        get() = sp.getString("group", "All") ?: "All"
        set(v) { sp.edit().putString("group", v).apply() }


    // ---- v1.6 settings
    /** keep the CPU and Wi-Fi awake while connected */
    var stayAlive: Boolean
        get() = sp.getBoolean("stayAlive", true)
        set(v) { sp.edit().putBoolean("stayAlive", v).apply() }
    /** switch to the next server of the same network when one keeps failing */
    var failover: Boolean
        get() = sp.getBoolean("failover", true)
        set(v) { sp.edit().putBoolean("failover", v).apply() }
    /** DNS-over-HTTPS for the well-known resolvers */
    var doh: Boolean
        get() = sp.getBoolean("doh", false)
        set(v) { sp.edit().putBoolean("doh", v).apply() }
    var blockIpv6: Boolean
        get() = sp.getBoolean("blockIpv6", false)
        set(v) { sp.edit().putBoolean("blockIpv6", v).apply() }
    /** split tunneling mode: false = chosen apps bypass the VPN, true = ONLY chosen apps use it */
    var splitOnly: Boolean
        get() = sp.getBoolean("splitOnly", false)
        set(v) { sp.edit().putBoolean("splitOnly", v).apply() }
    var autoDns: Boolean
        get() = sp.getBoolean("autoDns", false)
        set(v) { sp.edit().putBoolean("autoDns", v).apply() }
    var confirmDisconnect: Boolean
        get() = sp.getBoolean("confirmDisconnect", false)
        set(v) { sp.edit().putBoolean("confirmDisconnect", v).apply() }
    /** id of the server the user wants connected right now ("" = user disconnected) */
    var wantedId: String
        get() = sp.getString("wantedId", "") ?: ""
        set(v) { sp.edit().putString("wantedId", v).apply() }
    /** DNS that blocks ads and trackers (AdGuard) */
    var adBlock: Boolean
        get() = sp.getBoolean("adBlock", false)
        set(v) { sp.edit().putBoolean("adBlock", v).apply() }
    /** where auto-connect is allowed: 0 any network, 1 mobile data only, 2 Wi-Fi only */
    var autoNet: Int
        get() = sp.getInt("autoNet", 0)
        set(v) { sp.edit().putInt("autoNet", v).apply() }
    /** warn when today's usage passes this many MB (0 = off) */
    var alertMb: Int
        get() = sp.getInt("alertMb", 0)
        set(v) { sp.edit().putInt("alertMb", v).apply() }
    var alertDay: String
        get() = sp.getString("alertDay", "") ?: ""
        set(v) { sp.edit().putString("alertDay", v).apply() }
    fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** usage of the last [n] days, oldest first: ("Mon", bytes) */
    fun lastDays(n: Int): List<Pair<String, Long>> {
        val cal = java.util.Calendar.getInstance()
        val out = ArrayList<Pair<String, Long>>()
        repeat(n) {
            val d = cal.time
            out.add(SimpleDateFormat("EEE", Locale.US).format(d) to sp.getLong("day_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(d), 0L))
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return out.reversed()
    }

    // ---- v1.10 helpers (same prefs file, so "Reset the app" wipes them too)
    fun extraStr(k: String, d: String = ""): String = sp.getString("x_$k", d) ?: d
    fun setExtraStr(k: String, v: String) { sp.edit().putString("x_$k", v).apply() }
    fun extraLong(k: String, d: Long = 0L): Long = sp.getLong("x_$k", d)
    fun setExtraLong(k: String, v: Long) { sp.edit().putLong("x_$k", v).apply() }
    /** pure-black background in dark mode (AMOLED screens) */
    var amoled: Boolean
        get() = sp.getBoolean("amoled", false)
        set(v) { sp.edit().putBoolean("amoled", v).apply() }

    /** usage of the last [n] days, oldest first: ("2026-11-11", bytes) */
    fun lastDaysDated(n: Int): List<Pair<String, Long>> {
        val cal = java.util.Calendar.getInstance()
        val out = ArrayList<Pair<String, Long>>()
        repeat(n) {
            val d = cal.time
            out.add(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) to
                sp.getLong("day_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(d), 0L))
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        return out.reversed()
    }

    /** real kill switch: while the tunnel is rebuilding, all traffic is blocked instead of leaking */
    var killSwitch: Boolean
        get() = sp.getBoolean("killSwitch", false)
        set(v) { sp.edit().putBoolean("killSwitch", v).apply() }
    /** protection level shown on Home: 0 speed, 1 balanced, 2 fortress */
    var shield: Int
        get() = sp.getInt("shield", 0)
        set(v) {
            sp.edit().putInt("shield", v).apply()
            killSwitch = v == 2; blockIpv6 = v >= 1; doh = v == 2
        }

    var lastClip: Int
        get() = sp.getInt("lastClip", 0)
        set(v) { sp.edit().putInt("lastClip", v).apply() }

    private fun dayKey(fmt: String) = SimpleDateFormat(fmt, Locale.US).format(Date())
    fun addDaily(n: Long) {
        val k = "day_" + dayKey("yyyyMMdd")
        sp.edit().putLong(k, sp.getLong(k, 0L) + n).apply()
    }
    fun usedToday(): Long = sp.getLong("day_" + dayKey("yyyyMMdd"), 0L)
    fun usedMonth(): Long {
        val prefix = "day_" + dayKey("yyyyMM")
        return sp.all.entries.filter { it.key.startsWith(prefix) }.fold(0L) { a, e -> a + ((e.value as? Long) ?: 0L) }
    }

    fun recordResult(id: String, ok: Boolean) {
        val k = if (ok) "ok_$id" else "bad_$id"
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply()
    }
    /** share of connection attempts that worked (0-100), or null until there are at least 3 attempts */
    fun reliability(id: String): Int? {
        val ok = sp.getInt("ok_$id", 0); val bad = sp.getInt("bad_$id", 0)
        return if (ok + bad >= 3) ok * 100 / (ok + bad) else null
    }

    const val GOOGLE_DNS = "8.8.8.8,8.8.4.4"

    /** DNS actually used: the user's choice, or Google DNS when the DNS (Custom) switch is off. */
    fun effectiveDns(): String = if (adBlock) "94.140.14.14,94.140.15.15" else if (customDns) dns else GOOGLE_DNS

    /**
     * The DNS servers to really apply: only valid IPv4 addresses, never empty (falls back to Google).
     * An empty or mistyped DNS box used to leave the tunnel with no resolver at all, which is what made lookups hang.
     */
    fun dnsList(): List<String> {
        val ip4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
        val ok = effectiveDns().split(",", " ", ";").map { it.trim() }
            .filter { ip4.matches(it) && it.split(".").all { p -> p.toInt() in 0..255 } }
            .distinct()
        return ok.ifEmpty { GOOGLE_DNS.split(",") }
    }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hashPin(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("wk-pin:$pin").toByteArray()).joinToString("") { "%02x".format(it) }

    fun resetUsed(id: String) { sp.edit().remove("used_$id").apply() }
    fun wipe() { sp.edit().clear().apply() }

    fun usedBytes(id: String): Long = sp.getLong("used_$id", 0L)
    fun addUsed(id: String, n: Long) { sp.edit().putLong("used_$id", usedBytes(id) + n).apply() }

    val DNS_PRESETS = linkedMapOf(
        "Google" to GOOGLE_DNS,
        "Cloudflare" to "1.1.1.1,1.0.0.1",
        "Quad9" to "9.9.9.9,149.112.112.112",
        "AdGuard" to "94.140.14.14,94.140.15.15",
        "CF Malware-block" to "1.1.1.2,1.0.0.2",
        "CF Family-safe" to "1.1.1.3,1.0.0.3"
    )

    fun profiles(): List<Profile> {
        val arr = JSONArray(sp.getString("profiles", "[]"))
        return (0 until arr.length()).mapNotNull { runCatching { Profile.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }
    fun saveProfiles(list: List<Profile>) {
        sp.edit().putString("profiles", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    /** Latest wall-clock time the app has seen (stops "wind the clock back" tricks on expiring files). */
    var lastSeen: Long
        get() = sp.getLong("lastSeen", 0L)
        set(v) { sp.edit().putLong("lastSeen", v).apply() }

    var selectedId: String
        get() = sp.getString("selected", "") ?: ""
        set(v) { sp.edit().putString("selected", v).apply() }

    var dns: String
        get() {
            // one-time switch of existing installs to Google DNS (later choices in Tools are respected)
            if (!sp.getBoolean("dnsGoogleDone", false)) sp.edit().putString("dns", GOOGLE_DNS).putBoolean("dnsGoogleDone", true).apply()
            return sp.getString("dns", GOOGLE_DNS) ?: GOOGLE_DNS
        }
        set(v) { sp.edit().putString("dns", v).putBoolean("dnsGoogleDone", true).apply() }

    var splitEnabled: Boolean
        get() = sp.getBoolean("split", false)
        set(v) { sp.edit().putBoolean("split", v).apply() }

    /** Packages that BYPASS the VPN when split tunneling is on. */
    var bypassApps: Set<String>
        get() = sp.getStringSet("bypass", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("bypass", v).apply() }
}
