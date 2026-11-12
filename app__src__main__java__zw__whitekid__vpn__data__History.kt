package zw.whitekid.vpn.data

import org.json.JSONArray
import org.json.JSONObject

/** Last 50 connection sessions plus lifetime totals. */
object History {
    data class Entry(val name: String, val start: Long, val seconds: Long, val rx: Long, val tx: Long)

    @Synchronized
    fun add(e: Entry) {
        val arr = runCatching { JSONArray(Store.historyJson) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("n", e.name).put("t", e.start).put("s", e.seconds).put("r", e.rx).put("x", e.tx))
        while (arr.length() > 50) arr.remove(0)
        Store.historyJson = arr.toString()
        Store.totalRx += e.rx; Store.totalTx += e.tx; Store.sessions += 1
        if (e.seconds > Store.longestSec) Store.longestSec = e.seconds
    }

    fun list(): List<Entry> {
        val arr = runCatching { JSONArray(Store.historyJson) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.optString("n"), o.optLong("t"), o.optLong("s"), o.optLong("r"), o.optLong("x"))
        }.reversed()
    }

    fun clear() { Store.historyJson = "[]"; Store.totalRx = 0; Store.totalTx = 0; Store.sessions = 0; Store.longestSec = 0 }
}
