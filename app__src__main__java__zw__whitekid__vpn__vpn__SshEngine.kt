package zw.whitekid.vpn.vpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import zw.whitekid.vpn.data.Profile
import zw.whitekid.vpn.data.Proto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * SSH tunnel: opens an SSH session (optionally wrapped in TLS with a custom SNI) and exposes a
 * local SOCKS5 proxy (TCP CONNECT) that forwards through SSH direct-tcpip channels.
 */
class SshEngine(private val p: Profile, private val protect: (Socket) -> Boolean) {
    private var session: Session? = null
    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var stopped = false

    /** true while the SSH session is up (the watchdog reconnects when this turns false) */
    fun isAlive(): Boolean = running && session?.isConnected == true

    /**
     * SSL/SNI servers usually sit behind a self-signed or unrelated certificate, so the TLS wrapper accepts any
     * certificate. It only disguises the connection; SSH itself still encrypts everything inside it.
     */
    private val trustAllFactory: SSLSocketFactory by lazy {
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom()) }.socketFactory
    }

    private inner class Factory : SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val raw = Socket()
            protect(raw)                                   // keep the SSH socket outside the VPN
            if (p.proxy.isNotBlank()) {
                val ph = p.proxy.substringBeforeLast(":")
                val pp = p.proxy.substringAfterLast(":").toIntOrNull() ?: 8080
                raw.connect(InetSocketAddress(DnsResolve.resolve(ph) ?: ph, pp), 15000)
                val out = raw.getOutputStream(); val inp = raw.getInputStream()
                out.write("CONNECT $host:$port HTTP/1.1\r\nHost: ${p.hostHeader.ifBlank { "$host:$port" }}\r\n\r\n".toByteArray())
                out.flush()
                val sb = StringBuilder()
                while (!sb.endsWith("\r\n\r\n")) {
                    val b = inp.read(); if (b < 0) error("HTTP proxy closed the connection")
                    sb.append(b.toChar()); if (sb.length > 8192) error("HTTP proxy reply too long")
                }
                val status = sb.lineSequence().first()
                if (status.split(" ").getOrNull(1) != "200") error("HTTP proxy refused: $status")
            } else {
                raw.connect(InetSocketAddress(DnsResolve.resolve(host) ?: host, port), 15000)
            }
            if (p.sni.isBlank()) return raw
            val ssl = trustAllFactory.createSocket(raw, p.sni, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(SNIHostName(p.sni)) }
            ssl.startHandshake()
            return ssl
        }
        override fun getInputStream(s: Socket): InputStream = s.getInputStream()
        override fun getOutputStream(s: Socket): OutputStream = s.getOutputStream()
    }

    fun start() {
        val js = JSch()
        val s = js.getSession(p.user, p.host, p.port).apply {
            setPassword(p.secret)
            setConfig("StrictHostKeyChecking", "no")
            setSocketFactory(Factory())
            serverAliveInterval = 15000
            setServerAliveCountMax(6)
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            if (p.proto == Proto.SLOWDNS) {            // DNS tunnels are slow, so squeeze the data
                setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
                setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            }
        }
        session = s                                    // visible to stop() while connecting, so Disconnect can interrupt a slow login
        s.connect(if (p.proto == Proto.SLOWDNS) 120000 else 45000)
        if (stopped) { runCatching { s.disconnect() }; error("stopped") }
        server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), XrayConfig.SSH_SOCKS)) }
        running = true
        thread(name = "ssh-socks", isDaemon = true) {
            while (running) {
                val c = try { server!!.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    private fun handle(c: Socket) {
        val i = c.getInputStream(); val o = c.getOutputStream()
        // greeting
        if (i.read() != 5) return
        val n = i.read(); i.skip(n.toLong()); o.write(byteArrayOf(5, 0)); o.flush()
        // request
        val hdr = ByteArray(4); readFully(i, hdr)
        if (hdr[1].toInt() != 1) { o.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return } // CONNECT only
        val host = when (hdr[3].toInt()) {
            1 -> ByteArray(4).also { readFully(i, it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
            3 -> ByteArray(i.read()).also { readFully(i, it) }.toString(Charsets.UTF_8)
            4 -> ByteArray(16).also { readFully(i, it) }.let { InetAddress.getByAddress(it).hostAddress!! }
            else -> return
        }
        val port = (i.read() shl 8) or i.read()
        val ch = session!!.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost(host); ch.setPort(port)
        val chIn = ch.inputStream; val chOut = ch.outputStream
        ch.connect(if (p.proto == Proto.SLOWDNS) 30000 else 10000)   // DNS tunnels need longer to open a channel
        o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
        val t = thread(isDaemon = true) { runCatching { chIn.copyTo(o) }; runCatching { c.close() } }
        runCatching { i.copyTo(chOut) }
        runCatching { ch.disconnect() }
        t.join(500)
    }

    private fun readFully(i: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) { val r = i.read(b, off, b.size - off); if (r < 0) error("eof"); off += r }
    }

    fun stop() {
        stopped = true
        running = false
        runCatching { server?.close() }
        runCatching { session?.disconnect() }
        server = null; session = null
    }
}
