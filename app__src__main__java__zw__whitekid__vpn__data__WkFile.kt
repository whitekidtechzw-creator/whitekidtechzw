package zw.whitekid.vpn.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed class ImportResult {
    data class Ok(val list: List<Profile>, val msg: String) : ImportResult()
    object NeedPassword : ImportResult()
    data class Fail(val why: String) : ImportResult()
}

/** One entry point for everything the user can paste or open: links, WireGuard configs, wk1: share files. */
object Importer {
    /** Downloads a config link (plain links, WireGuard config, wk1: code or base64 list). Call off the main thread. */
    fun fetch(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "WhitekidVPN")
        try {
            if (c.responseCode !in 200..299) error("server replied ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText().take(500_000) }
        } finally { c.disconnect() }
    }

    fun run(input: String, password: String?): ImportResult {
        val t = input.trim()
        if (t.startsWith("wk1:")) return WkFile.decode(t.filterNot { it.isWhitespace() }, password)
        val found = LinkParser.parseAny(t)
        return if (found.isEmpty()) ImportResult.Fail("Couldn't read that. Check the link, config or file.")
        else ImportResult.Ok(found, "")
    }
}

/**
 * Whitekid share file (.wkp): servers + expiry + note + lock flag, AES-GCM encrypted.
 * Without a password the key is built into the app, so this stops casual peeking, not a determined
 * attacker. With a password, only people who know it can open the file.
 */
object WkFile {
    private const val PREFIX = "wk1:"
    private const val APP_SECRET = "whitekid-tech-zw/config/v1"

    private fun key(secret: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(secret.toCharArray(), salt, 12000, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun encode(profiles: List<Profile>, expires: Long, note: String, locked: Boolean, password: String?,
               device: String = "", dataMb: Long = 0L): String {
        // an imported server that already expires can never be re-shared with a later date
        val exp = (listOf(expires) + profiles.map { it.expires }).filter { it > 0 }.minOrNull() ?: 0L
        val json = JSONObject().apply {
            put("v", 1); put("author", "Whitekid Tech"); put("created", Clock.now())
            put("expires", exp); put("note", note); put("locked", locked)
            put("device", device.trim().uppercase()); put("dataMb", dataMb)
            put("profiles", JSONArray().apply { profiles.forEach { put(it.toJson()) } })
        }.toString()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val pw = password?.takeIf { it.isNotEmpty() }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pw ?: APP_SECRET, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(json.toByteArray(Charsets.UTF_8))
        val out = byteArrayOf(if (pw != null) 1 else 0) + salt + iv + ct
        return PREFIX + Base64.encodeToString(out, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(code: String, password: String?): ImportResult {
        val raw = try {
            Base64.decode(code.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_PADDING)
        } catch (_: Exception) { return ImportResult.Fail("This file is damaged.") }
        if (raw.size < 30) return ImportResult.Fail("This file is damaged.")
        val needsPw = raw[0].toInt() == 1
        val pw = password?.takeIf { it.isNotEmpty() }
        if (needsPw && pw == null) return ImportResult.NeedPassword
        val salt = raw.copyOfRange(1, 17); val iv = raw.copyOfRange(17, 29); val ct = raw.copyOfRange(29, raw.size)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pw ?: APP_SECRET, salt), GCMParameterSpec(128, iv))
            c.doFinal(ct)
        } catch (_: Exception) {
            return ImportResult.Fail(if (needsPw) "Wrong password, or the file is damaged." else "This file is damaged.")
        }
        return try {
            val j = JSONObject(String(plain, Charsets.UTF_8))
            val exp = j.optLong("expires", 0L)
            if (exp > 0 && exp <= Clock.now())
                return ImportResult.Fail("This file expired on ${DateFormat.getDateInstance().format(Date(exp))}.")
            val dev = j.optString("device").trim().uppercase()
            if (dev.isNotEmpty() && dev != Store.deviceId)
                return ImportResult.Fail("This file is locked to another device. Your device ID is ${Store.deviceId}.")
            val dataMb = j.optLong("dataMb", 0L)
            val locked = j.optBoolean("locked", false)
            val note = j.optString("note"); val author = j.optString("author", "Whitekid Tech")
            val arr = j.getJSONArray("profiles")
            val list = (0 until arr.length()).map {
                Profile.fromJson(arr.getJSONObject(it)).copy(
                    id = UUID.randomUUID().toString(), expires = exp, locked = locked, note = note, author = author,
                    device = dev, dataLimitMb = dataMb)
            }
            ImportResult.Ok(list, note)
        } catch (_: Exception) { ImportResult.Fail("This file is damaged.") }
    }
}
