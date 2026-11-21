package app.drivelauncher

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ notices

/** One gentle banner at a time at the top of the launcher ("update ready", "time for a break"). Calls and messages come first. */
object Notices {
    class N(val key: String, val ic: Ic, val title: String, val text: String, val action: String?, val until: Long, val run: ((MainActivity) -> Unit)?)

    @Volatile var current: N? = null
        private set
    @Volatile var version = 0
        private set

    fun post(n: N) { current = n; version++ }
    fun clear(key: String) { if (current?.key == key) { current = null; version++ } }
}

// ------------------------------------------------------------------ backup

/**
 * Backup = one zip file: `backup.json` (every setting: theme, home pages, dock, places, favourites, odometer…) and
 * `files/` (trips, fuel and service logs, your logo, background and car photos).
 */
object Backup {
    const val AUTO_NAME = "DriveLauncher-backup.zip"
    private const val KIND = "drive-launcher-backup"

    fun fileName(): String = "DriveLauncher-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"

    fun write(ctx: Context, out: OutputStream) {
        val zip = ZipOutputStream(BufferedOutputStream(out))
        val prefs = JSONArray()
        for ((k, v) in Prefs.all()) {
            val o = JSONObject().put("k", k)
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            prefs.put(o)
        }
        val js = JSONObject().put("kind", KIND).put("version", BuildConfig.VERSION_NAME).put("time", System.currentTimeMillis())
            .put("prefs", prefs)
        zip.putNextEntry(ZipEntry("backup.json"))
        zip.write(js.toString(1).toByteArray())
        zip.closeEntry()
        val base = ctx.filesDir
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            zip.putNextEntry(ZipEntry("files/" + f.relativeTo(base).path.replace(File.separatorChar, '/')))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.close()
    }

    /**
     * Puts a backup back: settings and data files are replaced. Returns "version, date" of the backup.
     * The app must restart afterwards ([restart]) so nothing old stays in memory.
     */
    fun restore(ctx: Context, inp: InputStream): String {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var info: JSONObject? = null
        ZipInputStream(BufferedInputStream(inp)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == "backup.json") info = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                else if (e.name.startsWith("files/")) {
                    val f = File(tmp, e.name.removePrefix("files/"))
                    if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue // never write outside the folder
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { z.copyTo(it) }
                }
            }
        }
        val j = info
        if (j == null || j.optString("kind") != KIND) { tmp.deleteRecursively(); throw IOException("This file isn't a Drive Launcher backup") }
        val list = j.getJSONArray("prefs")
        val map = HashMap<String, Any>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            map[o.getString("k")] = when (o.optString("t")) {
                "b" -> o.getBoolean("v")
                "i" -> o.getInt("v")
                "l" -> o.getLong("v")
                "f" -> o.getDouble("v").toFloat()
                "set" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> o.getString("v")
            }
        }
        Prefs.replaceAll(map)
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        tmp.walkTopDown().filter { it.isFile }.forEach { f ->
            val d = File(ctx.filesDir, f.relativeTo(tmp).path)
            d.parentFile?.mkdirs()
            f.copyTo(d, overwrite = true)
        }
        tmp.deleteRecursively()
        return "${j.optString("version")}, ${Fmt.dateLong(j.optLong("time"))}"
    }

    /** Copy in the Downloads folder (Android 10 and newer); the same file is updated each time. */
    fun toDownloads(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var uri: Uri? = null
            cr.query(col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(AUTO_NAME), null)?.use { c ->
                if (c.moveToFirst()) uri = ContentUris.withAppendedId(col, c.getLong(0))
            }
            val u = uri ?: cr.insert(col, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, AUTO_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            }) ?: throw IOException("Couldn't create the file in Downloads")
            (cr.openOutputStream(u, "wt") ?: throw IOException("Couldn't open the file")).use { write(ctx, it) }
            Prefs.sl("lastBackup", System.currentTimeMillis())
            return "Downloads/$AUTO_NAME"
        }
        throw IOException("Saving to Downloads needs Android 10 or newer")
    }

    /** Once a day (from the background service): refresh the copy in Downloads. */
    fun auto(ctx: Context) {
        if (!Prefs.autoBackup || Build.VERSION.SDK_INT < 29) return
        val now = System.currentTimeMillis()
        if (now - Prefs.gl("lastBackupTry", 0L) < 24 * 3600_000L) return
        Prefs.sl("lastBackupTry", now) // first, so a failure doesn't retry every second
        val app = ctx.applicationContext
        Thread {
            try { toDownloads(app); Diag.log("daily backup saved to Downloads") } catch (e: Exception) { Diag.log("daily backup: ${e.message}") }
        }.start()
    }

    fun lastText(): String {
        val t = Prefs.gl("lastBackup", 0L)
        return if (t == 0L) "never" else Fmt.date(t)
    }

    /** Start the launcher again with the restored settings. */
    fun restart(a: MainActivity) {
        try {
            val i = Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(a, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            a.getSystemService(AlarmManager::class.java)?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 800, pi)
        } catch (_: Exception) {}
        // no onPause / service shutdown: they would save the old trip over the restored one
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

// ------------------------------------------------------------------ updates

/** Looks for a newer release on GitHub and installs it with one tap (Android asks to confirm). */
object Updater {
    private const val API = "https://api.github.com/repos/to0olate2-wq/Headunit/releases/latest"
    private const val APK = "DriveLauncher.apk"

    /** This build's CI run (versionCode = 1000 + run). */
    val current: Int get() = BuildConfig.VERSION_CODE - 1000
    @Volatile var latest = 0
        private set
    @Volatile var latestName = ""
        private set
    @Volatile var notes = ""
        private set
    @Volatile private var apkUrl = ""
    @Volatile var status = ""
        private set
    @Volatile var busy = false
        private set
    private var lastTry = 0L
    private var laterUntil = 0L

    val available: Boolean get() = latest > current && apkUrl.isNotEmpty()

    fun buttonText(): String = when {
        busy -> status
        available -> "Update to $latestName"
        else -> "Check for updates"
    }

    fun summary(): String = when {
        busy -> status
        available -> "Version $latestName is ready"
        status.isNotEmpty() -> status
        else -> "Checked " + Prefs.gl("updateChecked", 0L).let { if (it == 0L) "never" else Fmt.date(it) + " " + Fmt.time(it) }
    }

    /** From the launcher's tick: check every 6 hours; show the banner only while parked. */
    fun tick(a: MainActivity) {
        val now = SystemClock.elapsedRealtime()
        if (Prefs.autoUpdate && !busy && (lastTry == 0L || now - lastTry > 6 * 3600_000L) && online(a)) check()
        val show = available && !busy && now > laterUntil && !CarArt.Mode.driving()
        if (show && Notices.current == null)
            Notices.post(Notices.N("update", Ic.REFRESH, "Drive Launcher $latestName is ready", highlights(), "Update", 0L) { install(it) })
        else if (!show) Notices.clear("update")
    }

    /** "Later" on the banner: ask again in 12 hours (Settings still shows it). */
    fun later() { laterUntil = SystemClock.elapsedRealtime() + 12 * 3600_000L }

    private fun online(ctx: Context): Boolean = try {
        ctx.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    } catch (_: Exception) { true }

    fun check() {
        if (busy) return
        busy = true
        lastTry = SystemClock.elapsedRealtime()
        status = "Checking…"
        Thread {
            try {
                val con = URL(API).openConnection() as HttpURLConnection
                con.connectTimeout = 10_000; con.readTimeout = 15_000
                con.setRequestProperty("Accept", "application/vnd.github+json")
                con.setRequestProperty("User-Agent", "DriveLauncher/${BuildConfig.VERSION_NAME}")
                val code = con.responseCode
                if (code != 200) throw IOException("server answered $code")
                parse(JSONObject(con.inputStream.bufferedReader().use { it.readText() }))
                Prefs.sl("updateChecked", System.currentTimeMillis())
                status = if (available) "Version $latestName is ready" else "Up to date (${BuildConfig.VERSION_NAME})"
                Diag.log("update check: newest build $latest, this build $current")
            } catch (e: Exception) {
                lastTry = SystemClock.elapsedRealtime() - 5 * 3600_000L - 30 * 60_000L // try again in 30 minutes
                status = "Couldn't check: ${e.message ?: "no connection"}"
                Diag.log("update check: ${e.javaClass.simpleName} ${e.message}")
            } finally { busy = false }
        }.start()
    }

    /** Reads GitHub's "latest release" answer. */
    fun parse(js: JSONObject) {
        val run = js.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
        var url = ""
        val assets = js.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val o = assets.getJSONObject(i)
            if (o.optString("name") == APK) url = o.optString("browser_download_url")
        }
        apkUrl = url
        latestName = js.optString("name").substringAfterLast(' ').ifEmpty { "build $run" }
        notes = js.optString("body")
        latest = run
    }

    /** The release notes' first lines, for the banner. */
    fun highlights(): String = notes.lineSequence().map { it.trim().removePrefix("- ").trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    fun install(a: MainActivity) {
        if (busy) return
        if (!available) { check(); return }
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow \"Install unknown apps\" for Drive Launcher, then tap Update again.", Toast.LENGTH_LONG).show()
            try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
            catch (_: Exception) { a.openAppSettings() }
            return
        }
        busy = true
        status = "Downloading…"
        Notices.clear("update")
        val app = a.applicationContext
        val url = apkUrl
        Thread {
            try {
                val f = File(app.cacheDir, "update.apk")
                download(url, f)
                val info = app.packageManager.getPackageArchiveInfo(f.path, 0) ?: throw IOException("the download isn't a working app")
                if (info.packageName != app.packageName) throw IOException("the download is a different app")
                status = "Installing…"
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite(APK, 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val back = Intent(app, UpdateReceiver::class.java)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    s.commit(PendingIntent.getBroadcast(app, id, back, flags).intentSender)
                }
                Diag.log("update: $latestName downloaded, installing")
            } catch (e: Exception) {
                failed(e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private fun download(url: String, f: File) {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 30_000
        con.setRequestProperty("User-Agent", "DriveLauncher/${BuildConfig.VERSION_NAME}")
        if (con.responseCode != 200) throw IOException("download answered ${con.responseCode}")
        val total = con.contentLengthLong
        var got = 0L
        con.inputStream.use { inp ->
            FileOutputStream(f).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (total > 0) status = "Downloading ${got * 100 / total}%"
                }
            }
        }
        if (total > 0 && got != total) throw IOException("download cut short")
    }

    fun failed(why: String) {
        status = "Update failed: $why"
        busy = false
        Diag.log("update: $why")
    }

    fun waiting() { status = "Waiting for you to confirm…" }
}

/** Android's answer about the update install. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) { Updater.failed("no confirm screen"); return }
                Updater.waiting()
                try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { Updater.failed("couldn't open the installer (${e.message})") }
            }
            PackageInstaller.STATUS_SUCCESS -> Diag.log("update installed")
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                Updater.failed(if (st == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else msg)
                Toast.makeText(c, "Update not installed: ${if (st == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else msg}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

// ------------------------------------------------------------------ break reminder

/**
 * After a long stretch of driving (default 2 hours), suggests a break: spoken, a banner on the launcher and a
 * notification. A stop of 15 minutes (or the car switched off that long) starts the count again.
 */
object BreakReminder {
    private var start = 0L
    private var stopped = 0L
    private var lastSaid = 0L
    private const val CH = "reminders"

    /** Minutes driven since the last real break. */
    @Volatile var drivingMin = 0
        private set

    fun tick(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        val kmh = if (Gps.fresh) Gps.speedKmh else Obd.v("speed")
        val moving = kmh != null && kmh > 10f
        if (moving) {
            if (start == 0L) start = now
            stopped = 0L
        } else if (kmh == null || kmh < 3f) {
            if (stopped == 0L) stopped = now
            if (now - stopped > 15 * 60_000L) { start = 0L; lastSaid = 0L }
        }
        drivingMin = if (start == 0L) 0 else ((now - start) / 60_000L).toInt()
        val after = Prefs.breakMin
        if (after <= 0 || !moving || drivingMin < after) return
        if (lastSaid != 0L && now - lastSaid < 30 * 60_000L) return
        lastSaid = now
        remind(ctx, drivingMin)
    }

    /** Screen off: count it as stopped from now. */
    fun pause() { if (stopped == 0L) stopped = SystemClock.elapsedRealtime() }

    fun spoken(min: Int): String {
        val h = min / 60
        val m = (min % 60) / 5 * 5
        val hs = if (h == 1) "1 hour" else "$h hours"
        return when {
            h == 0 -> "$m minutes"
            m == 0 -> hs
            else -> "$hs and $m minutes"
        }
    }

    fun remind(ctx: Context, min: Int) {
        val t = spoken(min)
        Diag.log("break reminder after $min min")
        Speaker.say(ctx, "You have been driving for $t. Time for a short break.")
        Notices.post(Notices.N("break", Ic.COFFEE, "Time for a break", "You've been driving for $t. A short stop keeps you sharp.",
            "Find coffee", SystemClock.elapsedRealtime() + 90_000L) { a ->
            Places.NEARBY.firstOrNull { it.icon == "coffee" }?.let { Places.go(a, it) }
        })
        try {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Break reminder after long drives"
            })
            val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(7, Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Time for a break")
                .setContentText("You've been driving for $t.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build())
        } catch (e: Exception) { Diag.log("break notification: ${e.message}") }
    }
}

// ------------------------------------------------------------------ screen saver

/**
 * Parked screen saver: a big dim clock, date and weather, the car in its showroom look and what's playing.
 * Everything drifts a little every minute so nothing burns into the screen. Tap to close.
 */
class SaverView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum, lnum" }
    private var key = ""
    private var time = ""
    private var ampm = ""
    private var date = ""
    private var music = ""
    private var temp = ""

    fun update() {
        time = Fmt.timeShort(); ampm = Fmt.ampm(); date = Fmt.dateLong()
        temp = if (Weather.tempC.isNaN()) "" else fmt("%.0f°", Units.temp(Weather.tempC))
        music = MediaHub.current?.let { it.title + if (it.artist.isNotEmpty()) "  ·  " + it.artist else "" } ?: ""
        val k = "$time|$ampm|$date|$temp|$music|${System.currentTimeMillis() / 60_000L}|${C.accent}|${Prefs.carColor}"
        if (k != key) { key = k; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = S.u
        val night = C.night
        p.shader = LinearGradient(0f, 0f, 0f, h, if (night) 0xFF020305.toInt() else 0xFF0A1220.toInt(),
            if (night) 0xFF080B12.toInt() else 0xFF15233A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // slow drift (burn-in): a different spot every minute
        val t = (System.currentTimeMillis() / 60_000L).toFloat()
        val dx = sin(t * 1.3f) * 22f * s
        val dy = cos(t * 0.7f) * 14f * s
        val portrait = h > w * 1.05f
        val dim = if (night) 0.78f else 0.92f
        val textC = withAlpha(0xFFF2F5FA.toInt(), dim)
        val subC = withAlpha(0xFFFFFFFF.toInt(), dim * 0.62f)
        val faintC = withAlpha(0xFFFFFFFF.toInt(), dim * 0.42f)

        // car on a soft accent pool
        val carCx: Float; val carBottom: Float; val carW: Float; val carH: Float
        if (portrait) { carCx = w / 2f + dx; carBottom = h * 0.76f + dy; carW = w * 0.86f; carH = h * 0.30f }
        else { carCx = w * 0.69f + dx; carBottom = h * 0.80f + dy; carW = w * 0.52f; carH = h * 0.50f }
        p.shader = RadialGradient(carCx, carBottom - carH * 0.35f, carW * 0.75f, withAlpha(C.accent, if (night) 0.10f else 0.14f), 0, Shader.TileMode.CLAMP)
        c.drawCircle(carCx, carBottom - carH * 0.35f, carW * 0.75f, p)
        p.shader = null
        CarArt.alwaysNight = true // the saver is always dark: the car's night floor
        CarArt.draw(c, carCx, carBottom, carW, carH, 0f, 0f, false, 0f)
        CarArt.alwaysNight = false

        // clock, date, weather
        val left = if (portrait) w / 2f + dx else 72f * s + dx
        val align = if (portrait) Paint.Align.CENTER else Paint.Align.LEFT
        tp.textAlign = align
        tp.typeface = F.display
        tp.color = textC
        tp.textSize = (if (portrait) 150f else 168f) * s
        val timeY = (if (portrait) h * 0.25f else h * 0.42f) + dy
        val tw = tp.measureText(time)
        c.drawText(time, left, timeY, tp)
        if (ampm.isNotEmpty()) {
            tp.textAlign = Paint.Align.LEFT
            tp.typeface = F.medium
            tp.textSize = 30f * s
            tp.color = subC
            val ax = if (portrait) left + tw / 2f + 10f * s else left + tw + 12f * s
            c.drawText(ampm, ax, timeY, tp)
            tp.textAlign = align
        }
        tp.typeface = F.medium
        tp.textSize = 28f * s
        tp.color = subC
        val dateY = timeY + 58f * s
        c.drawText(date, left, dateY, tp)
        if (temp.isNotEmpty()) {
            val dw = tp.measureText(date)
            val ix = if (portrait) left + dw / 2f + 34f * s else left + dw + 34f * s
            Icons.draw(c, Weather.icon(), ix, dateY - 10f * s, 30f * s, subC, 1.8f)
            tp.textAlign = Paint.Align.LEFT
            c.drawText(temp, ix + 22f * s, dateY, tp)
            tp.textAlign = align
        }
        // what's playing
        if (music.isNotEmpty()) {
            tp.typeface = F.regular
            tp.textSize = 20f * s
            tp.color = faintC
            val my = h - 56f * s + dy * 0.5f
            val maxW = if (portrait) w * 0.8f else w * 0.40f
            var m = music
            while (m.length > 4 && tp.measureText(m) > maxW) m = m.dropLast(2)
            if (m != music) m = m.trimEnd() + "…"
            if (portrait) {
                c.drawText(m, left + 16f * s, my, tp)
                Icons.draw(c, Ic.MUSIC, left - tp.measureText(m) / 2f - 4f * s, my - 7f * s, 22f * s, faintC, 1.8f)
            } else {
                Icons.draw(c, Ic.MUSIC, left + 11f * s, my - 7f * s, 22f * s, faintC, 1.8f)
                tp.textAlign = Paint.Align.LEFT
                c.drawText(m, left + 32f * s, my, tp)
            }
        }
        // a quiet hint
        tp.textAlign = Paint.Align.RIGHT
        tp.typeface = F.regular
        tp.textSize = 14f * s
        tp.color = withAlpha(0xFFFFFFFF.toInt(), dim * 0.25f)
        c.drawText("Tap to close", w - 28f * s, h - 22f * s, tp)
    }

}
