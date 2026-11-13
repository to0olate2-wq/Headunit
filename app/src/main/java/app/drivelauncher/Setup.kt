package app.drivelauncher

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the crash safety net before anything else runs (launcher, background service, notification listener). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
    }
}

// ------------------------------------------------------------------ crash safety net

/**
 * If the launcher crashes: write what went wrong to `files/crash.txt` (kept in the saved log and the diagnostics box),
 * then start again by itself — the home screen if it was on screen, otherwise only the background service so the app
 * you were using (ZLink, Maps) isn't interrupted. Three crashes within 2 minutes = stop restarting (no endless loop).
 */
object Crash {
    private const val FILE = "crash.txt"
    private const val KEEP = 12_000 // characters of old reports to keep
    @Volatile private var installed = false

    /** Crash reports (newest first), empty when there were none. */
    @Volatile var text = ""
        private set

    fun install(app: Context) {
        if (installed || Build.FINGERPRINT == "robolectric") return
        installed = true
        val ctx = app.applicationContext
        text = try { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { handle(ctx, thread, e) } catch (_: Throwable) { previous?.uncaughtException(thread, e) }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }

    /** Writes the report and plans the restart. */
    private fun handle(ctx: Context, thread: Thread, e: Throwable) {
        val now = System.currentTimeMillis()
        val report = report(thread.name, e, now)
        Diag.log("CRASH in ${thread.name}: ${e.javaClass.simpleName}: ${e.message}")
        text = (report + "\n" + text).take(KEEP)
        try { File(ctx.filesDir, FILE).writeText(text) } catch (_: Exception) {}
        // loop guard: times of recent crashes
        val sp = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE) // same file as Prefs, which may not be set up yet
        val recent = (sp.getString("crashTimes", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 120_000L } + now
        sp.edit().putString("crashTimes", recent.joinToString(",")).putInt("crashUnseen", sp.getInt("crashUnseen", 0) + 1)
            .commit() // must be on disk before the process ends
        if (!shouldRestart(recent)) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + 1500
        val pi = if (Overlay.launcherVisible) {
            val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, 91, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        } else {
            val i = Intent(ctx, DriveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(ctx, 92, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            else PendingIntent.getService(ctx, 92, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        }
        am.set(AlarmManager.ELAPSED_REALTIME, at, pi)
    }

    /** No more than 2 automatic restarts within 2 minutes. */
    fun shouldRestart(recentTimes: List<Long>) = recentTimes.size <= 2

    fun report(threadName: String, e: Throwable, time: Long): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().lines().take(40).joinToString("\n")
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val log = Diag.recentLines(25).joinToString("\n") { "  $it" }
        return "=== Crash ${tf.format(Date(time))}, version ${BuildConfig.VERSION_NAME}, thread $threadName\n$stack\nLast log lines:\n$log\n"
    }

    /** After a restart: tell the owner once, with a button to save a report for me. */
    fun announce(a: MainActivity) {
        val n = Prefs.gi("crashUnseen", 0)
        if (n <= 0) return
        Prefs.si("crashUnseen", 0)
        Notices.post(Notices.N("crash", Ic.REFRESH, "Drive Launcher restarted after a problem",
            "Your settings are safe. Save a report to Downloads so the problem can be fixed.", "Save report", 0L) { act ->
            Thread {
                val msg = try { "Report saved: " + Diag.save(act, "") } catch (e: Exception) { "Couldn't save: ${e.message}" }
                act.runOnUiThread { android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show() }
            }.start()
        })
    }

    fun clear(ctx: Context) {
        text = ""
        try { File(ctx.filesDir, FILE).delete() } catch (_: Exception) {}
    }
}

// ------------------------------------------------------------------ setup guide

/**
 * First-time setup: every permission and setting the launcher needs, one row each, with a button that opens the right
 * Android screen and a tick when it's done. Opens by itself on the first start (or after a reset) when a needed step
 * is missing; Settings → Setup guide opens it any time.
 */
object Setup {
    class Step(val ic: Ic, val title: String, val why: String, val needed: Boolean, val done: (MainActivity) -> Boolean, val open: (MainActivity) -> Unit)

    private fun granted(a: Context, p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun isHome(a: Context): Boolean = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        a.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == a.packageName
    } catch (_: Exception) { false }

    fun keepsRunning(a: Context): Boolean = try {
        a.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(a.packageName) ?: true
    } catch (_: Exception) { true }

    val steps: List<Step> = listOfNotNull(
        Step(Ic.LOCATE, "Location", "Speed, trips, speed limits, weather and day/night look.", true,
            { granted(it, Manifest.permission.ACCESS_FINE_LOCATION) }, { it.requestPerms(true) }),
        Step(Ic.MUSIC, "Notification access", "Song info from ZLink, Anghami and Spotify, next turn from Maps, calls and messages.", true,
            { MediaHub.hasAccess(it) }, { it.openNotificationAccess() }),
        Step(Ic.HOME, "Home screen", "Choose Drive Launcher as the Home app so it opens when the car starts.", true,
            { isHome(it) }, { it.openHomeSettings() }),
        Step(Ic.REFRESH, "Keep running", "Stops Android from closing the trip recorder and speed alerts in the background.", false,
            { keepsRunning(it) }, { it.askKeepRunning() }),
        Step(Ic.OPEN, "Show over other apps", "Floating speedometer, next turn and mini player over ZLink and Maps.", false,
            { Overlay.allowed(it) }, { it.openOverlaySettings() }),
        if (Build.VERSION.SDK_INT >= 33) Step(Ic.SPEAKER, "Notifications", "Break reminder and the background service notice.", false,
            { granted(it, Manifest.permission.POST_NOTIFICATIONS) }, { it.requestPerms(true) }) else null,
        if (Build.VERSION.SDK_INT >= 31) Step(Ic.BT, "Bluetooth", "Only for an OBD2 engine adapter.", false,
            { granted(it, Manifest.permission.BLUETOOTH_CONNECT) }, { it.requestPerms(true) }) else null,
        Step(Ic.CHECK, "Install updates", "Lets the app install new versions with one tap.", false,
            { it.packageManager.canRequestPackageInstalls() }, { it.openInstallSettings() }),
    )

    fun missingNeeded(a: MainActivity) = steps.any { it.needed && !it.done(a) }

    /** On start: open the guide the first time, unless everything needed is already done. */
    fun shouldShow(a: MainActivity): Boolean {
        if (Prefs.gb("setupDone", false)) return false
        if (!missingNeeded(a)) { Prefs.sb("setupDone", true); return false }
        return true
    }
}

/** The setup guide screen (over the launcher). */
class SetupView(private val a: MainActivity, private val onClose: () -> Unit) : FrameLayout(a) {
    private val refreshers = ArrayList<() -> Unit>()
    private val finish: TextView

    init {
        setBackgroundColor(withAlpha(if (C.night) 0xFF05070B.toInt() else 0xFF0A1220.toInt(), 0.97f))
        isClickable = true
        val portrait = a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val box = a.vbox(10f)
        box.pad(if (portrait) 24f else 40f, 28f)
        box.addView(a.label(30f, C.TEXT, F.semibold).apply { text = "Welcome to Drive Launcher" }, lp(MATCH, WRAP))
        box.addView(a.label(16f, C.TEXT2, F.regular, lines = 2).apply {
            text = "Allow these once and everything works. Tap a button, allow it in the Android screen, then press Back."
        }, lp(MATCH, WRAP).apply { bottomMargin = S.i(6) })

        val list = a.vbox(8f)
        for (st in Setup.steps) list.addView(stepRow(st), lp(MATCH, WRAP))
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = true; addView(list) }
        box.addView(sv, lp(MATCH, 0, 1f))

        val foot = a.hbox(12f)
        val restore = a.label(15f, C.TEXT2, F.semibold).apply {
            text = "Restore a backup…"; gravity = Gravity.CENTER; background = roundBg(C.CARD2, 14f, C.BORDER2); pad(18f, 0f)
            isClickable = true; setOnClickListener { a.pickRestore() }
        }
        foot.addView(restore, lp(WRAP, S.i(52)))
        foot.addView(View(a), lp(0, 1, 1f))
        finish = a.label(16f, C.ON_ACCENT, F.semibold).apply {
            gravity = Gravity.CENTER; background = roundBg(C.accent, 14f); pad(26f, 0f); isClickable = true
            setOnClickListener { Prefs.sb("setupDone", true); onClose() }
        }
        foot.addView(finish, lp(WRAP, S.i(52)))
        box.addView(foot, lp(MATCH, WRAP).apply { topMargin = S.i(6) })
        addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        refresh()
    }

    private fun stepRow(st: Setup.Step): View {
        val r = a.hbox(14f)
        r.background = cardBg()
        r.pad(16f, 12f)
        val ic = IconBtn(a, st.ic, 22f).apply { circle = true; isClickable = false }
        r.addView(ic, lp(S.i(46), S.i(46)))
        val col = a.vbox(3f)
        val title = a.label(18f, C.TEXT, F.semibold)
        col.addView(title, lp(MATCH, WRAP))
        col.addView(a.label(14f, C.TEXT2, F.regular, lines = 2).apply { text = st.why }, lp(MATCH, WRAP))
        r.addView(col, lp(0, WRAP, 1f))
        val b = a.label(15f, C.TEXT, F.semibold).apply { gravity = Gravity.CENTER; pad(18f, 0f); isClickable = true }
        b.setOnClickListener { if (!st.done(a)) st.open(a) }
        r.addView(b, lp(S.i(150), S.i(46)))
        refreshers.add {
            val done = st.done(a)
            title.put(st.title + if (st.needed) "" else "  ·  optional")
            ic.icon = if (done) Ic.CHECK else st.ic
            ic.bgColor = if (done) C.accent else C.CARD2
            ic.fg = if (done) C.ON_ACCENT else C.TEXT2
            ic.invalidate()
            b.put(if (done) "Done" else "Allow")
            b.setTextColor(if (done) C.MUTED else C.ON_ACCENT)
            b.background = if (done) null else roundBg(C.accent, 14f)
            b.isEnabled = !done
        }
        return r
    }

    /** Ticks follow the real state (called on resume and every second). */
    fun refresh() {
        refreshers.forEach { it() }
        finish.put(if (Setup.missingNeeded(a)) "Skip for now" else "Finish")
    }
}
