package app.drivelauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mapsforge.core.model.LatLong
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToLong

/** Built-in navigation: route parsing, words, following the route, map list, rough offline routes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NavTest {
    @Before fun setUp() {
        Prefs.init(RuntimeEnvironment.getApplication())
        Prefs.imperial = false
        Prefs.sb("navVoice", false)
    }

    private fun encode(pts: List<DoubleArray>, precision: Int = 6): String {
        val f = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pla = 0L; var plo = 0L
        for (p in pts) {
            val la = (p[0] * f).roundToLong(); val lo = (p[1] * f).roundToLong()
            for (d in listOf(la - pla, lo - plo)) {
                var v = if (d < 0) (d shl 1).inv() else d shl 1
                while (v >= 0x20) { sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar()); v = v shr 5 }
                sb.append((v + 63).toInt().toChar())
            }
            pla = la; plo = lo
        }
        return sb.toString()
    }

    @Test fun polyline() {
        val p = NavNet.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertEquals(38.5, p[0][0], 1e-6); assertEquals(-120.2, p[0][1], 1e-6)
        assertEquals(43.252, p[2][0], 1e-6); assertEquals(-126.453, p[2][1], 1e-6)
        val pts = listOf(doubleArrayOf(13.7563, 100.5018), doubleArrayOf(13.7570, 100.5101))
        val back = NavNet.decodePolyline(encode(pts))
        assertEquals(13.757, back[1][0], 1e-6); assertEquals(100.5101, back[1][1], 1e-6)
    }

    /** A 2 km route: 1 km east, then right (south) 1 km. */
    private fun lRoute(): Route {
        val pts = ArrayList<DoubleArray>()
        for (i in 0..10) pts.add(doubleArrayOf(13.0, 100.0 + i * 0.000924))   // ≈ 100 m steps east
        for (i in 1..10) pts.add(doubleArrayOf(13.0 - i * 0.0009, 100.00924))  // ≈ 100 m steps south
        val m = listOf(
            Maneuver(13.0, 100.0, "depart", "", 0, "Main Road"),
            Maneuver(13.0, 100.00924, "turn", "right", 0, "Side Street"),
            Maneuver(pts.last()[0], pts.last()[1], "arrive", "", 0, ""),
        )
        return Route(pts, m, 2000.0, 240.0, "online")
    }

    @Test fun osrmAnswer() {
        val pts = lRoute().points
        fun step(type: String, mod: String, lat: Double, lon: Double, name: String, exit: Int = 0) = JSONObject()
            .put("name", name).put("maneuver", JSONObject().put("type", type).put("modifier", mod).put("exit", exit)
                .put("location", JSONArray().put(lon).put(lat)))
        val steps = JSONArray()
            .put(step("depart", "", 13.0, 100.0, "Main Road"))
            .put(step("roundabout", "right", 13.0, 100.005, "Ring Road", 2))
            .put(step("exit roundabout", "right", 13.0, 100.0051, "Ring Road"))
            .put(step("turn", "right", 13.0, 100.00924, "Side Street"))
            .put(step("arrive", "", pts.last()[0], pts.last()[1], ""))
        val js = JSONObject().put("code", "Ok").put("routes", JSONArray().put(JSONObject()
            .put("geometry", encode(pts)).put("distance", 2000.0).put("duration", 240.0)
            .put("legs", JSONArray().put(JSONObject().put("steps", steps)))))
        val r = NavNet.parseOsrm(js.toString())
        assertEquals(4, r.maneuvers.size) // "exit roundabout" is dropped
        assertEquals("Start on Main Road", Words.instruction(r.maneuvers[0]))
        assertEquals("At the roundabout, take the 2nd exit onto Ring Road", Words.instruction(r.maneuvers[1]))
        assertEquals("Turn right onto Side Street", Words.instruction(r.maneuvers[2]))
        assertEquals("You have arrived", Words.instruction(r.maneuvers[3]))
        assertTrue(abs(r.length - 2000) < 60)
        assertTrue(abs(r.maneuvers[2].at - 1000) < 60)
    }

    @Test fun words() {
        assertEquals("In 500 metres", Words.spokenIn(510.0))
        assertEquals("In 1.5 kilometres", Words.spokenIn(1520.0))
        assertEquals("In 1 kilometre", Words.spokenIn(990.0))
        assertEquals("1.2 km", Words.distance(1234.0))
        assertEquals("350 m", Words.distance(347.0))
        assertEquals("1 h 5 min", Words.duration(3900.0))
        assertEquals("Keep left onto A1", Words.instruction(Maneuver(0.0, 0.0, "fork", "slight left", 0, "A1")))
    }

    @Test fun followsTheRoute() {
        val app = RuntimeEnvironment.getApplication()
        val r = lRoute()
        Guide.setForTest(app, Dest("Shop", "", r.points.last()[0], r.points.last()[1]), r, 0.0)
        assertTrue(Guide.active)
        // halfway along the first leg: next is the right turn, ~500 m away
        Guide.onFix(13.0, 100.00462, 40f, 90f)
        assertEquals(1, Guide.next)
        assertTrue("toNext ${Guide.toNext}", abs(Guide.toNext - 500) < 40)
        assertEquals("Turn right onto Side Street", NavHub.text)
        // after the turn: next is the arrival
        Guide.onFix(12.997, 100.00924, 40f, 180f)
        assertEquals(2, Guide.next)
        assertTrue(abs(Guide.remaining() - 700) < 60)
        Guide.stop()
        assertFalse(Guide.active)
        assertFalse(NavHub.active)
    }

    @Test fun mapList() {
        val apache = """<tr><td><a href="?C=N;O=D">Name</a></td></tr>
<tr><td valign="top"><img src="/icons/back.gif"></td><td><a href="/maps/v5/">Parent Directory</a></td></tr>
<tr><td><a href="thailand.map">thailand.map</a></td><td align="right">2026-10-14 03:51  </td><td align="right">284M</td><td>&nbsp;</td></tr>
<tr><td><a href="japan/">japan/</a></td><td align="right">2026-10-14 03:51  </td><td align="right">  - </td></tr>"""
        val a = OfflineMaps.parseListing(apache, "asia/")
        assertEquals(2, a.size)
        assertEquals("Thailand", a[0].label); assertEquals("asia/thailand.map", a[0].path); assertEquals("284 MB", a[0].size)
        assertTrue(a[1].isDir)
        val nginx = """<a href="../">../</a>
<a href="laos.map">laos.map</a>                                          14-Oct-2026 03:51            52428800
<a href="us-west/">us-west/</a>                                          14-Oct-2026 03:51                   -"""
        val b = OfflineMaps.parseListing(nginx, "asia/")
        assertEquals(2, b.size)
        assertEquals("50 MB", b[0].size)
        assertEquals("Us West", b[1].label)
    }

    @Test fun photon() {
        val js = """{"features":[{"geometry":{"coordinates":[100.5,13.75]},"properties":{"name":"Siam Paragon","street":"Rama I Road","city":"Bangkok","country":"Thailand"}}]}"""
        val l = NavNet.parsePhoton(js)
        assertEquals(1, l.size)
        assertEquals("Siam Paragon", l[0].name)
        assertEquals(13.75, l[0].lat, 1e-9)
        assertTrue(l[0].detail.contains("Bangkok"))
    }

    @Test fun roughOfflineRoute() {
        // a small grid: main road east, a side road south, and a road cut in two (like at a tile edge)
        val g = OfflineRoute.Graph()
        fun way(vararg p: Double) = Array(p.size / 2) { LatLong(p[it * 2], p[it * 2 + 1]) }
        g.addWay(way(13.0, 100.0, 13.0, 100.005, 13.0, 100.01), 15.0, "Main Road", false)
        g.addWay(way(13.0, 100.01, 12.995, 100.01), 8.0, "Side Street", false)
        g.addWay(way(12.995, 100.01, 12.9901, 100.01), 8.0, "Side Street", false)
        g.addWay(way(12.99, 100.0101, 12.985, 100.01), 8.0, "Side Street", false) // cut ~15 m away
        g.joinLooseEnds(40.0)
        val a = g.nearest(13.0, 100.0, 100.0)
        val b = g.nearest(12.985, 100.01, 100.0)
        val p = g.path(a, b)
        assertNotNull(p)
        val r = OfflineRoute.build(g, p!!, 13.0, 100.0, 12.985, 100.01)
        assertEquals("offline", r.source)
        val turn = r.maneuvers.firstOrNull { it.type == "turn" }
        assertNotNull(turn)
        assertEquals("right", turn!!.modifier)
        assertEquals("Turn right onto Side Street", Words.instruction(turn))
        assertEquals("arrive", r.maneuvers.last().type)
    }
}
