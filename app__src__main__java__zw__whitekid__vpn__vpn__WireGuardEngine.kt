package zw.whitekid.vpn.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream

/** WireGuard runs through the official wireguard-android GoBackend (it hosts its own VpnService). */
object WireGuardEngine {
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "whitekid"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    /** Insert/replace a key inside the [Interface] section of a .conf. */
    private fun setIface(conf: String, key: String, value: String): String {
        val lines = conf.lines().filterNot { it.trim().startsWith("$key", ignoreCase = true) && it.contains("=") }.toMutableList()
        val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        lines.add(i + 1, "$key = $value")
        return lines.joinToString("\n")
    }

    fun up(ctx: Context, conf: String, dns: List<String>, bypass: Set<String>, only: Boolean = false) {
        var c = setIface(conf, "DNS", dns.joinToString(", "))
        if (bypass.isNotEmpty()) c = setIface(c, if (only) "IncludedApplications" else "ExcludedApplications", bypass.joinToString(", "))
        val cfg = Config.parse(ByteArrayInputStream(c.toByteArray()))
        val b = backend ?: GoBackend(ctx.applicationContext).also { backend = it }
        b.setState(tunnel, Tunnel.State.UP, cfg)
    }

    fun down(ctx: Context) {
        backend?.setState(tunnel, Tunnel.State.DOWN, null)
    }
}
