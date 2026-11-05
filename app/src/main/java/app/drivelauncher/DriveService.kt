package app.drivelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Keeps GPS, trip recording, the speed warning, the floating speedometer and auto-start
 * working while another app (ZLink, Maps) is on screen. Shows a small "running" notification.
 */
class DriveService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var screenOffAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                SpeedAlert.check(this@DriveService)
                SpeedVolume.update(this@DriveService)
                Trips.tick()
                Overlay.update(this@DriveService)
            } catch (e: Exception) {
                Diag.log("service tick: ${e.javaClass.simpleName} ${e.message}")
            }
            h.postDelayed(this, 1000)
        }
    }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = SystemClock.elapsedRealtime()
                    if (!Gps.fresh || Gps.speedKmh < 3f) {
                        Trips.powerOff()
                        if (!Gps.lat.isNaN()) Parking.save(Gps.lat, Gps.lon, System.currentTimeMillis())
                    }
                    Gps.save()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val off = screenOffAt
                    screenOffAt = 0L
                    if (off > 0 && SystemClock.elapsedRealtime() - off > 60_000) AutoStart.run(c, "wake-up")
                }
                Intent.ACTION_SHUTDOWN -> {
                    Trips.powerOff()
                    if (!Gps.lat.isNaN()) Parking.save(Gps.lat, Gps.lon, System.currentTimeMillis())
                    Gps.save()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        F.load(this)
        Trips.init(this)
        FuelLog.init(this)
        ServiceLog.init(this)
        startInForeground()
        Gps.start(this)
        MediaHub.init(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(power, f)
        h.post(tick)
        Diag.log("background service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        h.removeCallbacks(tick)
        try { unregisterReceiver(power) } catch (_: Exception) {}
        Overlay.hide()
        Trips.powerOff()
        Gps.save()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CH, "Drive Launcher", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps speed, trips and alerts working while other apps are open"
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Drive Launcher is running")
            .setContentText("Speed, trips and alerts work while other apps are open")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
    }

    companion object {
        private const val CH = "drive"

        /** Starts the service (needs location permission; must be called while the launcher is on screen). */
        fun start(ctx: Context) {
            if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            try {
                ctx.startForegroundService(Intent(ctx, DriveService::class.java))
            } catch (e: Exception) {
                Diag.log("service start: ${e.javaClass.simpleName} ${e.message}")
            }
        }
    }
}

/** Beeps when the car goes faster than the chosen speed, or faster than the road's posted limit. */
object SpeedAlert {
    @Volatile var over = false
        private set
    /** Faster than the road's posted limit (the sign turns red). */
    @Volatile var overRoad = false
        private set
    private var lastBeep = 0L
    private var tone: ToneGenerator? = null

    /** Limit in km/h, or null when off. */
    fun limitKmh(): Float? {
        val v = Prefs.speedLimit
        if (v <= 0) return null
        return if (Prefs.imperial) v / 0.621371f else v.toFloat()
    }

    fun check(ctx: Context) {
        val kmh = if (Gps.fresh) Gps.speedKmh else Obd.v("speed")
        if (kmh == null) { over = false; overRoad = false; return }
        val road = RoadLimit.kmh
        overRoad = when {
            road == null -> false
            kmh > road + 2f -> true
            kmh < road - 2f -> false
            else -> overRoad
        }
        val tol = Prefs.roadWarn
        val lim = limitKmh()
        // once over, stay "over" until a few km/h below the limit (no flicker)
        val fixedOver = lim != null && (if (over) kmh >= lim - 3f else kmh > lim + 1f)
        val roadOver = road != null && tol >= 0 && (if (over) kmh >= road + tol - 2f else kmh > road + max(2, tol))
        over = fixedOver || roadOver
        if (over) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBeep > 10_000) { lastBeep = now; beep() }
        }
    }

    fun beep() {
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 90).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
        } catch (e: Exception) {
            Diag.log("speed chime: ${e.message}")
        }
    }
}

/** Floating speedometer bubble shown on top of other apps. */
object Overlay {
    @Volatile var launcherVisible = false
    private var view: BubbleView? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    fun allowed(ctx: Context): Boolean = try { Settings.canDrawOverlays(ctx) } catch (_: Exception) { false }

    fun update(ctx: Context) {
        val want = Prefs.bubble && !launcherVisible && allowed(ctx)
        if (want && view == null) show(ctx)
        if (!want && view != null) hide()
        val v = view ?: return
        val kmh = if (Gps.fresh) Gps.speedKmh else Obd.v("speed")
        v.set(kmh?.let { Units.speed(it).roundToInt().toString() } ?: "--", Units.speedUnit, SpeedAlert.over)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(ctx: Context) {
        val app = ctx.applicationContext
        val w = app.getSystemService(WindowManager::class.java) ?: return
        val dm = app.resources.displayMetrics
        val k = min(dm.widthPixels / 1280f, dm.heightPixels / 720f).coerceIn(0.6f, 3f)
        val size = (96 * k).roundToInt()
        val p = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = Prefs.bubbleX; p.y = Prefs.bubbleY
        val v = BubbleView(app, k)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    p.x = (startX + dx).roundToInt().coerceIn(0, max(0, dm.widthPixels - size))
                    p.y = (startY + dy).roundToInt().coerceIn(0, max(0, dm.heightPixels - size))
                    try { w.updateViewLayout(v, p) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { Prefs.bubbleX = p.x; Prefs.bubbleY = p.y }
                    else try {
                        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }
            true
        }
        try {
            w.addView(v, p)
            view = v; wm = w; lp = p
        } catch (e: Exception) {
            Diag.log("floating speedometer: ${e.message}")
        }
    }

    fun hide() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
    }
}

class BubbleView(ctx: Context, private val k: Float) : View(ctx) {
    private var text = "--"
    private var unit = "KM/H"
    private var warn = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    fun set(t: String, u: String, w: Boolean) {
        if (t != text || u != unit || w != warn) { text = t; unit = u; warn = w; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f
        p.style = Paint.Style.FILL
        p.color = if (warn) 0xF0D12F20.toInt() else 0xE60B0D10.toInt()
        c.drawCircle(w / 2, h / 2, r - 1, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f * k
        p.color = if (warn) 0xFFFFFFFF.toInt() else C.accent
        rf.set(p.strokeWidth, p.strokeWidth, w - p.strokeWidth, h - p.strokeWidth)
        c.drawOval(rf, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = 0xFFF2F4F7.toInt()
        p.typeface = F.numeric
        p.textSize = r * 0.85f
        c.drawText(text, w / 2, h / 2 + p.textSize * 0.3f, p)
        p.typeface = F.semibold
        p.textSize = r * 0.2f
        p.color = 0xFFC9CED6.toInt()
        c.drawText(unit, w / 2, h / 2 + r * 0.58f, p)
    }
}

/** Opens ZLink (or another app) and resumes music when the car is switched on. */
object AutoStart {
    private val h = Handler(Looper.getMainLooper())
    private var lastRun = 0L

    fun target(ctx: Context): String? = when (val a = Prefs.autoApp) {
        "off", "" -> null
        "zlink" -> Apps.findZlink(ctx)
        else -> a.takeIf { Apps.canLaunch(ctx, it) }
    }

    /** Called when the launcher starts; runs once per power-up. */
    fun onLauncherStart(ctx: Context) {
        val boot = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }
        if (boot != -1 && boot == Prefs.lastBoot) return
        if (SystemClock.elapsedRealtime() > 10 * 60_000L) { Prefs.lastBoot = boot; return }
        Prefs.lastBoot = boot
        run(ctx, "power-on")
    }

    fun run(ctx: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < 60_000) return
        lastRun = now
        val app = ctx.applicationContext
        val pkg = target(app)
        Diag.log("auto-start ($reason): ${pkg ?: "no app"}, resume music=${Prefs.autoPlay}")
        var delay = 5000L
        if (pkg != null) {
            h.postDelayed({ Apps.launch(app, pkg) }, delay)
            delay += 10_000L
        }
        if (Prefs.autoPlay) h.postDelayed({ MediaHub.key(app, KeyEvent.KEYCODE_MEDIA_PLAY) }, delay)
        val back = Prefs.autoReturn
        if (back > 0 && pkg != null) h.postDelayed({
            try { app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }, 5000L + back * 1000L)
    }
}
