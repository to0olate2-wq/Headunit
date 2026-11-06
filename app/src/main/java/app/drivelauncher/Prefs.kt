package app.drivelauncher

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp == null) sp = ctx.applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)
    }

    private val p: SharedPreferences get() = sp ?: throw IllegalStateException("Prefs not initialised")

    /** glass | sport | classic (see [Skin]) */
    var skin: String get() = gs("skin", Skin.GLASS); set(v) = ss("skin", v)
    /** Each theme remembers its own accent colour. */
    var accent: Int
        get() = p.getInt("accent_$skin", if (skin == Skin.CLASSIC) p.getInt("accent", Skin.defaultAccent(skin)) else Skin.defaultAccent(skin))
        set(v) = p.edit().putInt("accent_$skin", v).apply()
    /** Show the posted speed limit from OpenStreetMap. */
    var roadLimits: Boolean get() = gb("roadLimits", true); set(v) = sb("roadLimits", v)
    /** Beep above the road's limit: -1 off, else allowed extra km/h. */
    var roadWarn: Int get() = gi("roadWarn", -1); set(v) = si("roadWarn", v)
    /** Louder music at speed: 0 off, 1 low, 2 medium, 3 high. */
    var speedVolume: Int get() = gi("speedVolume", 0); set(v) = si("speedVolume", v)
    /** Video wallpaper (content URI kept with a persistent permission). */
    var videoUri: String get() = gs("videoUri", ""); set(v) = ss("videoUri", v)
    /** Floating panel over other apps: mini music player and next turn (the speedometer is [bubble]). */
    var floatMusic: Boolean get() = gb("floatMusic", false); set(v) = sb("floatMusic", v)
    var floatTurn: Boolean get() = gb("floatTurn", false); set(v) = sb("floatTurn", v)
    /** Favourite contacts (JSON [{name, number}]). */
    var phoneFavs: String get() = gs("phoneFavs", "[]"); set(v) = ss("phoneFavs", v)
    /** Read new messages aloud: 0 off, 1 while driving, 2 always. */
    var readMsgs: Int get() = gi("readMsgs", 1); set(v) = si("readMsgs", v)
    /** Read the message text too (not only who sent it). */
    var readText: Boolean get() = gb("readText", true); set(v) = sb("readText", v)
    /** Automatic reply while driving (off until the owner turns it on). */
    var autoReply: Boolean get() = gb("autoReply", false); set(v) = sb("autoReply", v)
    var autoReplyText: String get() = gs("autoReplyText", "I'm driving right now, I'll reply soon."); set(v) = ss("autoReplyText", v)
    /** Icon pack package ("" = the apps' own icons). */
    var iconPack: String get() = gs("iconPack", ""); set(v) = ss("iconPack", v)
    /** Fuel tank size in litres (a 2015 Sonata has 70 L). */
    var tankL: Int get() = gi("tankL", 70); set(v) = si("tankL", v)
    /** Saved destinations (JSON, see [Places]). */
    var places: String get() = gs("places", ""); set(v) = ss("places", v)
    /** auto | maps | waze */
    var navApp: String get() = gs("navApp", "auto"); set(v) = ss("navApp", v)
    /** Paint colour of the car picture (see [CarArt.COLORS]). */
    var carColor: String get() = gs("carColor", "silver"); set(v) = ss("carColor", v)
    /** Show the owner's own car photo (files/car.png) instead of the rendered car. */
    var carPhoto: Boolean get() = gb("carPhoto", false); set(v) = sb("carPhoto", v)
    /** Animations: page transitions, glowing background, spinning wheels. */
    var motion: Boolean get() = gb("motion", true); set(v) = sb("motion", v)
    var imperial: Boolean
        get() = p.getBoolean("imperial", false)
        set(v) = p.edit().putBoolean("imperial", v).apply()
    var fullscreen: Boolean
        get() = p.getBoolean("fullscreen", false)
        set(v) = p.edit().putBoolean("fullscreen", v).apply()
    /** auto = paired Bluetooth adapter found by name, bt = chosen device, wifi, off */
    var obdMode: String
        get() = p.getString("obdMode", "auto") ?: "auto"
        set(v) = p.edit().putString("obdMode", v).apply()
    var obdAddr: String
        get() = p.getString("obdAddr", "") ?: ""
        set(v) = p.edit().putString("obdAddr", v).apply()
    var obdHost: String
        get() = p.getString("obdHost", "192.168.0.10:35000") ?: "192.168.0.10:35000"
        set(v) = p.edit().putString("obdHost", v).apply()
    var mapZoom: Int
        get() = p.getInt("mapZoom", 16)
        set(v) = p.edit().putInt("mapZoom", v).apply()
    var mediaPin: String
        get() = p.getString("mediaPin", "") ?: ""
        set(v) = p.edit().putString("mediaPin", v).apply()

    // ---- generic accessors for the newer settings ----
    fun gs(k: String, d: String = ""): String = p.getString(k, d) ?: d
    fun ss(k: String, v: String) = p.edit().putString(k, v).apply()
    fun gi(k: String, d: Int): Int = p.getInt(k, d)
    fun si(k: String, v: Int) = p.edit().putInt(k, v).apply()
    fun gl(k: String, d: Long): Long = p.getLong(k, d)
    fun sl(k: String, v: Long) = p.edit().putLong(k, v).apply()
    fun gb(k: String, d: Boolean): Boolean = p.getBoolean(k, d)
    fun sb(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    /** auto | day | night */
    var theme: String get() = gs("theme", "auto"); set(v) = ss("theme", v)
    var autoBright: Boolean get() = gb("autoBright", false); set(v) = sb("autoBright", v)
    var nightBright: Int get() = gi("nightBright", 40); set(v) = si("nightBright", v)
    /** plain | stars | moving | photo */
    var background: String get() = gs("bg", "plain"); set(v) = ss("bg", v)
    /** Speed warning in the display unit (km/h or mph); 0 = off */
    var speedLimit: Int get() = gi("speedLimit", 0); set(v) = si("speedLimit", v)
    var bubble: Boolean get() = gb("bubble", false); set(v) = sb("bubble", v)
    var bubbleX: Int get() = gi("bubbleX", 40); set(v) = si("bubbleX", v)
    var bubbleY: Int get() = gi("bubbleY", 120); set(v) = si("bubbleY", v)
    /** off | zlink | <package name> */
    var autoApp: String get() = gs("autoApp", "zlink"); set(v) = ss("autoApp", v)
    var autoPlay: Boolean get() = gb("autoPlay", true); set(v) = sb("autoPlay", v)
    /** seconds before coming back to the dashboard after auto-start; 0 = stay in the app */
    var autoReturn: Int get() = gi("autoReturn", 0); set(v) = si("autoReturn", v)
    var lastBoot: Int get() = gi("lastBoot", -1); set(v) = si("lastBoot", v)
    /** minutes parked before the screen saver shows; 0 = off */
    var saverMin: Int get() = gi("saverMin", 0); set(v) = si("saverMin", v)
    var splitApp: String get() = gs("splitApp", ""); set(v) = ss("splitApp", v)
    /** Home pages (JSON). Each theme keeps its own; Classic also reads the layout saved before themes existed. */
    var pages: String
        get() = gs("pages_$skin", if (skin == Skin.CLASSIC) gs("pages", "") else "")
        set(v) = ss("pages_$skin", v)
    var currency: String get() = gs("currency", defaultCurrency()); set(v) = ss("currency", v)

    private fun defaultCurrency(): String = try {
        java.util.Currency.getInstance(Locale.getDefault()).symbol
    } catch (_: Exception) { "$" }

    fun saveTrip(m: Double, movingMs: Long, maxKmh: Float, start: Long) {
        p.edit().putFloat("tripM", m.toFloat()).putLong("tripMs", movingMs).putFloat("tripMax", maxKmh).putLong("tripStart", start).apply()
    }
    val tripM: Double get() = p.getFloat("tripM", 0f).toDouble()
    val tripMs: Long get() = p.getLong("tripMs", 0L)
    val tripMax: Float get() = p.getFloat("tripMax", 0f)
    val tripStart: Long get() = p.getLong("tripStart", 0L)
}

object Units {
    val imp: Boolean get() = Prefs.imperial
    fun speed(kmh: Float): Float = if (imp) kmh * 0.621371f else kmh
    val speedUnit: String get() = if (imp) "MPH" else "KM/H"
    val speedUnitLower: String get() = if (imp) "mph" else "km/h"
    fun dist(m: Double): Double = if (imp) m / 1609.344 else m / 1000.0
    val distUnit: String get() = if (imp) "mi" else "km"
    fun temp(c: Float): Float = if (imp) c * 9f / 5f + 32f else c
    fun alt(m: Double): Double = if (imp) m * 3.28084 else m
    val altUnit: String get() = if (imp) "ft" else "m"
}

/** In-app diagnostics: what the launcher sees from media apps, notifications and OBD. */
object Diag {
    class Note(val time: Long, val pkg: String, val title: String, val text: String, val flags: String)

    private val lines = ArrayDeque<String>()
    val notes = ArrayDeque<Note>()
    @Volatile var version = 0
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(s: String) {
        synchronized(lines) {
            lines.addLast(tf.format(Date()) + "  " + s)
            while (lines.size > 400) lines.removeFirst()
        }
        version++
    }

    fun note(pkg: String, title: String, text: String, flags: String) {
        synchronized(notes) {
            notes.removeAll { it.pkg == pkg && it.title == title && it.text == text }
            notes.addFirst(Note(System.currentTimeMillis(), pkg, title, text, flags))
            while (notes.size > 25) notes.removeLast()
        }
        version++
    }

    fun recentNotes(n: Int): List<Note> = synchronized(notes) { notes.take(n) }
    fun recentLines(n: Int): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun dump(extra: String): String {
        val sb = StringBuilder()
        sb.append("Drive Launcher diagnostics\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}\n\n")
        sb.append(extra).append("\n\nRecent notifications:\n")
        for (n in recentNotes(25)) sb.append("${tf.format(Date(n.time))} ${n.pkg} | ${n.title} | ${n.text} | ${n.flags}\n")
        sb.append("\nLog:\n")
        for (l in recentLines(400)) sb.append(l).append('\n')
        return sb.toString()
    }

    /** Writes the log to Downloads so it can be copied off the unit. Returns where it went. */
    fun save(ctx: Context, extra: String): String {
        val text = dump(extra)
        val name = "DriveLauncher-log-${System.currentTimeMillis() / 1000}.txt"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create file in Downloads")
            val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Could not open file")
            os.use { it.write(text.toByteArray()) }
            "Downloads/$name"
        } else {
            val f = File(ctx.getExternalFilesDir(null), name)
            f.writeText(text)
            f.absolutePath
        }
    }
}
