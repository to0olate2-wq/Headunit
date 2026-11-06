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
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.FrameLayout
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
                BreakReminder.tick(this@DriveService)
                Backup.auto(this@DriveService)
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
                    BreakReminder.pause()
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
    private var view: FloatPanel? = null
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    fun allowed(ctx: Context): Boolean = try { Settings.canDrawOverlays(ctx) } catch (_: Exception) { false }

    /** Floating pill over other apps: speedometer, next turn, mini music player (each optional). */
    fun update(ctx: Context) {
        val speed = Prefs.bubble
        val turn = Prefs.floatTurn && NavHub.active
        val music = Prefs.floatMusic && MediaHub.current != null
        val want = (speed || turn || music) && !launcherVisible && allowed(ctx)
        if (want && view == null) show(ctx)
        if (!want && view != null) hide()
        val v = view ?: return
        v.update(speed, turn, music)
    }

    private fun show(ctx: Context) {
        val app = ctx.applicationContext
        val w = app.getSystemService(WindowManager::class.java) ?: return
        val dm = app.resources.displayMetrics
        val k = min(dm.widthPixels / 1280f, dm.heightPixels / 720f).coerceIn(0.6f, 3f)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = Prefs.bubbleX; p.y = Prefs.bubbleY
        val v = FloatPanel(app, k)
        var startX = 0; var startY = 0
        v.onDrag = { dx, dy, done ->
            if (dx == 0f && dy == 0f && !done) { startX = p.x; startY = p.y }
            else {
                p.x = (startX + dx).roundToInt().coerceIn(0, max(0, dm.widthPixels - v.width))
                p.y = (startY + dy).roundToInt().coerceIn(0, max(0, dm.heightPixels - v.height))
                try { w.updateViewLayout(v, p) } catch (_: Exception) {}
                if (done) { Prefs.bubbleX = p.x; Prefs.bubbleY = p.y }
            }
        }
        try {
            w.addView(v, p)
            view = v; wm = w; lp = p
        } catch (e: Exception) {
            Diag.log("floating panel: ${e.message}")
        }
    }

    fun hide() {
        val v = view ?: return
        try { wm?.removeView(v) } catch (_: Exception) {}
        view = null
    }
}

/** The floating pill: drag it anywhere; tap the speed to open the launcher, the turn to open Maps, the song to open the music app. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FloatPanel(private val ctx: Context, private val k: Float) : LinearLayout(ctx) {
    var onDrag: ((Float, Float, Boolean) -> Unit)? = null
    private val speed = BubbleView(ctx, k)
    private val turn = LinearLayout(ctx)
    private val turnImg = android.widget.ImageView(ctx)
    private val turnIcon = IconBtn(ctx, Ic.TURN_RIGHT, 26f)
    private val turnDist = TextView(ctx)
    private val turnStreet = TextView(ctx)
    private val music = LinearLayout(ctx)
    private val art = ArtView(ctx)
    private val title = TextView(ctx)
    private val artist = TextView(ctx)
    private val play = IconBtn(ctx, Ic.PLAY, 22f)
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var navShown = -1
    private var trackKey = ""

    private fun px(v: Float) = (v * k).roundToInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xEB0B0D10.toInt()); cornerRadius = 60f * k; setStroke(max(1, px(1.5f)), withAlpha(C.accent, 0.8f))
        }
        setPadding(px(6f), px(6f), px(10f), px(6f))
        addView(speed, LayoutParams(px(84f), px(84f)))
        // next turn
        turn.orientation = HORIZONTAL; turn.gravity = Gravity.CENTER_VERTICAL
        turn.setPadding(px(10f), 0, px(8f), 0)
        val box = FrameLayout(ctx)
        box.background = android.graphics.drawable.GradientDrawable().apply { setColor(C.accent); cornerRadius = 14f * k }
        turnIcon.isClickable = false; turnIcon.fg = C.ON_ACCENT; turnIcon.sw = 2.4f
        box.addView(turnIcon, FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(turnImg, FrameLayout.LayoutParams(px(38f), px(38f), Gravity.CENTER))
        turn.addView(box, LayoutParams(px(56f), px(56f)))
        val tc = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(px(10f), 0, 0, 0) }
        turnDist.setTextColor(0xFFF2F4F7.toInt()); turnDist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 24f * k)
        turnDist.typeface = F.numeric; turnDist.setSingleLine(true)
        turnStreet.setTextColor(0xFFC9CED6.toInt()); turnStreet.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * k)
        turnStreet.typeface = F.medium; turnStreet.setSingleLine(true); turnStreet.ellipsize = android.text.TextUtils.TruncateAt.END
        turnStreet.maxWidth = px(220f)
        tc.addView(turnDist); tc.addView(turnStreet)
        turn.addView(tc)
        turn.setOnClickListener { NavHub.pkg.takeIf { it.isNotEmpty() }?.let { Apps.launch(ctx, it) } }
        addView(turn, LayoutParams(WRAP, WRAP))
        // music
        music.orientation = HORIZONTAL; music.gravity = Gravity.CENTER_VERTICAL
        music.setPadding(px(10f), 0, 0, 0)
        music.addView(art, LayoutParams(px(56f), px(56f)))
        val mc = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(px(10f), 0, px(6f), 0) }
        title.setTextColor(0xFFF2F4F7.toInt()); title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * k)
        title.typeface = F.semibold; title.setSingleLine(true); title.ellipsize = android.text.TextUtils.TruncateAt.END; title.maxWidth = px(200f)
        artist.setTextColor(0xFF9AA3AE.toInt()); artist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * k)
        artist.typeface = F.regular; artist.setSingleLine(true); artist.ellipsize = android.text.TextUtils.TruncateAt.END; artist.maxWidth = px(200f)
        mc.addView(title); mc.addView(artist)
        mc.setOnClickListener { MediaHub.openSourceApp(ctx) }
        music.addView(mc)
        fun btn(ic: Ic, desc: String, click: () -> Unit) = IconBtn(ctx, ic, 22f).apply {
            contentDescription = desc; fg = 0xFFF2F4F7.toInt(); setOnClickListener { click() }
        }
        music.addView(btn(Ic.PREV, "Previous track") { MediaHub.prev(ctx) }, LayoutParams(px(48f), px(48f)))
        play.circle = true; play.bgColor = C.accent; play.fg = C.ON_ACCENT; play.contentDescription = "Play or pause"
        play.setOnClickListener { MediaHub.playPause(ctx) }
        music.addView(play, LayoutParams(px(52f), px(52f)))
        music.addView(btn(Ic.NEXT, "Next track") { MediaHub.next(ctx) }, LayoutParams(px(48f), px(48f)))
        addView(music, LayoutParams(WRAP, WRAP))
        speed.setOnClickListener {
            try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        speed.isClickable = true
    }

    fun update(showSpeed: Boolean, showTurn: Boolean, showMusic: Boolean) {
        speed.showIf(showSpeed)
        turn.showIf(showTurn)
        music.showIf(showMusic)
        if (showSpeed) {
            val kmh = if (Gps.fresh) Gps.speedKmh else Obd.v("speed")
            speed.set(kmh?.let { Units.speed(it).roundToInt().toString() } ?: "--", Units.speedUnit, SpeedAlert.over)
        }
        if (showTurn && NavHub.version != navShown) {
            navShown = NavHub.version
            turnDist.text = NavHub.title.ifEmpty { NavHub.text }
            turnStreet.text = if (NavHub.title.isEmpty()) "" else NavHub.text
            turnImg.setImageBitmap(NavHub.icon)
            turnImg.showIf(NavHub.icon != null); turnIcon.showIf(NavHub.icon == null)
        }
        if (showMusic) {
            val t = MediaHub.current ?: return
            val key = t.title + "|" + t.artist + "|" + (t.art?.hashCode() ?: 0)
            if (key != trackKey) { trackKey = key; title.text = t.title.ifEmpty { "Music" }; artist.text = t.artist; art.set(t.art) }
            play.set(icon = if (t.playing) Ic.PAUSE else Ic.PLAY)
        }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; onDrag?.invoke(0f, 0f, false) }
            MotionEvent.ACTION_MOVE -> if (abs(e.rawX - downX) > 12 * k || abs(e.rawY - downY) > 12 * k) { dragging = true; return true }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; onDrag?.invoke(0f, 0f, false); return true }
            MotionEvent.ACTION_MOVE -> { onDrag?.invoke(e.rawX - downX, e.rawY - downY, false); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDrag?.invoke(e.rawX - downX, e.rawY - downY, true)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(e)
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
