package app.drivelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
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

    private val pagesJson = """[{"layout":"classic","cards":["speed","trip","road","gauges","map","media"]},""" +
        """{"layout":"map","cards":["speed","media","map"]},""" +
        """{"layout":"grid","cards":["clock","compass","car","logo"]}]"""
    private val glassJson = """[{"layout":"aurora","cards":["hero","nav","media"]},""" +
        """{"layout":"map","cards":["speed","media","map"]},""" +
        """{"layout":"grid","cards":["clock","compass","car","stats"]}]"""

    private fun sample(act: MainActivity) {
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
        Obd.mil = true
        Obd.dtcStored = listOf("P0133", "P0442")
        Obd.dtcPending = listOf("P0171")
        Obd.dtcAt = System.currentTimeMillis()
        Obd.dtcStatus = "2 stored, 1 pending"
        NavHub.active = true
        NavHub.title = "300 m"
        NavHub.text = "Turn right onto Sukhumvit Rd"
        NavHub.sub = "18 min · 9.2 km · ETA 9:23 PM"
        NavHub.pkg = "com.google.android.apps.maps"
        NavHub.version++
        Weather.tempC = 31f
        Weather.code = 0
        Weather.isDay = false
        Prefs.speedLimit = 40
        SpeedAlert.check(act)
        MediaHub.showForTest(Track("Midnight Ring Road", "The Low Beams", "com.zjinnova.zlink", null,
            236_000L, 145_000L, SystemClock.elapsedRealtime(), true, 1f, "notification"))
        Odometer.set(84_250.0)
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val route = listOf(doubleArrayOf(13.7466, 100.5393), doubleArrayOf(13.7440, 100.5480), doubleArrayOf(13.7380, 100.5600),
            doubleArrayOf(13.7300, 100.5690), doubleArrayOf(13.7230, 100.5800))
        Trips.addForTest(TripRecord(now - 2 * 3_600_000L, now - 2 * 3_600_000L + 27 * 60_000L, 12_400.0, 24 * 60_000L, 74f,
            13.7466, 100.5393, 13.7230, 100.5800, 1.1), route)
        Trips.addForTest(TripRecord(now - day - 5 * 3_600_000L, now - day - 5 * 3_600_000L + 41 * 60_000L, 23_900.0, 36 * 60_000L, 96f,
            13.7230, 100.5800, 13.7466, 100.5393, 2.0), route.reversed())
        FuelLog.add(FuelEntry(now - 20 * day, 42.0, 1470.0, 83_100.0, true))
        FuelLog.add(FuelEntry(now - 9 * day, 38.5, 1386.0, 83_690.0, true))
        FuelLog.add(FuelEntry(now - 2 * day, 20.0, 730.0, 84_050.0, false))
        ServiceLog.list()[0].lastKm = 74_700.0
        ServiceLog.save()
        Parking.save(13.7472, 100.5401, now - 40 * 60_000L)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun save(act: MainActivity, name: String) {
        idle()
        val v = act.window.decorView
        assertTrue("window has no size", v.width > 0 && v.height > 0)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findScroll(v: View): ScrollView? {
        if (v is ScrollView && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun shoot(name: String, full: Boolean) {
        Motion.allowed = false // no half-finished animations in the pictures
        val app = RuntimeEnvironment.getApplication()
        Prefs.init(app)
        Prefs.obdMode = "off"
        Prefs.theme = "night"
        Prefs.skin = Skin.GLASS
        Prefs.pages = glassJson
        val ctrl = Robolectric.buildActivity(MainActivity::class.java).setup()
        val act = ctrl.get()
        Obd.stop()
        Thread.sleep(500)
        sample(act)
        act.rebuild()
        idle()
        // ---- Aurora Glass (default theme)
        act.showHome(1)
        Thread.sleep(5000); idle()
        SystemClock.sleep(9000) // let map tiles download and failed ones retry
        act.showHome(1); Thread.sleep(3000)
        Gps.lastFix = SystemClock.elapsedRealtime()
        save(act, "$name-glass-home2")
        act.showHome(0); save(act, "$name-glass-home1")
        act.showHome(2); save(act, "$name-glass-home3")
        act.show(MainActivity.Page.TELEMETRY); save(act, "$name-glass-telemetry")
        if (full) {
            for ((i, t) in listOf("trips", "fuel", "service", "engine", "parking").withIndex()) {
                act.showCarTab(i)
                if (t == "parking") Thread.sleep(2500)
                save(act, "$name-glass-car-$t")
            }
            act.show(MainActivity.Page.SOURCES); save(act, "$name-glass-settings")
            findScroll(act.window.decorView)?.let { sv ->
                sv.scrollTo(0, 1100); save(act, "$name-glass-settings2")
                sv.scrollTo(0, 100_000); save(act, "$name-glass-settings3")
            }
            act.show(MainActivity.Page.APPS); save(act, "$name-glass-apps")
            Prefs.theme = "day"
            act.applyTheme()
            act.showHome(0); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-glass-day-home1")
            act.showHome(1); Thread.sleep(2500); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-glass-day-home2")
            act.showCarTab(1); save(act, "$name-glass-day-car-fuel")
            Prefs.theme = "night"
            Prefs.background = "stars"
            act.applyTheme()
            act.showHome(0); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-glass-stars-home1")
            Prefs.background = "plain"
        }
        // ---- Carbon Sport (standard pages of the theme)
        Prefs.skin = Skin.SPORT
        Prefs.pages = ""
        act.rebuild()
        act.showHome(0); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-sport-home1")
        act.showHome(1); Thread.sleep(2500); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-sport-home2")
        act.showHome(2); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-sport-home3")
        if (full) {
            act.show(MainActivity.Page.TELEMETRY); save(act, "$name-sport-telemetry")
            act.showCarTab(2); save(act, "$name-sport-car-service")
            act.show(MainActivity.Page.SOURCES); save(act, "$name-sport-settings")
            Prefs.theme = "day"
            act.applyTheme()
            act.showHome(0); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-sport-day-home1")
            Prefs.theme = "night"
        }
        // ---- Classic
        Prefs.skin = Skin.CLASSIC
        Prefs.pages = pagesJson
        act.rebuild()
        act.showHome(0); Thread.sleep(1500); Gps.lastFix = SystemClock.elapsedRealtime(); save(act, "$name-classic-home1")
        if (full) { act.showHome(2); save(act, "$name-classic-home3") }
        println(Diag.recentLines(80).joinToString("\n"))
        ctrl.pause().stop().destroy()
    }

    @Test @Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
    fun ts10s_1280x720() = shoot("1280x720", true)

    @Test @Config(sdk = [34], qualifiers = "w1024dp-h600dp-land-mdpi")
    fun small_1024x600() = shoot("1024x600", false)

    @Test @Config(sdk = [34], qualifiers = "w768dp-h1024dp-port-mdpi")
    fun portrait_768x1024() = shoot("768x1024", true)
}
