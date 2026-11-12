package zw.whitekid.vpn.vpn

import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Free Cloudflare WARP over WireGuard: registers a fresh account and builds a WireGuard profile. Call off the main thread. */
object Warp {
    fun create(): Profile {
        val kp = KeyPair()
        val priv = kp.privateKey.toBase64(); val pub = kp.publicKey.toBase64()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = JSONObject().put("key", pub).put("install_id", "").put("fcm_token", "").put("tos", iso)
            .put("type", "Android").put("model", "PC").put("locale", "en_US").toString()
        val c = URL("https://api.cloudflareclient.com/v0a2158/reg").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("CF-Client-Version", "a-6.3-2158")
            c.setRequestProperty("User-Agent", "okhttp/3.12.1")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("Cloudflare replied ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val cfg = j.getJSONObject("config")
            val v4 = cfg.getJSONObject("interface").getJSONObject("addresses").getString("v4")
            val peerKey = cfg.getJSONArray("peers").getJSONObject(0).getString("public_key")
            val conf = "[Interface]\nPrivateKey = $priv\nAddress = $v4/32\nMTU = 1280\n\n" +
                "[Peer]\nPublicKey = $peerKey\nAllowedIPs = 0.0.0.0/0\nEndpoint = engage.cloudflareclient.com:2408\n"
            return Profile(name = "Cloudflare WARP (free)", proto = Proto.WIREGUARD,
                host = "engage.cloudflareclient.com", port = 2408, raw = conf)
        } finally { c.disconnect() }
    }
}
