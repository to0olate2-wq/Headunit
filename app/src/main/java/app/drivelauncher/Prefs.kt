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

    var accent: Int
        get() = p.getInt("accent", 0xFFFF9F43.toInt())
        set(v) = p.edit().putInt("accent", v).apply()
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
