package zw.whitekid.vpn.data

import org.json.JSONObject
import java.util.UUID

enum class Proto(val label: String) {
    VLESS("VLESS"), VMESS("VMess"), TROJAN("Trojan"), WIREGUARD("WireGuard"), SSH("SSH"), SLOWDNS("SlowDNS"), SHADOWSOCKS("Shadowsocks")
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val proto: Proto,
    val host: String = "",
    val port: Int = 443,
    val user: String = "",      // uuid (vless/vmess), password (trojan), username (ssh)
    val secret: String = "",    // ssh password
    val network: String = "tcp",// tcp | ws | grpc
    val security: String = "none", // none | tls | reality
    val sni: String = "",       // TLS server name (SNI) - also used for SSH-over-TLS
    val hostHeader: String = "",// WS Host header
    val path: String = "",      // WS path / gRPC service name
    val flow: String = "",
    val fingerprint: String = "chrome",
    val alpn: String = "",
    val publicKey: String = "", // reality
    val shortId: String = "",   // reality
    val raw: String = "",       // WireGuard .conf text
    val resolver: String = "",  // SlowDNS: DNS resolver used to reach the tunnel (e.g. 8.8.8.8)
    val expires: Long = 0L,     // epoch millis, 0 = never
    val note: String = "",      // message from the file's creator
    val locked: Boolean = false,// hide host/SNI/keys in the UI
    val author: String = "",
    val device: String = "",    // locked to this device ID ("" = any device)
    val dataLimitMb: Long = 0L, // 0 = unlimited
    val sourceUrl: String = "", // config link this server was downloaded from (for updates)
    val proxy: String = "",     // optional HTTP proxy host:port for SSH
    val remoteId: String = "",  // set when the server comes from the admin panel
    val group: String = "",     // "Network" shown on the Connect screen
    val passwordHash: String = "", // SHA-256 of the password users must enter (empty = none)
    val mobileOnly: Boolean = false,
    val blockRooted: Boolean = false,
    val insecure: Boolean = false // accept self-signed TLS certificates (allowInsecure=1 in share links)
) {
    fun expired() = expires > 0 && Clock.now() >= expires

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("proto", proto.name); put("host", host); put("port", port)
        put("user", user); put("secret", secret); put("network", network); put("security", security)
        put("sni", sni); put("hostHeader", hostHeader); put("path", path); put("flow", flow)
        put("fingerprint", fingerprint); put("alpn", alpn); put("publicKey", publicKey)
        put("shortId", shortId); put("raw", raw)
        put("resolver", resolver); put("expires", expires); put("note", note); put("locked", locked); put("author", author)
        put("device", device); put("dataLimitMb", dataLimitMb); put("sourceUrl", sourceUrl); put("proxy", proxy)
        put("remoteId", remoteId); put("group", group); put("passwordHash", passwordHash)
        put("mobileOnly", mobileOnly); put("blockRooted", blockRooted); put("insecure", insecure)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.optString("id"), name = o.optString("name"), proto = Proto.valueOf(o.optString("proto")),
            host = o.optString("host"), port = o.optInt("port", 443), user = o.optString("user"),
            secret = o.optString("secret"), network = o.optString("network", "tcp"),
            security = o.optString("security", "none"), sni = o.optString("sni"),
            hostHeader = o.optString("hostHeader"), path = o.optString("path"), flow = o.optString("flow"),
            fingerprint = o.optString("fingerprint", "chrome"), alpn = o.optString("alpn"),
            publicKey = o.optString("publicKey"), shortId = o.optString("shortId"), raw = o.optString("raw"),
            resolver = o.optString("resolver"), expires = o.optLong("expires", 0L), note = o.optString("note"),
            locked = o.optBoolean("locked", false), author = o.optString("author"),
            device = o.optString("device"), dataLimitMb = o.optLong("dataLimitMb", 0L),
            sourceUrl = o.optString("sourceUrl"), proxy = o.optString("proxy"),
            remoteId = o.optString("remoteId"), group = o.optString("group"), passwordHash = o.optString("passwordHash"),
            mobileOnly = o.optBoolean("mobileOnly", false), blockRooted = o.optBoolean("blockRooted", false),
            insecure = o.optBoolean("insecure", false)
        )
    }
}
