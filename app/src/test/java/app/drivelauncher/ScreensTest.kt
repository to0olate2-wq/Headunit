package app.drivelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Starts the real launcher in a simulated Android device, fills it with sample
 * data and saves a picture of every screen into build/screens. Catches start-up
 * crashes and layout problems before anything is installed on the car.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {

    private fun sample() {
        Obd.stop()
        Gps.permission = true
        Gps.speedKmh = 42f
        Gps.lastFix = SystemClock.elapsedRealtime()
        Gps.lat = 13.7466
        Gps.lon = 100.5393
        Gps.bearing = 45f
        Gps.alt = 12.0
        Gps.accuracy = 4f
        Gps.satsUsed = 9
        Gps.satsSeen = 14
        Gps.tripMeters = 4300.0
        Gps.tripMovingMs = 12 * 60_000L
        Gps.tripMaxKmh = 88f
        Gps.tripStart = System.currentTimeMillis() - 15 * 60_000L
        Obd.state = Obd.State.CONNECTED
        Obd.adapterName = "OBDII"
        Obd.detail = "Connected"
        mapOf("rpm" to 3053f, "speed" to 42f, "coolant" to 88f, "throttle" to 18f, "volt" to 13.4f, "map" to 115f,
            "maf" to 13.1f, "iat" to 34f, "load" to 42f, "fuel" to 64f).forEach { (k, v) -> Obd.values[k] = v }
        NavHub.active = true
        NavHub.title = "300 m"
        NavHub.text = "Turn right onto Sukhumvit Rd"
        NavHub.sub = "18 min · 9.2 km · ETA 9:23 PM"
        NavHub.pkg = "com.google.android.apps.maps"
        NavHub.version++
        Weather.tempC = 31f
        Weather.code = 0
        Weather.isDay = false
        MediaHub.showForTest(Track("Midnight Ring Road", "The Low Beams", "com.zjinnova.zlink", null,
            236_000L, 145_000L, SystemClock.elapsedRealtime(), true, 1f, "notification"))
    }

    private fun shoot(name: String) {
        Prefs.init(RuntimeEnvironment.getApplication())
        Prefs.obdMode = "off"
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        Obd.stop()
        Thread.sleep(500)
        sample()
        shadowOf(Looper.getMainLooper()).idle()
        // give map tiles a moment to download, then draw
        val pages = listOf(MainActivity.Page.DRIVE, MainActivity.Page.TELEMETRY, MainActivity.Page.SOURCES, MainActivity.Page.APPS)
        val dir = File("build/screens").apply { mkdirs() }
        for (p in pages) {
            act.show(p)
            if (p == MainActivity.Page.DRIVE) {
                Thread.sleep(5000)
                shadowOf(Looper.getMainLooper()).idle()
                SystemClock.sleep(9000) // advance the simulated clock so failed tiles are retried
                act.show(p)
                Thread.sleep(3000)
                println(Diag.recentLines(60).joinToString("\n"))
            }
            shadowOf(Looper.getMainLooper()).idle()
            val v = act.window.decorView
            assertTrue("window has no size", v.width > 0 && v.height > 0)
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            FileOutputStream(File(dir, "$name-${p.name.lowercase()}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (p == MainActivity.Page.SOURCES) {
                val sv = findScroll(v)
                if (sv != null) {
                    sv.scrollTo(0, 100_000)
                    shadowOf(Looper.getMainLooper()).idle()
                    val b2 = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                    v.draw(Canvas(b2))
                    FileOutputStream(File(dir, "$name-sources-bottom.png")).use { b2.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        ctrl.pause().stop().destroy()
    }

    private fun findScroll(v: android.view.View): android.widget.ScrollView? {
        if (v is android.widget.ScrollView && v.isShown) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun ts10s_1280x720() = shoot("1280x720")

    @Test @Config(sdk = [34], qualifiers = "w1024dp-h600dp-land-mdpi")
    fun small_1024x600() = shoot("1024x600")

    @Test @Config(sdk = [34], qualifiers = "w768dp-h1024dp-port-mdpi")
    fun portrait_768x1024() = shoot("768x1024")
}
