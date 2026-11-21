package app.drivelauncher

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.labels.TileBasedLabelStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Offline maps: country files from download.mapsforge.org (OpenStreetMap data, free), kept in the app's own storage
 * and drawn on the unit without internet (mapsforge library, LGPL-3.0).
 */
object OfflineMaps {
    const val BASE = "https://download.mapsforge.org/maps/v5/"

    class Entry(val name: String, val path: String, val size: String, val isDir: Boolean) {
        /** "thailand.map" → "Thailand", "us-west/" → "Us West". */
        val label: String get() = name.removeSuffix("/").removeSuffix(".map").split('-', '_')
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private var app: Context? = null
    @Volatile private var store: MapDataStore? = null
    @Volatile private var renderer: DatabaseRenderer? = null
    @Volatile private var theme: RenderThemeFuture? = null
    private var display: DisplayModel? = null
    @Volatile var version = 0
        private set
    private var loadedKey = ""

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "maps").apply { mkdirs() }

    /** Downloaded (finished) map files. */
    fun files(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.name.endsWith(".map") }?.sortedBy { it.name } ?: emptyList()

    fun has(ctx: Context) = files(ctx).isNotEmpty()

    /** (Re)open the map files when they changed. Cheap when nothing changed. */
    @Synchronized
    fun load(ctx: Context) {
        app = ctx.applicationContext
        finishDownloads(ctx)
        val fs = files(ctx)
        val key = fs.joinToString("|") { "${it.name}:${it.length()}" }
        if (key == loadedKey) return
        loadedKey = key
        close()
        if (fs.isEmpty()) { version++; return }
        try {
            AndroidGraphicFactory.createInstance(ctx.applicationContext as android.app.Application)
            val multi = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
            for (f in fs) try { multi.addMapDataStore(MapFile(f, "en"), false, false) } catch (e: Exception) { Diag.log("map ${f.name}: ${e.message}") }
            val dm = DisplayModel()
            val rt = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, MapsforgeThemes.DEFAULT, dm)
            Thread(rt).start()
            store = multi; display = dm; theme = rt
            renderer = DatabaseRenderer(multi, AndroidGraphicFactory.INSTANCE, InMemoryTileCache(32), TileBasedLabelStore(256), true, true, null)
            Diag.log("offline maps: ${fs.joinToString { it.name }}")
        } catch (e: Throwable) {
            Diag.log("offline maps: ${e.javaClass.simpleName} ${e.message}")
            close()
        }
        version++
    }

    private fun close() {
        try { store?.close() } catch (_: Exception) {}
        store = null; renderer = null; theme = null
    }

    /** Is (lat, lon) inside a downloaded map? */
    fun covers(lat: Double, lon: Double): Boolean {
        val s = store ?: return false
        return try { s.boundingBox()?.contains(lat, lon) == true } catch (_: Exception) { false }
    }

    /** Draw one 256-px map tile; null if no offline map covers it. */
    @Synchronized
    fun render(z: Int, x: Int, y: Int): Bitmap? {
        val r = renderer ?: return null
        val s = store ?: return null
        val t = theme ?: return null
        val dm = display ?: return null
        return try {
            val tile = Tile(x, y, z.toByte(), dm.tileSize)
            if (!s.supportsTile(tile)) return null
            val tb = r.executeJob(RendererJob(tile, s, t, dm, 1f, false, false)) ?: return null
            val bmp = AndroidGraphicFactory.getBitmap(tb)
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            tb.decrementRefCount()
            copy
        } catch (e: Throwable) {
            Diag.log("offline tile $z/$x/$y: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** Ways in a tile, for the rough offline route. */
    fun ways(z: Int, x: Int, y: Int): List<org.mapsforge.map.datastore.Way> {
        val s = store ?: return emptyList()
        return try {
            val tile = Tile(x, y, z.toByte(), 256)
            if (!s.supportsTile(tile)) emptyList() else s.readMapData(tile)?.ways ?: emptyList()
        } catch (e: Exception) { Diag.log("offline ways: ${e.message}"); emptyList() }
    }

    // ---- catalogue and downloads

    fun list(path: String): List<Entry> {
        val con = URL(BASE + path).openConnection() as HttpURLConnection
        con.connectTimeout = 12_000; con.readTimeout = 15_000
        con.setRequestProperty("User-Agent", TileMapView.UA)
        val html = try { con.inputStream.bufferedReader().use { it.readText() } } finally { con.disconnect() }
        return parseListing(html, path)
    }

    /** Folder listing of the download server (Apache or nginx style). */
    fun parseListing(html: String, path: String): List<Entry> {
        val out = ArrayList<Entry>()
        val row = Regex("""<a href="([^"?#][^"]*)">[^<]*</a>(.*)""")
        for (line in html.split('\n', '\r').flatMap { it.split("<tr>") }) {
            val m = row.find(line) ?: continue
            val href = m.groupValues[1]
            if (href.startsWith("/") || href.startsWith("..") || href.startsWith("http")) continue
            val isDir = href.endsWith("/")
            if (!isDir && !href.endsWith(".map")) continue
            val rest = m.groupValues[2].replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ")
            val size = rest.split(Regex("\\s+")).lastOrNull { Regex("""\d+(\.\d+)?[KMG]?""").matches(it) } ?: ""
            out.add(Entry(Uri.decode(href), path + href, if (isDir) "" else pretty(size), isDir))
        }
        return out.distinctBy { it.path }
    }

    private fun pretty(s: String): String {
        val n = s.toDoubleOrNull()
        return when {
            s.isEmpty() || s == "-" -> ""
            n != null -> fmt("%.0f MB", n / 1_048_576)
            else -> s.replace("M", " MB").replace("G", " GB").replace("K", " KB")
        }
    }

    /** Start downloading a map with Android's download manager (resumes by itself, shows progress). */
    fun download(ctx: Context, e: Entry): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: throw java.io.IOException("No download manager on this unit")
        val tmp = e.name + ".download"
        File(dir(ctx), tmp).delete()
        val req = DownloadManager.Request(Uri.parse(BASE + e.path))
            .setTitle("Map: ${e.label}")
            .setDescription("Drive Launcher offline map")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(File(dir(ctx), tmp)))
            .addRequestHeader("User-Agent", TileMapView.UA)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(req)
        val a = pending(); a.put(JSONObject().put("id", id).put("name", e.name).put("label", e.label))
        Prefs.ss("mapDownloads", a.toString())
        Diag.log("map download ${e.path} (id $id)")
        return id
    }

    private fun pending(): JSONArray = try { JSONArray(Prefs.gs("mapDownloads", "[]")) } catch (_: Exception) { JSONArray() }

    /** "Thailand 42%" for each running download. */
    fun progress(ctx: Context): List<String> {
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return emptyList()
        val out = ArrayList<String>()
        val a = pending()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            try {
                dm.query(DownloadManager.Query().setFilterById(o.getLong("id")))?.use { c ->
                    if (c.moveToFirst()) {
                        val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val what = when (st) {
                            DownloadManager.STATUS_PAUSED -> "paused (waiting for internet)"
                            DownloadManager.STATUS_PENDING -> "waiting…"
                            DownloadManager.STATUS_FAILED -> "failed"
                            DownloadManager.STATUS_SUCCESSFUL -> "done"
                            else -> if (tot > 0) "${got * 100 / tot}% of ${tot / 1_048_576} MB" else "${got / 1_048_576} MB"
                        }
                        out.add("${o.optString("label")}: $what")
                    }
                }
            } catch (_: Exception) {}
        }
        return out
    }

    /** Finished downloads: rename "x.map.download" to "x.map" so they get used. */
    fun finishDownloads(ctx: Context) {
        val a = pending()
        if (a.length() == 0) return
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            var st = -1
            try {
                dm.query(DownloadManager.Query().setFilterById(o.getLong("id")))?.use { c ->
                    if (c.moveToFirst()) st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
            } catch (_: Exception) {}
            val name = o.getString("name")
            when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val tmp = File(dir(ctx), "$name.download")
                    if (tmp.exists()) { File(dir(ctx), name).delete(); tmp.renameTo(File(dir(ctx), name)) }
                    Diag.log("map download finished: $name")
                }
                DownloadManager.STATUS_FAILED, -1 -> { File(dir(ctx), "$name.download").delete(); Diag.log("map download failed: $name") }
                else -> keep.put(o)
            }
        }
        Prefs.ss("mapDownloads", keep.toString())
    }

    fun delete(ctx: Context, f: File) {
        synchronized(this) { close(); loadedKey = "" }
        f.delete()
        load(ctx)
    }

    fun sizeText(f: File) = fmt("%.0f MB", f.length() / 1_048_576.0)
}

// ------------------------------------------------------------------ rough offline route

/**
 * A rough route from the roads in the downloaded map, for when there's no internet. Map files are made for drawing,
 * not routing: roads are cut at tile edges and one-way streets may not be marked, so the route can be imperfect.
 */
object OfflineRoute {
    private fun speed(h: String): Double? = when (h) {
        "motorway" -> 27.0; "trunk" -> 22.0; "primary" -> 18.0; "secondary" -> 15.0; "tertiary" -> 12.0
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link" -> 11.0
        "unclassified", "road" -> 10.0; "residential" -> 8.0; "living_street" -> 4.0; "service" -> 5.0; "track" -> 4.0
        else -> null
    }

    class Edge(val to: Int, val len: Double, val cost: Double, val name: String)

    /** Road network: nodes are points, joined when they are the same or very close. */
    class Graph {
        val lat = ArrayList<Double>(); val lon = ArrayList<Double>()
        val edges = ArrayList<ArrayList<Edge>>()
        private val index = HashMap<Long, Int>()
        private val grid = HashMap<Long, ArrayList<Int>>()

        private fun cell(la: Double, lo: Double) = (floor(la / 0.0005).toLong() shl 32) xor (floor(lo / 0.0005).toLong() and 0xffffffffL)

        fun node(la: Double, lo: Double): Int {
            val key = (Math.round(la * 1e6) shl 32) xor (Math.round(lo * 1e6) and 0xffffffffL)
            index[key]?.let { return it }
            val i = lat.size
            lat.add(la); lon.add(lo); edges.add(ArrayList())
            index[key] = i
            grid.getOrPut(cell(la, lo)) { ArrayList() }.add(i)
            return i
        }

        fun link(a: Int, b: Int, spd: Double, name: String, oneway: Boolean) {
            if (a == b) return
            val d = Geo.dist(lat[a], lon[a], lat[b], lon[b])
            edges[a].add(Edge(b, d, d / spd, name))
            if (!oneway) edges[b].add(Edge(a, d, d / spd, name))
        }

        fun nearest(la: Double, lo: Double, maxM: Double, skip: Int = -1): Int {
            var best = -1; var bd = maxM
            val c0 = floor(la / 0.0005).toLong(); val c1 = floor(lo / 0.0005).toLong()
            val r = max(1, (maxM / 50).toInt() + 1)
            for (dy in -r..r) for (dx in -r..r) {
                val list = grid[((c0 + dy) shl 32) xor ((c1 + dx) and 0xffffffffL)] ?: continue
                for (i in list) {
                    if (i == skip || edges[i].isEmpty() && skip >= 0) continue
                    val d = Geo.dist(la, lo, lat[i], lon[i])
                    if (d < bd) { bd = d; best = i }
                }
            }
            return best
        }

        /** Repair roads cut at tile edges: join loose ends to the closest other road point. */
        fun joinLooseEnds(maxM: Double) {
            val deg = IntArray(lat.size)
            for (i in edges.indices) for (e in edges[i]) { deg[i]++; deg[e.to]++ }
            for (i in 0 until lat.size) if (deg[i] in 1..2) {
                val j = nearest(lat[i], lon[i], maxM, i)
                if (j >= 0 && edges[i].none { it.to == j }) link(i, j, 8.0, "", false)
            }
        }

        fun addWay(pts: Array<org.mapsforge.core.model.LatLong>, spd: Double, name: String, oneway: Boolean) {
            var prev = -1
            for (p in pts) {
                val n = node(p.latitude, p.longitude)
                if (prev >= 0) link(prev, n, spd, name, oneway)
                prev = n
            }
        }

        /** Fastest path (A*). */
        fun path(from: Int, to: Int): List<Int>? {
            val g = DoubleArray(lat.size) { Double.MAX_VALUE }
            val came = IntArray(lat.size) { -1 }
            val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
            g[from] = 0.0
            open.add(doubleArrayOf(h(from, to), from.toDouble()))
            var steps = 0
            while (open.isNotEmpty() && steps++ < 2_000_000) {
                val cur = open.poll()!![1].toInt()
                if (cur == to) break
                for (e in edges[cur]) {
                    val ng = g[cur] + e.cost
                    if (ng < g[e.to]) { g[e.to] = ng; came[e.to] = cur; open.add(doubleArrayOf(ng + h(e.to, to), e.to.toDouble())) }
                }
            }
            if (g[to] == Double.MAX_VALUE) return null
            val out = ArrayList<Int>()
            var c = to
            while (c != -1) { out.add(c); c = came[c] }
            return out.reversed()
        }

        private fun h(a: Int, b: Int) = Geo.dist(lat[a], lon[a], lat[b], lon[b]) / 30.0

        fun nameBetween(a: Int, b: Int) = edges[a].firstOrNull { it.to == b }?.name ?: ""
    }

    private fun tileX(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()
    private fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
    }

    private fun addArea(g: Graph, z: Int, s: Double, w: Double, n: Double, e: Double, maxTiles: Int) {
        val x0 = tileX(w, z); val x1 = tileX(e, z); val y0 = tileY(n, z); val y1 = tileY(s, z)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > maxTiles) throw java.io.IOException("too far for an offline route")
        val seen = HashSet<Int>()
        for (y in y0..y1) for (x in x0..x1) for (wy in OfflineMaps.ways(z, x, y)) {
            val tags = wy.tags.associate { it.key to it.value }
            val spd = speed(tags["highway"] ?: continue) ?: continue
            if (!seen.add(wy.hashCode())) continue
            val one = tags["oneway"] == "yes" || tags["junction"] == "roundabout" || (tags["highway"] ?: "").startsWith("motorway")
            for (line in wy.latLongs) g.addWay(line, spd, tags["name"] ?: tags["ref"] ?: "", one)
        }
    }

    fun route(fLa: Double, fLo: Double, tLa: Double, tLo: Double): Route {
        if (!OfflineMaps.covers(fLa, fLo) || !OfflineMaps.covers(tLa, tLo)) throw java.io.IOException("the offline map doesn't cover this trip")
        val d = Geo.dist(fLa, fLo, tLa, tLo)
        val g = Graph()
        fun box(la: Double, lo: Double, m: Double): DoubleArray {
            val dLa = m / 111_000.0; val dLo = m / (111_000.0 * max(0.2, cos(Math.toRadians(la))))
            return doubleArrayOf(la - dLa, lo - dLo, la + dLa, lo + dLo)
        }
        if (d < 25_000) {
            val m = d * 0.3 + 2000
            val s = min(fLa, tLa) - m / 111_000; val n = max(fLa, tLa) + m / 111_000
            val k = 111_000.0 * max(0.2, cos(Math.toRadians(fLa)))
            addArea(g, 14, s, min(fLo, tLo) - m / k, n, max(fLo, tLo) + m / k, 700)
        } else {
            // local streets near both ends, main roads in between
            for (b in listOf(box(fLa, fLo, 3000.0), box(tLa, tLo, 3000.0))) addArea(g, 14, b[0], b[1], b[2], b[3], 120)
            val m = d * 0.2 + 10_000
            val k = 111_000.0 * max(0.2, cos(Math.toRadians(fLa)))
            addArea(g, 10, min(fLa, tLa) - m / 111_000, min(fLo, tLo) - m / k, max(fLa, tLa) + m / 111_000, max(fLo, tLo) + m / k, 400)
        }
        g.joinLooseEnds(40.0)
        val a = g.nearest(fLa, fLo, 600.0); val b = g.nearest(tLa, tLo, 600.0)
        if (a < 0 || b < 0) throw java.io.IOException("no road near the start or the destination")
        val p = g.path(a, b) ?: throw java.io.IOException("no road connection found in the offline map")
        return build(g, p, fLa, fLo, tLa, tLo)
    }

    /** Points, distance, time and turn instructions from a path. */
    fun build(g: Graph, p: List<Int>, fLa: Double, fLo: Double, tLa: Double, tLo: Double): Route {
        val pts = ArrayList<DoubleArray>()
        pts.add(doubleArrayOf(fLa, fLo))
        for (i in p) pts.add(doubleArrayOf(g.lat[i], g.lon[i]))
        pts.add(doubleArrayOf(tLa, tLo))
        var secs = 0.0; var len = 0.0
        for (k in 0 until p.size - 1) g.edges[p[k]].firstOrNull { it.to == p[k + 1] }?.let { secs += it.cost; len += it.len }
        val mans = ArrayList<Maneuver>()
        mans.add(Maneuver(fLa, fLo, "depart", "", 0, if (p.size > 1) g.nameBetween(p[0], p[1]) else ""))
        var lastAt = -1
        for (k in 1 until p.size - 1) {
            val i = p[k]
            if (g.edges[i].size < 3 && g.nameBetween(p[k - 1], i) == g.nameBetween(i, p[k + 1])) continue
            // directions ~25 m before and after the point
            var a = k - 1
            while (a > 0 && Geo.dist(g.lat[p[a]], g.lon[p[a]], g.lat[i], g.lon[i]) < 25) a--
            var b = k + 1
            while (b < p.size - 1 && Geo.dist(g.lat[p[b]], g.lon[p[b]], g.lat[i], g.lon[i]) < 25) b++
            val tin = Geo.bearing(g.lat[p[a]], g.lon[p[a]], g.lat[i], g.lon[i])
            val tout = Geo.bearing(g.lat[i], g.lon[i], g.lat[p[b]], g.lon[p[b]])
            val t = Geo.turn(tin, tout)
            val nameOut = g.nameBetween(i, p[k + 1])
            val nameIn = g.nameBetween(p[k - 1], i)
            // a blank name is a joined gap, not a new road
            val changed = nameOut.isNotBlank() && nameIn.isNotBlank() && nameOut != nameIn
            if (abs(t) < 30 && !changed) continue
            if (lastAt >= 0 && Geo.dist(g.lat[p[lastAt]], g.lon[p[lastAt]], g.lat[i], g.lon[i]) < 30) continue
            val side = if (t < 0) "left" else "right"
            val mod = when {
                abs(t) < 30 -> "straight"
                abs(t) < 55 -> "slight $side"
                abs(t) < 130 -> side
                abs(t) < 165 -> "sharp $side"
                else -> "uturn"
            }
            mans.add(Maneuver(g.lat[i], g.lon[i], if (mod == "straight") "continue" else "turn", mod, 0, nameOut))
            lastAt = k
        }
        mans.add(Maneuver(tLa, tLo, "arrive", "", 0, ""))
        return Route(pts, mans, len, secs * 1.15, "offline")
    }
}
