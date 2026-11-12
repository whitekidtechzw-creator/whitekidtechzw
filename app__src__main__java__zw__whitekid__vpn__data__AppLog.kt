package zw.whitekid.vpn.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small connection log: last 500 lines in memory, mirrored to a file so it survives restarts. Never log secrets. */
object AppLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private var file: File? = null
    private val fmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "wk.log")
        lines.value = runCatching { file!!.readLines().takeLast(300) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.value = (lines.value + line).takeLast(500)
        runCatching {
            val f = file ?: return
            if (f.length() > 200_000) f.writeText(lines.value.joinToString("\n") + "\n") else f.appendText(line + "\n")
        }
    }

    @Synchronized
    fun clear() {
        lines.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun text(): String = lines.value.joinToString("\n")
}
