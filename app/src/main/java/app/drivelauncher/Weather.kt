package app.drivelauncher

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Current temperature from Open-Meteo (free, no key). Refreshes every 30 minutes. */
object Weather {
    @Volatile var tempC = Float.NaN
    @Volatile var code = -1
    @Volatile var isDay = true
    @Volatile private var lastFetch = 0L
    @Volatile private var busy = false

    fun maybeFetch(lat: Double, lon: Double) {
        if (lat.isNaN() || lon.isNaN() || busy) return
        val now = SystemClock.elapsedRealtime()
        if (lastFetch != 0L && now - lastFetch < 30 * 60_000L) return
        busy = true
        Thread {
            try {
                val url = URL(fmt("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day", lat, lon))
                val con = url.openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                con.setRequestProperty("User-Agent", TileMapView.UA)
                val body = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                tempC = cur.getDouble("temperature_2m").toFloat()
                code = cur.optInt("weather_code", -1)
                isDay = cur.optInt("is_day", 1) == 1
                lastFetch = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                Diag.log("weather: ${e.javaClass.simpleName} ${e.message}")
                lastFetch = SystemClock.elapsedRealtime() - 25 * 60_000L // retry in ~5 min
            } finally {
                busy = false
            }
        }.start()
    }

    fun icon(): Ic = when {
        code in 0..1 || code < 0 -> if (isDay) Ic.SUN else Ic.MOON
        code in 2..48 -> Ic.CLOUD
        else -> Ic.RAIN
    }
}
