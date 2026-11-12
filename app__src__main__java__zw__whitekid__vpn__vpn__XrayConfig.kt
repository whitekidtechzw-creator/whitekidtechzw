package zw.whitekid.vpn.vpn

import org.json.JSONArray
import org.json.JSONObject
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.data.Proto

object XrayConfig {
    const val SOCKS_IN = 10808   // Xray inbound the TUN bridge talks to
    const val SSH_SOCKS = 10809  // local SOCKS5 exposed by SshEngine

    /** withTun adds Xray's own TUN inbound (the phone's traffic); the SOCKS inbound is always there for the self-test. */
    fun build(p: Profile, dns: List<String>, withTun: Boolean = true, mtu: Int = 1400, heal: Int = 0): String {
        val viaProxy = p.proto != Proto.SSH && p.proto != Proto.SLOWDNS
        val hosts = JSONObject()
        val servers = JSONArray().apply {
            // The proxy server's own name must be looked up BEFORE the tunnel exists. If it went through tcp://8.8.8.8 it
            // would wait for a tunnel that isn't up yet and time out (the "slow DNS" stall). Resolve it with Google DNS
            // directly (this app is outside the VPN), and keep the phone's resolver as a backup for that one name.
            if (viaProxy && !DnsResolve.isIp(p.host)) {
                DnsResolve.resolve(p.host, dns)?.let { hosts.put(p.host, it) }
                put(JSONObject().put("address", "localhost").put("domains", JSONArray().put("full:${p.host}")))
            }
            dns.forEach { put(dnsServer(it, heal)) }
            // backup chain: if the chosen resolver stalls, the next ones answer instead of the lookup hanging
            listOf("8.8.8.8", "1.1.1.1", "9.9.9.9").filter { it !in dns }.take(2).forEach { put("tcp://$it") }
            put("localhost")
        }
        val outbound = if (p.proto == Proto.SSH || p.proto == Proto.SLOWDNS) sshOutbound() else proxyOutbound(p, heal)
        val sniff = JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls"))
        val inboundsArr = JSONArray().put(JSONObject().apply {
            put("tag", "socks-in"); put("port", SOCKS_IN); put("listen", "127.0.0.1"); put("protocol", "socks")
            put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            put("sniffing", sniff)
        })
        if (withTun) inboundsArr.put(JSONObject().put("tag", "tun-in").put("protocol", "tun")
            .put("settings", JSONObject().put("name", "xray0").put("MTU", mtu).put("userLevel", 0))
            .put("sniffing", sniff))
        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            // UseIPv4: the tunnel is IPv4 only, so AAAA lookups only double the traffic (very costly over SSH/SlowDNS)
            put("dns", JSONObject().put("servers", servers).put("hosts", hosts).put("queryStrategy", "UseIPv4"))
            put("inbounds", inboundsArr)
            put("outbounds", JSONArray().put(outbound.put("tag", "proxy"))
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                .put(JSONObject().put("tag", "dns-out").put("protocol", "dns")))
            put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray().put(
                // only the phone's UDP DNS goes to Xray's resolver; the resolver's own TCP queries to 8.8.8.8 must NOT match
                // this rule or they loop back into it
                JSONObject().put("type", "field").put("network", "udp").put("port", "53").put("outboundTag", "dns-out"))))
        }.toString()
    }

    private val DOH = mapOf(
        "8.8.8.8" to "https://8.8.8.8/dns-query", "8.8.4.4" to "https://8.8.4.4/dns-query",
        "1.1.1.1" to "https://1.1.1.1/dns-query", "1.0.0.1" to "https://1.0.0.1/dns-query",
        "9.9.9.9" to "https://9.9.9.9/dns-query", "149.112.112.112" to "https://149.112.112.112/dns-query")

    /** Plain DNS over TCP, or DNS-over-HTTPS when Secure DNS is on and the resolver supports it. */
    private fun dnsServer(ip: String, heal: Int): String = if (Store.doh && heal < 2) (DOH[ip] ?: "tcp://$ip") else "tcp://$ip"

    private fun sshOutbound() = JSONObject().put("protocol", "socks").put("settings",
        JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", SSH_SOCKS))))

    private fun proxyOutbound(p: Profile, heal: Int): JSONObject {
        val o = JSONObject()
        when (p.proto) {
            Proto.VLESS -> {
                o.put("protocol", "vless")
                o.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", p.host).put("port", p.port)
                    .put("users", JSONArray().put(JSONObject().put("id", p.user).put("encryption", "none")
                        .apply { if (p.flow.isNotBlank()) put("flow", p.flow) })))))
            }
            Proto.VMESS -> {
                o.put("protocol", "vmess")
                o.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", p.host).put("port", p.port)
                    .put("users", JSONArray().put(JSONObject().put("id", p.user).put("alterId", 0).put("security", "auto"))))))
            }
            Proto.TROJAN -> {
                o.put("protocol", "trojan")
                o.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", p.host).put("port", p.port).put("password", p.user))))
            }
            Proto.SHADOWSOCKS -> {
                o.put("protocol", "shadowsocks")
                o.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", p.host).put("port", p.port).put("method", p.secret).put("password", p.user))))
            }
            else -> error("not an Xray protocol")
        }
        if (p.proto != Proto.SHADOWSOCKS) o.put("streamSettings", stream(p))
        if (Store.mux && heal < 2 && (p.proto == Proto.VLESS || p.proto == Proto.VMESS || p.proto == Proto.TROJAN))
            o.put("mux", JSONObject().put("enabled", true).put("concurrency", 8))
        return o
    }

    private fun stream(p: Profile): JSONObject {
        val s = JSONObject().put("network", p.network).put("security", p.security)
        val name = p.sni.ifBlank { p.hostHeader.ifBlank { p.host } }
        when (p.security) {
            "tls" -> s.put("tlsSettings", JSONObject().put("serverName", name)
                .put("fingerprint", p.fingerprint).put("allowInsecure", p.insecure)
                .apply { if (p.alpn.isNotBlank()) put("alpn", JSONArray(p.alpn.split(","))) })
            "reality" -> s.put("realitySettings", JSONObject().put("serverName", name)
                .put("fingerprint", p.fingerprint).put("publicKey", p.publicKey).put("shortId", p.shortId))
        }
        when (p.network) {
            "ws" -> s.put("wsSettings", JSONObject().put("path", p.path.ifBlank { "/" })
                .put("headers", JSONObject().put("Host", p.hostHeader.ifBlank { name })))
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", p.path))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", p.path.ifBlank { "/" }).put("host", p.hostHeader.ifBlank { name }))
            "xhttp", "splithttp" -> {
                s.put("network", "xhttp")
                s.put("xhttpSettings", JSONObject().put("path", p.path.ifBlank { "/" }).put("host", p.hostHeader.ifBlank { name }))
            }
        }
        return s
    }
}
