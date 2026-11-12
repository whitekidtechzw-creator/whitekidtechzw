package zw.whitekid.vpn.data

object Clock {
    /** Wall clock that can't be wound back past the latest time the app has already seen. */
    fun now(): Long {
        val last = Store.lastSeen
        val n = maxOf(System.currentTimeMillis(), last)
        if (n - last > 60_000) Store.lastSeen = n
        return n
    }
}
