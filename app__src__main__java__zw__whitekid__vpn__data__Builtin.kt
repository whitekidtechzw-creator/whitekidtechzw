package zw.whitekid.vpn.data

import zw.whitekid.vpn.BuildConfig

/** The owner's own SlowDNS server, baked into the app at build time from GitHub secrets. Always uses Google DNS 8.8.8.8. */
object Builtin {
    fun slowDns(): Profile? {
        val ns = BuildConfig.SLOWDNS_NS.trim(); val key = BuildConfig.SLOWDNS_KEY.trim()
        if (ns.isEmpty() || key.isEmpty()) return null
        return Profile(
            id = "builtin_slowdns", name = "Whitekid SlowDNS", proto = Proto.SLOWDNS, host = ns, port = 22,
            user = BuildConfig.SLOWDNS_USER, secret = BuildConfig.SLOWDNS_PASS, publicKey = key, resolver = "8.8.8.8",
            locked = true, group = "Whitekid", author = "Whitekid Tech", note = "SlowDNS through Google DNS 8.8.8.8"
        )
    }

    /** Adds or refreshes the built-in server. Returns true if the list changed. */
    fun ensure(list: MutableList<Profile>): Boolean {
        val b = slowDns() ?: return false
        val i = list.indexOfFirst { it.id == b.id }
        if (i < 0) { list.add(0, b); return true }
        if (list[i].copy(expires = 0L) != b.copy(expires = 0L)) { list[i] = b; return true }
        return false
    }
}
