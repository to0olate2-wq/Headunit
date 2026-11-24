package app.drivelauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock

/**
 * CarPlay / Android Auto through the unit's ZLink app (0.6.29).
 *
 * ZLink draws the phone's screen itself (a video), so the launcher can only use what ZLink shares
 * with other apps:
 *  - its status broadcast (action `com.zjinnova.zlink`, extras `status` and `phoneMode`), which says
 *    whether a phone is connected and how (CarPlay / Android Auto, wired / wireless);
 *  - its notifications: song info goes to [MediaHub]; text that reads like a turn ("200 m · Turn
 *    right") goes to [NavHub] so the home cards and the floating pill show it;
 *  - media sessions and Bluetooth AVRCP song info (already handled by [MediaHub]).
 * Everything ZLink sends is written to the diagnostics log, so a report from the unit shows what
 * that ZLink version really shares.
 */
object Projection {
    const val ACTION = "com.zjinnova.zlink"
    private val WORDS = listOf("zlink", "zjinnova", "carlink", "autokit", "carplay", "phonemirror", "speedplay",
        "tlink.", "androidauto", "android.projection", "easyconn")

    @Volatile var status = ""
        private set
    @Volatile var mode = ""
        private set
    private var statusAt = 0L
    private var registered = false

    /** A phone is connected through ZLink right now (from ZLink's own status broadcast). */
    val connected: Boolean get() = status.isNotEmpty() && isConnected(status)

    fun isConnected(s: String): Boolean {
        val u = s.uppercase()
        if (u.contains("DISCONNECT") || u.contains("FAIL") || u.contains("IDLE") || u.contains("CALL_OFF")) return false
        return u.contains("CONNECTED") || u.contains("START") || u.contains("CALL_ON") || u.contains("RUNNING")
    }

    fun isProjection(pkg: String): Boolean { val p = pkg.lowercase(); return WORDS.any { p.contains(it) } }

    /** "CarPlay" / "Android Auto" / "Mirror" from ZLink's phoneMode (e.g. carplay_wireless). */
    fun modeName(m: String = mode): String {
        val l = m.lowercase()
        return when {
            l.startsWith("carplay") || l.startsWith("airplay") -> "CarPlay"
            l.contains("auto") || l.startsWith("aa") -> "Android Auto"
            l.contains("mirror") -> "screen mirroring"
            else -> "the phone"
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { onStatus(i) } catch (e: Exception) { Diag.log("zlink broadcast: ${e.message}") }
        }
    }

    fun init(ctx: Context) {
        if (registered) return
        registered = true
        val f = IntentFilter(ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.applicationContext.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else ctx.applicationContext.registerReceiver(receiver, f)
        } catch (e: Exception) {
            registered = false
            Diag.log("zlink: receiver failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun onStatus(i: Intent) {
        val ex = i.extras
        val all = ex?.keySet()?.joinToString(", ") { k -> "$k=${ex.get(k)?.toString()?.take(60)}" } ?: ""
        Diag.log("zlink says: $all")
        val s = ex?.get("status")?.toString() ?: ""
        val m = ex?.get("phoneMode")?.toString() ?: ""
        if (s.isEmpty() && m.isEmpty()) return
        // calls also come as status (PHONE_CALL_ON / OFF): they mean a phone is there, not that it left
        if (s.isNotEmpty() && !s.uppercase().startsWith("PHONE_CALL")) status = s
        else if (s.uppercase() == "PHONE_CALL_ON" && status.isEmpty()) status = "CONNECTED"
        if (m.isNotEmpty()) mode = m
        statusAt = SystemClock.elapsedRealtime()
        MediaHub.pick()
    }

    /** For the screen tests. */
    fun setForTest(s: String, m: String) { status = s; mode = m }

    // ---------- navigation text inside a ZLink notification ----------

    private val DIST = Regex("""(?i)(^|\s)\d+([.,]\d+)?\s?(m|km|ft|mi|yd|yards|miles|meters|metres|م|كم)(\s|$|[.,·])""")
    private val TURN = listOf("turn ", "keep ", "exit", "roundabout", "merge", "u-turn", "make a u", "head ", "continue",
        "arrive", "destination", "take the", "slight ", "sharp ", "bear ", "ramp", "towards",
        "انعطف", "استمر", "دوار", "مخرج", "الوجهة", "اتجه", "خذ")

    /** True when a projection notification reads like a navigation instruction, not a song. */
    fun looksLikeTurn(title: String, text: String): Boolean {
        val all = "$title $text".lowercase()
        val turn = TURN.any { all.contains(it) }
        val dist = DIST.containsMatchIn(title) || DIST.containsMatchIn(text)
        val eta = Regex("""(?i)\b(eta|min|mins|minutes|arrival)\b""").containsMatchIn(all)
        return (turn && (dist || eta)) || (dist && eta)
    }

    fun describe(ctx: Context): String {
        val z = Apps.findZlink(ctx) ?: return "ZLink app: not found"
        val ago = if (statusAt == 0L) "" else " (${(SystemClock.elapsedRealtime() - statusAt) / 1000} s ago)"
        return "ZLink app: $z, status ${status.ifEmpty { "not reported yet" }}${if (mode.isNotEmpty()) ", $mode" else ""}$ago"
    }
}
