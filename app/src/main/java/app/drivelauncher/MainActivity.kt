package app.drivelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity() {
    enum class Page { DRIVE, TELEMETRY, SOURCES, APPS }

    private lateinit var root: FrameLayout
    private var page = Page.DRIVE
    private var builtW = 0
    private var builtH = 0
    private var drive: DrivePage? = null
    private var tele: TelemetryPage? = null
    private var sources: SourcesPage? = null
    private var apps: AppsPage? = null
    private var dock: Dock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var tickCount = 0

    private val tick = object : Runnable {
        override fun run() {
            update()
            tickCount++
            if (tickCount % 12 == 0 && !MediaHub.accessGranted) MediaHub.tryAttach()
            if (resumed) handler.postDelayed(this, 250)
        }
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > S.p(140f) && abs(dx) > abs(dy) * 1.5f && abs(velocityX) > 700f) {
                    if (dx < 0 && page == Page.DRIVE) { show(Page.TELEMETRY); return true }
                    if (dx > 0 && page == Page.TELEMETRY) { show(Page.DRIVE); return true }
                }
                return false
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        F.load(this)
        C.accent = Prefs.accent
        Diag.log("launcher started, ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
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
        requestPerms(false)
        Gps.start(this)
    }

    // ---------- layout ----------

    private fun build(w: Int, h: Int, force: Boolean) {
        if (!force && w == builtW && h == builtH) return
        builtW = w
        builtH = h
        val portrait = h > w * 1.05f
        S.u = (if (portrait) min(w / 720f, h / 1280f) else min(w / 1280f, h / 720f)).coerceIn(0.45f, 3f)
        root.removeAllViews()
        val col = vbox(12f)
        col.pad(16f)
        val host = FrameLayout(this)
        val d = DrivePage(this, portrait)
        val t = TelemetryPage(this, portrait)
        val s = SourcesPage(this, portrait, w, h)
        val ap = AppsPage(this, w)
        host.addView(d.view, flp(MATCH, MATCH))
        host.addView(t.view, flp(MATCH, MATCH))
        host.addView(s.view, flp(MATCH, MATCH))
        host.addView(ap.view, flp(MATCH, MATCH))
        val dk = Dock(this, portrait)
        col.addView(host, lp(MATCH, 0, 1f))
        col.addView(dk.view, lp(MATCH, S.i(64)))
        root.addView(col, flp(MATCH, MATCH))
        drive = d; tele = t; sources = s; apps = ap; dock = dk
        show(page)
        Diag.log("layout ${w}x$h scale ${fmt("%.2f", S.u)} ${if (portrait) "portrait" else "landscape"}")
    }

    /** Rebuild every screen (after accent / unit changes). */
    fun rebuild() {
        if (builtW > 0) build(builtW, builtH, true)
    }

    fun show(p: Page) {
        page = p
        drive?.view?.showIf(p == Page.DRIVE)
        tele?.view?.showIf(p == Page.TELEMETRY)
        sources?.view?.showIf(p == Page.SOURCES)
        apps?.view?.showIf(p == Page.APPS)
        if (p == Page.APPS) apps?.refresh()
        update()
    }

    private fun update() {
        try {
            when (page) {
                Page.DRIVE -> drive?.update()
                Page.TELEMETRY -> tele?.update()
                Page.SOURCES -> sources?.update()
                Page.APPS -> {}
            }
            dock?.update(page, drive?.clock() ?: "")
        } catch (e: Exception) {
            Diag.log("ui update: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** Refresh shortly after a button press so the result shows quickly. */
    fun nudge() {
        handler.postDelayed({ MediaHub.pick(); update() }, 350)
    }

    // ---------- lifecycle ----------

    override fun onResume() {
        super.onResume()
        resumed = true
        MediaHub.tryAttach()
        Gps.start(this)
        applyFullscreen()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(tick)
        Gps.save()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        show(Page.DRIVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.DRIVE) show(Page.DRIVE)
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
        try { gestures.onTouchEvent(ev) } catch (_: Exception) {}
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    fun applyFullscreen() {
        window.decorView.systemUiVisibility = if (Prefs.fullscreen)
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        else 0
    }

    // ---------- permissions & settings ----------

    fun requestPerms(fromButton: Boolean) {
        val want = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            want.add(Manifest.permission.ACCESS_FINE_LOCATION)
            want.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            want.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
        else if (fromButton) openAppSettings()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Gps.start(this)
        if (requestCode == 2 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) chooseObd()
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
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Notification access")
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

    fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title).setMessage(msg)
            .setPositiveButton("OK") { _, _ -> ok() }
            .setNegativeButton("Cancel", null)
            .show()
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("OBD2 adapter")
            .setItems(names.toTypedArray()) { _, which ->
                actions[which]()
                Obd.restart(this)
                Toast.makeText(this, "OBD2: ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
