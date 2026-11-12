package zw.whitekid.vpn.ui

import android.content.Context
import android.widget.Toast
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import zw.whitekid.vpn.BuildConfig
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.vpn.DnsResolve
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * v1.13.0: SSL / SNI mode. The user picks a country, a port and types ONLY an SNI. The app then runs SSH wrapped in
 * TLS with that SNI (the engine for this already exists in SshEngine). Nothing else has to be filled in.
 *
 * Where the countries come from (all merged into one list):
 *  1. GitHub secret WK_SSL_SERVERS_B64 (base64 of the JSON below), baked into the APK at build time
 *  2. the file app/src/main/assets/ssl_servers.json
 *  3. countries the user adds inside the app
 *
 * JSON: [{"country":"South Africa","flag":"🇿🇦","host":"za1.example.com","ports":[443,8443,80],"user":"name","pass":"secret","sni":"optional default"}]
 */
object SslCatalog {
    data class Entry(val country: String, val flag: String, val host: String, val ports: List<Int>,
                     val user: String, val pass: String, val sni: String, val custom: Boolean)

    fun parse(json: String, custom: Boolean): List<Entry> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val host = o.optString("host").trim(); val country = o.optString("country").trim()
            if (host.isEmpty() || country.isEmpty()) return@mapNotNull null
            val pv = o.opt("ports")
            val ports: List<Int> = when (pv) {
                is JSONArray -> (0 until pv.length()).map { pv.optInt(it, 0) }
                is String -> pv.split(",", " ").mapNotNull { it.trim().toIntOrNull() }
                else -> emptyList()
            }.filter { it in 1..65535 }.ifEmpty { listOf(443) }
            Entry(country, o.optString("flag").trim(), host, ports, o.optString("user"), o.optString("pass"), o.optString("sni").trim(), custom)
        }
    }.getOrDefault(emptyList())

    fun load(ctx: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val b64 = BuildConfig.SSL_SERVERS_B64.trim()
        if (b64.isNotEmpty()) runCatching {
            out.addAll(parse(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8), false))
        }
        runCatching { out.addAll(parse(ctx.assets.open("ssl_servers.json").bufferedReader().use { it.readText() }, false)) }
        out.addAll(parse(Store.extraStr("sslCustom", "[]"), true))
        return out
    }

    fun saveCustom(list: List<Entry>) {
        val arr = JSONArray()
        list.filter { it.custom }.forEach { e ->
            arr.put(JSONObject().put("country", e.country).put("flag", e.flag).put("host", e.host)
                .put("ports", JSONArray(e.ports)).put("user", e.user).put("pass", e.pass).put("sni", e.sni))
        }
        Store.setExtraStr("sslCustom", arr.toString())
    }

    private val hostRx = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    fun validSni(s: String) = hostRx.matches(s) && !Regex("^[0-9.]+$").matches(s)

    /**
     * Proves the whole chain for this country, port and SNI: TCP connect, TLS handshake with that SNI, and the SSH banner
     * that a working SSL tunnel sends first. Returns milliseconds or throws with a readable reason.
     */
    fun test(host: String, port: Int, sni: String, timeoutMs: Int = 8000): Int {
        val t = System.nanoTime()
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        val factory = SSLContext.getInstance("TLS").apply { init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom()) }.socketFactory
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(DnsResolve.resolve(host) ?: host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = factory.createSocket(raw, sni, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
            ssl.startHandshake()
            val banner = ssl.inputStream.bufferedReader().readLine() ?: error("server closed the connection")
            if (!banner.startsWith("SSH-")) error("TLS works but the server did not answer as SSH")
        } finally { runCatching { raw.close() } }
        return ((System.nanoTime() - t) / 1_000_000).toInt()
    }

    fun history(): List<String> = Store.extraStr("sslSnis").split(",").filter { it.isNotBlank() }
    fun saveSni(s: String) {
        Store.setExtraStr("sslSnis", (listOf(s) + history()).distinct().take(8).joinToString(","))
        Store.setExtraStr("sslSni", s)
    }
}

/** The SSL / SNI card on the Connect screen. onUse(profile, connectNow) is handled in MainActivity. */
@Composable
fun SslPanel(onUse: (Profile, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(SslCatalog.load(ctx)) }
    var sel by remember { mutableIntStateOf(Store.extraLong("sslSel").toInt()) }
    var port by remember { mutableIntStateOf(Store.extraLong("sslPort").toInt()) }
    var sni by remember { mutableStateOf(Store.extraStr("sslSni")) }
    var result by remember { mutableStateOf("") }
    var good by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var addDlg by remember { mutableStateOf(false) }
    var hist by remember { mutableStateOf(SslCatalog.history()) }

    val ent = entries.getOrNull(sel) ?: entries.firstOrNull()
    val ports = ent?.ports ?: emptyList()
    val usePort = if (port in ports) port else (ports.firstOrNull() ?: 443)

    Spacer(Modifier.height(18.dp))
    Text("SSL / SNI MODE", color = WkMint, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
    HorizontalDivider(color = WkLine, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))

    if (entries.isEmpty()) {
        Text("No SSL servers yet. Tap + COUNTRY and enter the server address, ports and login once.", color = WkMuted, fontSize = 12.sp)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Picker("COUNTRY", ((ent?.flag ?: "") + " " + (ent?.country ?: "")).trim(),
                entries.mapIndexed { i, x -> i.toString() to (x.flag + " " + x.country).trim() }, Modifier.weight(1f)) {
                sel = it.toIntOrNull() ?: 0; port = 0; Store.setExtraLong("sslSel", sel.toLong()); Store.setExtraLong("sslPort", 0L)
                val d = entries.getOrNull(sel)?.sni.orEmpty()
                if (d.isNotBlank() && sni.isBlank()) sni = d
                result = ""
            }
            Picker("PORT", usePort.toString(), ports.map { it.toString() to it.toString() }, Modifier.weight(1f)) {
                port = it.toIntOrNull() ?: 0; Store.setExtraLong("sslPort", port.toLong()); result = ""
            }
        }
        OutlinedTextField(sni, { sni = it.lowercase().replace(" ", ""); result = "" }, Modifier.fillMaxWidth().padding(top = 10.dp), singleLine = true,
            label = { Text("SNI (server name)") }, placeholder = { Text("www.example.com") })
        if (hist.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hist.forEach { h -> FilterChip(selected = sni == h, onClick = { sni = h; result = "" }, label = { Text(h, maxLines = 1) }) }
        }
        val bad: String? = when {
            ent == null -> "Add a country first."
            !SslCatalog.validSni(sni) -> "Type a valid SNI, for example www.example.com"
            else -> null
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = {
                if (bad != null || ent == null) { good = false; result = bad ?: "" }
                else {
                    busy = true; good = false; result = "Testing port $usePort…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { SslCatalog.test(ent.host, usePort, sni) } }
                        good = r.isSuccess
                        result = r.fold({ "✓ Port $usePort works with SNI $sni ($it ms)" }, { "✗ Port $usePort failed: ${it.message ?: it.javaClass.simpleName}" })
                        busy = false
                    }
                }
            }) { Text(if (busy) "TESTING…" else "TEST", letterSpacing = 1.sp, fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = {
                if (bad != null || ent == null) { good = false; result = bad ?: "" }
                else {
                    busy = true; good = false; result = "Trying every port…"
                    scope.launch {
                        var found = -1; var ms = 0
                        for (pt in ent.ports) {
                            result = "Trying port $pt…"
                            val r = withContext(Dispatchers.IO) { runCatching { SslCatalog.test(ent.host, pt, sni, 6000) } }
                            if (r.isSuccess) { found = pt; ms = r.getOrDefault(0); break }
                        }
                        if (found > 0) { port = found; Store.setExtraLong("sslPort", found.toLong()); good = true; result = "✓ Port $found works with SNI $sni ($ms ms)" }
                        else { good = false; result = "✗ No port works with this SNI. Try another SNI." }
                        busy = false
                    }
                }
            }) { Text("AUTO PORT", letterSpacing = 1.sp, fontSize = 12.sp) }
            TextButton(enabled = !busy, onClick = {
                if (bad != null || ent == null) { good = false; result = bad ?: "" }
                else {
                    SslCatalog.saveSni(sni); hist = SslCatalog.history()
                    onUse(sslProfile(ent, usePort, sni), true)
                }
            }) { Text("CONNECT", letterSpacing = 1.sp, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            TextButton(enabled = !busy, onClick = {
                if (bad != null || ent == null) { good = false; result = bad ?: "" }
                else {
                    SslCatalog.saveSni(sni); hist = SslCatalog.history()
                    onUse(sslProfile(ent, usePort, sni), false)
                    Toast.makeText(ctx, "SSL server selected. Tap the power button.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("SELECT", letterSpacing = 1.sp, fontSize = 12.sp) }
        }
        if (result.isNotEmpty()) Text(result, color = if (good) WkOk else if (result.startsWith("✗") || result.startsWith("Type") || result.startsWith("Add")) WkRed else WkMuted,
            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("Runs SSH inside TLS with your SNI. TEST checks the whole chain, AUTO PORT finds a port that works.", color = WkMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { addDlg = true }) { Text("+ COUNTRY", letterSpacing = 1.sp, fontSize = 12.sp) }
        if (ent != null && ent.custom) TextButton(onClick = {
            entries = entries - ent; SslCatalog.saveCustom(entries); sel = 0; port = 0
        }) { Text("REMOVE", letterSpacing = 1.sp, fontSize = 12.sp) }
    }
    if (addDlg) SslAddDialog(onClose = { addDlg = false }) { n ->
        entries = entries + n; SslCatalog.saveCustom(entries); sel = entries.size - 1; port = 0
        Store.setExtraLong("sslSel", sel.toLong()); addDlg = false
        if (n.sni.isNotBlank() && sni.isBlank()) sni = n.sni
        AppLog.log("SSL: added ${n.country}")
    }
}

private fun sslProfile(e: SslCatalog.Entry, port: Int, sni: String) = Profile(
    id = "ssl_mode", name = "SSL • ${e.country} :$port", proto = Proto.SSH, host = e.host, port = port,
    user = e.user, secret = e.pass, sni = sni, group = "SSL", locked = true, author = "Whitekid Tech",
    note = "SSH over TLS • SNI $sni"
)

@Composable
private fun SslAddDialog(onClose: () -> Unit, onSave: (SslCatalog.Entry) -> Unit) {
    var country by remember { mutableStateOf("") }
    var flag by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("443, 8443, 80") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, containerColor = WkCard,
        title = { Text("Add a country") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(country, { country = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Country") })
                OutlinedTextField(flag, { flag = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Flag emoji (optional)") })
                OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server address") })
                OutlinedTextField(ports, { ports = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SSL ports, comma separated") })
                OutlinedTextField(user, { user = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SSH username") })
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("SSH password") })
                if (err.isNotEmpty()) Text(err, color = WkRed, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                val list = SslCatalog.parse(JSONArray().put(JSONObject().put("country", country).put("flag", flag).put("host", host)
                    .put("ports", ports).put("user", user).put("pass", pass)).toString(), true)
                if (list.isEmpty()) err = "Country and server address are required." else onSave(list.first())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
