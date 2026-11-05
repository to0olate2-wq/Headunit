package app.drivelauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/** GPS speed, heading, position and trip computer. Works on every unit, no setup. */
object Gps {
    var speedKmh = 0f
    var bearing = 0f
    var lat = Double.NaN
    var lon = Double.NaN
    var alt = Double.NaN
    var accuracy = 0f
    var lastFix = 0L
    var satsUsed = 0
    var satsSeen = 0
    var permission = false
    private var started = false

    var tripMeters = 0.0
    var tripMovingMs = 0L
    var tripMaxKmh = 0f
    var tripStart = 0L
    private var prev: Location? = null
    private var lastSave = 0L

    val fresh: Boolean get() = lastFix != 0L && SystemClock.elapsedRealtime() - lastFix < 4000
    val avgKmh: Float get() = if (tripMovingMs > 5000) (tripMeters / (tripMovingMs / 1000.0) * 3.6).toFloat() else 0f

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (!fresh) { lat = l.latitude; lon = l.longitude }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val gnssCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
            satsSeen = status.satelliteCount
        }
    }

    fun start(ctx: Context) {
        if (started) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permission = false
            return
        }
        permission = true
        started = true
        tripMeters = Prefs.tripM
        tripMovingMs = Prefs.tripMs
        tripMaxKmh = Prefs.tripMax
        tripStart = Prefs.tripStart
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val main = Looper.getMainLooper()
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, main)
        } catch (e: Exception) {
            Diag.log("gps: ${e.message}")
        }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, netListener, main)
        } catch (e: Exception) {
            Diag.log("network location: ${e.message}")
        }
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) { lat = last.latitude; lon = last.longitude }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(gnssCb, Handler(main))
        } catch (_: Exception) {
        }
    }

    private fun onFix(l: Location) {
        val now = SystemClock.elapsedRealtime()
        val p = prev
        var kmh = if (l.hasSpeed()) l.speed * 3.6f else if (p != null) {
            val dt = (l.time - p.time) / 1000f
            if (dt > 0f) p.distanceTo(l) / dt * 3.6f else 0f
        } else 0f
        if (kmh < 2f) kmh = 0f
        speedKmh = if (kmh == 0f) 0f else kmh * 0.75f + speedKmh * 0.25f
        if (l.hasBearing() && kmh > 3f) bearing = l.bearing
        lat = l.latitude
        lon = l.longitude
        if (l.hasAltitude()) alt = l.altitude
        accuracy = l.accuracy
        lastFix = now
        if (p != null && l.accuracy < 35f && kmh > 3f) {
            val d = p.distanceTo(l)
            if (d < 500f) {
                tripMeters += d
                tripMovingMs += min(max(0L, l.time - p.time), 5000L)
                Odometer.add(d.toDouble())
            }
        }
        if (kmh > tripMaxKmh) tripMaxKmh = kmh
        if (tripStart == 0L && kmh > 3f) tripStart = System.currentTimeMillis()
        prev = l
        try { Trips.onFix(l.latitude, l.longitude, kmh, l.accuracy, l.time) } catch (e: Exception) { Diag.log("trips: ${e.message}") }
        if (now - lastSave > 30_000) save()
        Weather.maybeFetch(lat, lon)
    }

    fun save() {
        lastSave = SystemClock.elapsedRealtime()
        Odometer.save()
        try { Prefs.saveTrip(tripMeters, tripMovingMs, tripMaxKmh, tripStart) } catch (_: Exception) {}
    }

    fun resetTrip() {
        tripMeters = 0.0; tripMovingMs = 0L; tripMaxKmh = 0f; tripStart = 0L
        Obd.resetEconomy()
        save()
    }

    fun compass(deg: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(((deg % 360f) + 360f) % 360f / 45f + 0.5f).toInt() % 8]
    }
}
