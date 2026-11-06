package app.drivelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Field(val label: String, val value: String, val numeric: Boolean)

open class MainActivity : Activity() {
    enum class Page { HOME, TELEMETRY, CAR, SOURCES, APPS }

    /** True for the compact split-screen dashboard ([DashActivity]). */
    protected open val dashMode = false

    private lateinit var root: FrameLayout
    var page = Page.HOME
        private set
    var homeIdx = 0
        private set
    val homeCount: Int get() = homes.size
    private var pages: MutableList<PageCfg> = ArrayList()
    private var homes: List<HomePage> = emptyList()
    private var builtW = 0
    private var builtH = 0
    private var tele: TelemetryPage? = null
    private var car: CarPage? = null
    private var sources: SourcesPage? = null
    private var apps: AppsPage? = null
    private var dock: Dock? = null
    private var saver: View? = null
    private var saverTime: TextView? = null
    private var saverDate: TextView? = null
    private var saverInfo: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var tickCount = 0
    private var lastTouch = SystemClock.elapsedRealtime()
    private var pendingWidget: Triple<Int, Int, Int>? = null
    private var splitTried = false

    val widgetManager: AppWidgetManager by lazy { AppWidgetManager.getInstance(this) }
    val widgetHost: AppWidgetHost by lazy { AppWidgetHost(applicationContext, 0x5150) }

    private val tick = object : Runnable {
        override fun run() {
            update()
            tickCount++
            if (tickCount % 12 == 0 && !MediaHub.accessGranted) MediaHub.tryAttach()
            if (tickCount % 40 == 0) checkTheme(false)
            checkSaver()
            if (resumed) handler.postDelayed(this, 250)
        }
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || dashMode) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > S.p(140f) && abs(dx) > abs(dy) * 1.5f && abs(velocityX) > 700f) {
                    swipe(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Skin.set(Prefs.skin)
        F.load(this)
        CarArt.init(this)
        C.accent = Prefs.accent
        C.apply(ThemeMgr.wantNight(), Prefs.background != "plain")
        Trips.init(this); FuelLog.init(this); ServiceLog.init(this)
        Diag.log("launcher started${if (dashMode) " (split dashboard)" else ""}, ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
        MediaHub.init(this)
        Obd.start(this)
        TileMapView.prune(this)
        root = object : FrameLayout(this) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w > 0 && h > 0) post { build(w, h, false) }
            }
        }
        root.setBackgroundColor(C.BG)
        setContentView(root)
        applyFullscreen()
        if (!dashMode) {
            requestPerms(false)
            AutoStart.onLauncherStart(this)
        }
        Gps.start(this)
        DriveService.start(this)
    }

    // ---------------------------------------------------------------- layout

    private fun build(w: Int, h: Int, force: Boolean) {
        if (!force && w == builtW && h == builtH) return
        builtW = w
        builtH = h
        val portrait = h > w * 1.05f
        S.u = (if (portrait) min(w / 720f, h / 1280f) else min(w / 1280f, h / 720f)).coerceIn(0.45f, 3f)
        Fmt.h24 = android.text.format.DateFormat.is24HourFormat(this)
        root.removeAllViews()
        root.setBackgroundColor(C.BG)
        val bgKind = Prefs.background
        val photo = if (bgKind == "photo") try { BitmapFactory.decodeFile(File(filesDir, "bg.jpg").path) } catch (_: Exception) { null } else null
        val video = Prefs.videoUri.takeIf { bgKind == "video" && it.isNotEmpty() }?.let { try { Uri.parse(it) } catch (_: Exception) { null } }
        videoBg = video?.let { VideoBackground(this, it) }
        root.addView(videoBg ?: BackgroundView(this, if (bgKind == "video") "plain" else bgKind, photo), flp(MATCH, MATCH))
        bannerBox = FrameLayout(this)
        Banners.attach(this)
        val col = vbox(12f)
        col.pad(if (dashMode) 8f else 16f)
        val host = FrameLayout(this)

        pages = if (dashMode) mutableListOf(PageCfg("dash", HomePages.defaults("dash").toMutableList())) else HomePages.load()
        homeIdx = homeIdx.coerceIn(0, pages.size - 1)
        homes = pages.mapIndexed { i, cfg -> HomePage(this, i, portrait, cfg) }
        homes.forEach { host.addView(it.view, flp(MATCH, MATCH)) }
        if (!dashMode) {
            val t = TelemetryPage(this, portrait)
            val c = CarPage(this, portrait)
            val s = SourcesPage(this, portrait, w, h)
            val ap = AppsPage(this, w)
            host.addView(t.view, flp(MATCH, MATCH))
            host.addView(c.view, flp(MATCH, MATCH))
            host.addView(s.view, flp(MATCH, MATCH))
            host.addView(ap.view, flp(MATCH, MATCH))
            tele = t; car = c; sources = s; apps = ap
        }
        col.addView(host, lp(MATCH, 0, 1f))
        if (!dashMode) {
            val dk = Dock(this, portrait)
            col.addView(dk.view, lp(MATCH, S.i(Skin.dockHeight)))
            dock = dk
        }
        root.addView(col, flp(MATCH, MATCH))
        if (!dashMode) bannerBox?.let { root.addView(it, flp(MATCH, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) }
        buildSaver()
        show(page)
        Diag.log("layout ${w}x$h scale ${fmt("%.2f", S.u)} ${if (portrait) "portrait" else "landscape"}, ${pages.size} home pages")
    }

    /** Rebuild every screen (after a settings change). */
    fun rebuild() {
        Skin.set(Prefs.skin)
        F.apply()
        C.accent = Prefs.accent
        C.apply(ThemeMgr.wantNight(), Prefs.background != "plain")
        if (builtW > 0) build(builtW, builtH, true)
    }

    private fun checkTheme(force: Boolean) {
        val night = ThemeMgr.wantNight()
        if (force || night != C.night) {
            rebuild()
            ThemeMgr.applyBrightness(this, night)
        }
    }

    fun applyTheme() = checkTheme(true)

    /** The view on screen for a page (to animate the change). */
    private fun pageView(p: Page, idx: Int): View? = when (p) {
        Page.HOME -> homes.getOrNull(idx)?.view
        Page.TELEMETRY -> tele?.view
        Page.CAR -> car?.view
        Page.SOURCES -> sources?.view
        Page.APPS -> apps?.view
    }

    private var videoBg: VideoBackground? = null
    /** Top banners (calls, messages) sit here above every page. */
    var bannerBox: FrameLayout? = null
        private set
    private var shownView: View? = null
    private var slideDir = 1

    fun show(p: Page) {
        page = p
        homes.forEachIndexed { i, hp -> hp.view.showIf(p == Page.HOME && i == homeIdx) }
        tele?.view?.showIf(p == Page.TELEMETRY)
        car?.view?.showIf(p == Page.CAR)
        sources?.view?.showIf(p == Page.SOURCES)
        apps?.view?.showIf(p == Page.APPS)
        if (p == Page.APPS) apps?.refresh()
        val v = pageView(p, homeIdx)
        if (v != null && shownView != null && v !== shownView && v.parent === shownView?.parent && Motion.on) {
            // slide the new page in a little and fade it up
            v.animate().cancel()
            v.alpha = 0f
            v.translationX = S.p(56f) * slideDir
            v.animate().alpha(1f).translationX(0f).setDuration(280L).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        shownView = v
        slideDir = 1
        update()
    }

    fun showHome(i: Int) {
        val n = i.coerceIn(0, max(0, homes.size - 1))
        if (page == Page.HOME) slideDir = if (n < homeIdx) -1 else 1
        homeIdx = n
        show(Page.HOME)
    }

    fun nextHome() = showHome(if (homes.isEmpty()) 0 else (homeIdx + 1) % homes.size)

    fun showCarTab(i: Int) { show(Page.CAR); car?.showTab(i) }

    private fun swipe(dir: Int) {
        when (page) {
            Page.HOME -> when {
                dir > 0 && homeIdx < homes.size - 1 -> showHome(homeIdx + 1)
                dir > 0 -> show(Page.TELEMETRY)
                dir < 0 && homeIdx > 0 -> showHome(homeIdx - 1)
            }
            Page.TELEMETRY -> if (dir < 0) { slideDir = -1; showHome(homes.size - 1) }
            else -> {}
        }
    }

    private fun update() {
        Banners.update(this)
        try {
            when (page) {
                Page.HOME -> homes.getOrNull(homeIdx)?.update()
                Page.TELEMETRY -> tele?.update()
                Page.CAR -> car?.update()
                Page.SOURCES -> sources?.update()
                Page.APPS -> {}
            }
            dock?.update(page, if (page == Page.HOME && homes.getOrNull(homeIdx)?.hasClock == true) "" else Fmt.time())
        } catch (e: Exception) {
            Diag.log("ui update: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Refresh shortly after a button press so the result shows quickly. */
    fun nudge() {
        handler.postDelayed({ MediaHub.pick(); update() }, 350)
    }

    // ---------------------------------------------------------------- screen saver

    private fun buildSaver() {
        val v = vbox(6f)
        v.gravity = Gravity.CENTER
        v.setBackgroundColor(if (C.night) 0xFF000000.toInt() else C.BG)
        val t = label(180f, C.TEXT, F.display).tnum()
        val d = label(28f, C.TEXT2, F.medium)
        val i = label(20f, C.MUTED, F.regular, lines = 2).apply { gravity = Gravity.CENTER }
        v.addView(t, lp(WRAP, WRAP)); v.addView(d, lp(WRAP, WRAP)); v.addView(i, lp(WRAP, WRAP).apply { topMargin = S.i(16) })
        v.visibility = View.GONE
        v.isClickable = true
        v.setOnClickListener { v.visibility = View.GONE; lastTouch = SystemClock.elapsedRealtime() }
        root.addView(v, flp(MATCH, MATCH))
        saver = v; saverTime = t; saverDate = d; saverInfo = i
    }

    private fun checkSaver() {
        val s = saver ?: return
        val min = Prefs.saverMin
        val moving = (Gps.fresh && Gps.speedKmh > 5f) || (Obd.v("speed") ?: 0f) > 5f
        val want = !dashMode && min > 0 && !moving && SystemClock.elapsedRealtime() - lastTouch > min * 60_000L
        if (s.visibility == View.VISIBLE && moving) s.visibility = View.GONE
        if (want && s.visibility != View.VISIBLE) s.visibility = View.VISIBLE
        if (s.visibility == View.VISIBLE) {
            saverTime?.put(Fmt.timeShort())
            saverDate?.put(Fmt.dateLong() + (if (Weather.tempC.isNaN()) "" else fmt("  ·  %.0f°", Units.temp(Weather.tempC))))
            saverInfo?.put(MediaHub.current?.let { "${it.title}${if (it.artist.isNotEmpty()) " — " + it.artist else ""}" } ?: "")
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { widgetHost.stopListening() } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Overlay.launcherVisible = true
        Overlay.update(this)
        videoBg?.resume()
        MediaHub.tryAttach()
        Gps.start(this)
        DriveService.start(this)
        applyFullscreen()
        ThemeMgr.applyBrightness(this, C.night)
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (dashMode && !splitTried) {
            splitTried = true
            handler.postDelayed({ launchSplitApp() }, 600)
        }
    }

    override fun onPause() {
        super.onPause()
        videoBg?.pause()
        resumed = false
        Overlay.launcherVisible = false
        handler.removeCallbacks(tick)
        Gps.save()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!dashMode) { saver?.visibility = View.GONE; showHome(0) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (dashMode) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        if (page != Page.HOME) showHome(homeIdx) else if (homeIdx != 0) showHome(0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.requestLayout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        try { gestures.onTouchEvent(ev) } catch (_: Exception) {}
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    fun applyFullscreen() {
        window.decorView.systemUiVisibility = if (Prefs.fullscreen && !dashMode)
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else 0
    }

    // ---------------------------------------------------------------- editing the home screen

    /** Tap and hold anywhere on a card to change it. */
    fun attachEdit(v: View, pageIdx: Int, slot: Int) {
        if (dashMode) return
        val l = View.OnLongClickListener { editCard(pageIdx, slot); true }
        fun walk(x: View) {
            if (x is android.appwidget.AppWidgetHostView) return
            if (x.tag == "edit") { x.setOnClickListener { editCard(pageIdx, slot) }; return }
            x.setOnLongClickListener(l)
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
    }

    fun editCard(pageIdx: Int, slot: Int) {
        val cfg = pages.getOrNull(pageIdx) ?: return
        val current = cfg.cards[slot]
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for ((type, name) in HomePages.CARD_TYPES) {
            labels.add((if (type == current) "✓  " else "     ") + name)
            actions.add { setCard(pageIdx, slot, type) }
        }
        labels.add("     Android widget…"); actions.add { pickWidget(pageIdx, slot) }
        labels.add("—  Page layout…"); actions.add { chooseLayout(pageIdx) }
        labels.add("—  Add a new page"); actions.add {
            pages.add(PageCfg("grid", HomePages.defaults("grid").toMutableList()))
            HomePages.save(pages); homeIdx = pages.size - 1; rebuild()
        }
        if (pages.size > 1) { labels.add("—  Remove this page"); actions.add {
            confirm("Remove this page?", "Its cards are removed; you can add pages again any time.") {
                pages.removeAt(pageIdx); HomePages.save(pages); homeIdx = 0; cleanupWidgets(); rebuild()
            }
        } }
        labels.add("—  Reset home screen"); actions.add {
            confirm("Reset the home screen?", "Back to the two standard pages.") { Prefs.pages = ""; homeIdx = 0; cleanupWidgets(); rebuild() }
        }
        dialog().setTitle("Page ${pageIdx + 1}, card ${slot + 1}: ${HomePages.label(current)}")
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setCard(pageIdx: Int, slot: Int, type: String) {
        pages[pageIdx].cards[slot] = type
        HomePages.save(pages)
        cleanupWidgets()
        rebuild()
    }

    private fun chooseLayout(pageIdx: Int) {
        val ids = HomePages.LAYOUTS.keys.toList()
        dialog().setTitle("Page layout").setItems(HomePages.LAYOUTS.values.toTypedArray()) { _, i ->
            val cfg = pages[pageIdx]
            val n = HomePages.slots(ids[i])
            val d = HomePages.defaults(ids[i])
            val cards = cfg.cards.take(n).toMutableList()
            while (cards.size < n) cards.add(d[cards.size])
            pages[pageIdx] = PageCfg(ids[i], cards)
            HomePages.save(pages); cleanupWidgets(); rebuild()
        }.setNegativeButton("Cancel", null).show()
    }

    // ---- Android widgets

    private fun pickWidget(pageIdx: Int, slot: Int) {
        val providers = try { widgetManager.installedProviders } catch (_: Exception) { emptyList<AppWidgetProviderInfo>() }
            .sortedBy { it.loadLabel(packageManager).lowercase() }
        if (providers.isEmpty()) { Toast.makeText(this, "No Android widgets found on this unit", Toast.LENGTH_LONG).show(); return }
        val names = providers.map { "${it.loadLabel(packageManager)}  (${Apps.label(this, it.provider.packageName)})" }
        dialog().setTitle("Choose a widget").setItems(names.toTypedArray()) { _, i ->
            val info = providers[i]
            val id = widgetHost.allocateAppWidgetId()
            pendingWidget = Triple(pageIdx, slot, id)
            val ok = try { widgetManager.bindAppWidgetIdIfAllowed(id, info.provider) } catch (_: Exception) { false }
            if (ok) configureWidget(id) else try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider), REQ_BIND)
            } catch (e: Exception) {
                widgetHost.deleteAppWidgetId(id)
                Toast.makeText(this, "This unit doesn't allow adding widgets", Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun configureWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
                return
            } catch (e: Exception) {
                Diag.log("widget configure: ${e.message}")
            }
        }
        finishWidget(id)
    }

    private fun finishWidget(id: Int) {
        val p = pendingWidget ?: return
        pendingWidget = null
        setCard(p.first, p.second, "widget:$id")
    }

    private fun cleanupWidgets() {
        if (dashMode) return
        try {
            val used = pages.flatMap { it.cards }.filter { it.startsWith("widget:") }.mapNotNull { it.substringAfter(':').toIntOrNull() }.toSet()
            for (id in widgetHost.appWidgetIds) if (id !in used) widgetHost.deleteAppWidgetId(id)
        } catch (_: Exception) {}
    }

    // ---- pictures

    /** Pick a video for the wallpaper (kept by reference, not copied). */
    fun pickVideo() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_VIDEO)
        } catch (_: Exception) {
            Toast.makeText(this, "No file app found to choose a video", Toast.LENGTH_LONG).show()
        }
    }

    fun pickImage(req: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), req)
        } catch (_: Exception) {
            Toast.makeText(this, "No gallery or file app found to choose a picture", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_LOGO, REQ_BG -> {
                val uri = data?.data ?: return
                if (resultCode != RESULT_OK) return
                try {
                    val bmp = decodeScaled(uri, if (requestCode == REQ_BG) 1920 else 1024) ?: throw IllegalStateException("not a picture")
                    if (requestCode == REQ_LOGO) {
                        FileOutputStream(File(filesDir, "logo.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } else {
                        FileOutputStream(File(filesDir, "bg.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                        Prefs.background = "photo"
                    }
                    rebuild()
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't use that picture (${e.message})", Toast.LENGTH_LONG).show()
                }
            }
            REQ_CONTACT -> {
                if (resultCode == RESULT_OK) data?.data?.let { PhoneHub.onPicked(this, it) }
            }
            REQ_VIDEO -> {
                val uri = data?.data ?: return
                if (resultCode != RESULT_OK) return
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                Prefs.videoUri = uri.toString()
                Prefs.background = "video"
                rebuild()
            }
            REQ_CAR -> {
                val uri = data?.data ?: return
                if (resultCode != RESULT_OK) return
                try {
                    var bmp = decodeScaled(uri, 1600) ?: throw IllegalStateException("not a picture")
                    if (bmp.hasAlpha()) bmp = trimTransparent(bmp)
                    FileOutputStream(CarArt.photoFile(this)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Prefs.carPhoto = true
                    CarArt.reset()
                    rebuild()
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't use that picture (${e.message})", Toast.LENGTH_LONG).show()
                }
            }
            REQ_BIND -> {
                val id = pendingWidget?.third ?: return
                if (resultCode == RESULT_OK) configureWidget(id) else { widgetHost.deleteAppWidgetId(id); pendingWidget = null }
            }
            REQ_CONFIG -> {
                val id = pendingWidget?.third ?: return
                if (resultCode == RESULT_OK) finishWidget(id) else { widgetHost.deleteAppWidgetId(id); pendingWidget = null }
            }
        }
    }

    /** Crop the see-through border of a cut-out picture so the car fills its space. */
    private fun trimTransparent(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 16) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
        }
        if (r < l || bt < t) return b
        // fully opaque picture: treat it as a normal photo
        if (l == 0 && t == 0 && r == w - 1 && bt == h - 1 && px.all { (it ushr 24) == 255 }) return b.copy(Bitmap.Config.RGB_565, false) ?: b
        return Bitmap.createBitmap(b, l, t, r - l + 1, bt - t + 1)
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxSide * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val k = min(1f, maxSide.toFloat() / max(b.width, b.height))
        return if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
    }

    // ---- split screen

    fun splitScreen() {
        if (dashMode) { launchSplitApp(); return }
        try {
            startActivity(Intent(this, DashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Diag.log("split: ${e.message}")
            openNav()
        }
    }

    fun splitTarget(): String? = Prefs.splitApp.takeIf { it.isNotEmpty() && Apps.canLaunch(this, it) }
        ?: Apps.findMaps(this) ?: Apps.findZlink(this)

    private fun launchSplitApp() {
        val pkg = splitTarget() ?: run { Toast.makeText(this, "No maps app installed", Toast.LENGTH_SHORT).show(); return }
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try { startActivity(i) } catch (e: Exception) { Diag.log("split launch: ${e.message}") }
        handler.postDelayed({
            val ok = isInMultiWindowMode
            Diag.log("split screen ${if (ok) "works" else "not available on this unit"}")
            if (!ok) Toast.makeText(applicationContext, "This unit doesn't allow split screen — opened Maps full screen.", Toast.LENGTH_LONG).show()
        }, 2500)
    }

    // ---------------------------------------------------------------- dialogs

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(this,
        if (C.night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)

    fun confirm(title: String, msg: String, ok: () -> Unit) {
        dialog().setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> ok() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Small form: text fields, an optional check box, an optional extra button. */
    fun form(title: String, fields: List<Field>, checkbox: String?, extra: Pair<String, () -> Unit>? = null, ok: (List<String>, Boolean) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val edits = fields.map { f ->
            box.addView(TextView(this).apply { text = f.label; setPadding(0, 16, 0, 0) })
            EditText(this).apply {
                setText(f.value)
                inputType = if (f.numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setSingleLine(true)
                selectAll()
                box.addView(this)
            }
        }
        val cb = checkbox?.let { CheckBox(this).apply { text = it; isChecked = true; box.addView(this) } }
        val b = dialog().setTitle(title).setView(box)
            .setPositiveButton("Save") { _, _ -> ok(edits.map { it.text.toString().replace(',', '.').trim() }, cb?.isChecked ?: false) }
            .setNegativeButton("Cancel", null)
        if (extra != null) b.setNeutralButton(extra.first) { _, _ -> extra.second() }
        b.show()
    }

    /** Pick an app from the installed ones; [fixed] are extra choices shown first (label to value). */
    fun chooseApp(title: String, fixed: List<Pair<String, String>>, pick: (String) -> Unit) {
        val list = Apps.launchables(this)
        val labels = fixed.map { it.first } + list.map { it.loadLabel(packageManager).toString() }
        dialog().setTitle(title).setItems(labels.toTypedArray()) { _, i ->
            pick(if (i < fixed.size) fixed[i].second else list[i - fixed.size].activityInfo.packageName)
        }.setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- permissions & settings

    fun requestPerms(fromButton: Boolean) {
        val want = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.ACCESS_FINE_LOCATION)
            want.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.POST_NOTIFICATIONS)
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
        else if (fromButton) openAppSettings()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Gps.start(this)
        DriveService.start(this)
        if (requestCode == 2 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) chooseObd()
        if (requestCode == REQ_PHONE) rebuild()
    }

    private fun tryStart(vararg intents: Intent): Boolean {
        for (i in intents) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
        }
        return false
    }

    fun openAppSettings() {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    fun openOverlaySettings() {
        if (!tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)))
            Toast.makeText(this, "Open Settings › Apps › Drive Launcher › Display over other apps", Toast.LENGTH_LONG).show()
    }

    fun openWriteSettings() {
        if (!tryStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")), Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)))
            Toast.makeText(this, "Open Settings › Apps › Drive Launcher › Modify system settings", Toast.LENGTH_LONG).show()
    }

    fun openNotificationAccess() {
        val cn = ComponentName(this, MediaListenerService::class.java)
        val list = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 30) list.add(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
        )
        list.add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        list.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        if (!tryStart(*list.toTypedArray())) {
            dialog().setTitle("Notification access")
                .setMessage("This unit hides the setting. From a computer, run:\n\nadb shell cmd notification allow_listener ${cn.flattenToString()}")
                .setPositiveButton("OK", null).show()
        }
    }

    fun openHomeSettings() {
        if (!tryStart(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
            Toast.makeText(this, "Open Settings › Apps › Default apps › Home app", Toast.LENGTH_LONG).show()
    }

    fun openNav() {
        val pkg = (if (NavHub.active) NavHub.pkg else null) ?: Apps.findMaps(this) ?: Apps.findZlink(this)
        if (pkg == null || !Apps.launch(this, pkg)) Toast.makeText(this, "No maps app installed", Toast.LENGTH_SHORT).show()
    }

    fun voice() {
        val ok = tryStart(Intent(Intent.ACTION_VOICE_COMMAND), Intent("android.intent.action.ASSIST"), Intent(Intent.ACTION_WEB_SEARCH))
        if (!ok) Toast.makeText(this, "No voice assistant on this unit", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    fun chooseObd() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val names = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        names.add("Automatic — find a paired OBD2 adapter")
        actions.add { Prefs.obdMode = "auto"; Prefs.obdAddr = "" }
        try {
            val bonded = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices ?: emptySet()
            for (d in bonded) {
                names.add("${d.name ?: "Unknown device"}  (${d.address})")
                actions.add { Prefs.obdMode = "bt"; Prefs.obdAddr = d.address }
            }
        } catch (e: Exception) {
            Diag.log("bonded list: ${e.message}")
        }
        names.add("Wi-Fi OBD2 adapter (${Prefs.obdHost})")
        actions.add { Prefs.obdMode = "wifi" }
        names.add("Off")
        actions.add { Prefs.obdMode = "off" }
        dialog().setTitle("OBD2 adapter")
            .setItems(names.toTypedArray()) { _, which ->
                actions[which]()
                Obd.restart(this)
                Toast.makeText(this, "OBD2: ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val REQ_LOGO = 11
        const val REQ_BG = 12
        const val REQ_BIND = 13
        const val REQ_CONFIG = 14
        const val REQ_CAR = 15
        const val REQ_VIDEO = 16
        const val REQ_PHONE = 17
        const val REQ_CONTACT = 18
    }
}

/** Compact dashboard shown next to Google Maps in split screen. */
class DashActivity : MainActivity() {
    override val dashMode = true
}
