package zw.whitekid.vpn.data

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Parses pasted "tweak codes": share links, WireGuard .conf text, or a base64 blob of either. */
object LinkParser {

    fun parseAny(input: String): List<Profile> {
        val text = input.trim()
        if (text.isEmpty()) return emptyList()
        if (text.contains("[Interface]", ignoreCase = true)) return listOfNotNull(parseWireGuard(text))
        val direct = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull(::parseLink).toList()
        if (direct.isNotEmpty()) return direct
        // maybe a base64 subscription / tweak blob
        val decoded = b64(text) ?: return emptyList()
        return if (decoded == text) emptyList() else parseAny(decoded)
    }

    private fun parseLink(line: String): Profile? = runCatching {
        when {
            line.startsWith("vless://") -> parseUrlStyle(line, Proto.VLESS)
            line.startsWith("trojan://") -> parseUrlStyle(line, Proto.TROJAN)
            line.startsWith("vmess://") -> parseVmess(line)
            line.startsWith("ssh://") -> parseSsh(line)
            line.startsWith("slowdns://") -> parseSlowDns(line)
            line.startsWith("ss://") -> parseSs(line)
            else -> null
        }
    }.getOrNull()

    private fun query(u: URI): Map<String, String> =
        (u.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
            val (k, v) = it.split("=", limit = 2); k to URLDecoder.decode(v, "UTF-8")
        }

    private fun frag(u: URI, fallback: String) =
        u.rawFragment?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() } ?: fallback

    private fun parseUrlStyle(link: String, proto: Proto): Profile {
        val u = URI(link); val q = query(u)
        val sec = q["security"] ?: if (proto == Proto.TROJAN) "tls" else "none"
        return Profile(
            name = frag(u, u.host), proto = proto, host = u.host, port = if (u.port > 0) u.port else 443,
            user = URLDecoder.decode(u.rawUserInfo ?: "", "UTF-8"),
            network = q["type"] ?: "tcp", security = sec,
            sni = q["sni"] ?: q["peer"] ?: "", hostHeader = q["host"] ?: "",
            path = q["path"] ?: q["serviceName"] ?: "", flow = q["flow"] ?: "",
            fingerprint = q["fp"] ?: "chrome", alpn = q["alpn"] ?: "",
            publicKey = q["pbk"] ?: "", shortId = q["sid"] ?: "",
            insecure = (q["allowInsecure"] ?: q["insecure"] ?: "0").let { it == "1" || it.equals("true", true) }
        )
    }

    private fun parseVmess(link: String): Profile {
        val j = JSONObject(b64(link.removePrefix("vmess://"))!!)
        return Profile(
            name = j.optString("ps", j.optString("add")), proto = Proto.VMESS,
            host = j.getString("add"), port = j.optString("port", "443").toInt(), user = j.getString("id"),
            network = j.optString("net", "tcp"), security = if (j.optString("tls") == "tls") "tls" else "none",
            sni = j.optString("sni"), hostHeader = j.optString("host"), path = j.optString("path"),
            alpn = j.optString("alpn"),
            insecure = j.optString("allowInsecure").let { it == "1" || it.equals("true", true) }
        )
    }

    // ssh://user:pass@host:22?sni=example.com#Name   (sni => SSH wrapped in TLS with that server name)
    private fun parseSsh(link: String): Profile {
        val u = URI(link); val q = query(u)
        val ui = URLDecoder.decode(u.rawUserInfo ?: "", "UTF-8").split(":", limit = 2)
        return Profile(
            name = frag(u, u.host), proto = Proto.SSH, host = u.host, port = if (u.port > 0) u.port else 22,
            user = ui.getOrElse(0) { "" }, secret = ui.getOrElse(1) { "" }, sni = q["sni"] ?: "",
            proxy = q["proxy"] ?: "", hostHeader = q["host"] ?: ""
        )
    }

    // slowdns://user:pass@ns.example.com?dns=8.8.8.8&key=<server public key hex>#Name
    private fun parseSlowDns(link: String): Profile {
        val u = URI(link); val q = query(u)
        val ui = URLDecoder.decode(u.rawUserInfo ?: "", "UTF-8").split(":", limit = 2)
        return Profile(
            name = frag(u, u.host), proto = Proto.SLOWDNS, host = u.host, port = 22,
            user = ui.getOrElse(0) { "" }, secret = ui.getOrElse(1) { "" },
            resolver = "8.8.8.8", publicKey = q["key"] ?: ""
        )
    }

    // ss://BASE64(method:password)@host:port#Name   or   ss://BASE64(method:password@host:port)#Name
    private fun parseSs(link: String): Profile {
        val rest = link.removePrefix("ss://")
        val name = rest.substringAfter('#', "").let { URLDecoder.decode(it, "UTF-8") }
        val main = rest.substringBefore('#').substringBefore('?')
        val cred: String; val hp: String
        if (main.contains('@')) {
            val c = main.substringBefore('@')
            cred = if (c.contains(':')) URLDecoder.decode(c, "UTF-8") else (b64(c) ?: c)
            hp = main.substringAfter('@')
        } else {
            val all = b64(main) ?: error("bad ss link")
            cred = all.substringBeforeLast('@'); hp = all.substringAfterLast('@')
        }
        val host = hp.substringBeforeLast(':'); val port = hp.substringAfterLast(':').toInt()
        return Profile(
            name = name.ifBlank { host }, proto = Proto.SHADOWSOCKS, host = host, port = port,
            secret = cred.substringBefore(':'), user = cred.substringAfter(':')
        )
    }

    private fun parseWireGuard(conf: String): Profile? {
        val endpoint = Regex("(?im)^\\s*Endpoint\\s*=\\s*(.+)$").find(conf)?.groupValues?.get(1)?.trim() ?: return null
        val host = endpoint.substringBeforeLast(":"); val port = endpoint.substringAfterLast(":").toIntOrNull() ?: 51820
        return Profile(name = "WireGuard $host", proto = Proto.WIREGUARD, host = host, port = port, raw = conf)
    }

    private fun b64(s: String): String? = runCatching {
        String(Base64.decode(s.trim(), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()
}
