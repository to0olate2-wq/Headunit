package app.drivelauncher

import android.app.Activity
import android.content.Context
import android.provider.Settings
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise / sunset (NOAA approximation, about ±2 minutes), pure and unit-tested. */
object Sun {
    /** Sunrise and sunset in minutes after midnight UTC (may be <0 or >1440), or null in polar day/night. */
    fun utcMinutes(lat: Double, lon: Double, dayOfYear: Int): Pair<Double, Double>? {
        val g = 2 * Math.PI / 365.0 * (dayOfYear - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (x < -1 || x > 1) return null
        val ha = Math.toDegrees(acos(x))
        return (720 - 4 * (lon + ha) - eq) to (720 - 4 * (lon - ha) - eq)
    }

    /** True between local sunrise and sunset. */
    fun isDay(lat: Double, lon: Double, now: Long, tz: TimeZone = TimeZone.getDefault()): Boolean {
        val c = Calendar.getInstance(tz).apply { timeInMillis = now }
        val off = tz.getOffset(now) / 60000.0
        val t = utcMinutes(lat, lon, c.get(Calendar.DAY_OF_YEAR)) ?: return lat * (if (c.get(Calendar.MONTH) in 3..8) 1 else -1) > 0
        val rise = ((t.first + off) % 1440 + 1440) % 1440
        val set = ((t.second + off) % 1440 + 1440) % 1440
        val m = c.get(Calendar.HOUR_OF_DAY) * 60.0 + c.get(Calendar.MINUTE)
        return if (rise < set) m in rise..set else m >= rise || m <= set
    }
}

/** Picks day or night look and screen brightness. */
object ThemeMgr {
    fun wantNight(): Boolean = when (Prefs.theme) {
        "day" -> false
        "night" -> true
        else -> {
            val now = System.currentTimeMillis()
            if (!Gps.lat.isNaN()) !Sun.isDay(Gps.lat, Gps.lon, now)
            else {
                val c = Calendar.getInstance()
                val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                m < 6 * 60 + 30 || m > 18 * 60 + 30
            }
        }
    }

    fun canWriteSettings(ctx: Context): Boolean = try { Settings.System.canWrite(ctx) } catch (_: Exception) { false }

    /** Bright by day, dimmer at night. Uses the system setting when allowed, otherwise only this window. */
    fun applyBrightness(a: Activity, night: Boolean) {
        if (!Prefs.autoBright) return
        val level = if (night) (Prefs.nightBright * 255 / 100).coerceIn(10, 255) else 255
        if (canWriteSettings(a)) {
            try {
                Settings.System.putInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                return
            } catch (e: Exception) {
                Diag.log("brightness: ${e.message}")
            }
        }
        val lp = a.window.attributes
        lp.screenBrightness = level / 255f
        a.window.attributes = lp
    }
}
