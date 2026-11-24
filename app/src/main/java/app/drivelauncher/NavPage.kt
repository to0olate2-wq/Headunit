package app.drivelauncher

import android.graphics.Canvas
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Built-in navigation: search a place, see the route, then turn-by-turn on a map that turns with the car.
 * The map is the downloaded offline map where there is one, else the online map.
 */
class NavPage(private val a: MainActivity, private val portrait: Boolean) {
    val view = FrameLayout(a)
    private val map = TileMapView(a)

    // planning panel (search, results, preview)
    private val panel = a.vbox(10f)
    private val search = EditText(a)
    private val list = a.vbox(6f)
    private val listScroll = ScrollView(a)
    private val note: TextView = a.label(13f, C.MUTED, F.regular, lines = 3)
    private val mapsBtn: TextView = small("Offline maps")

    // driving panel
    private val turnBox = a.hbox(14f)
    private val arrow = ArrowView()
    /** Text on the next-turn panel (white on the gradient themes). */
    private val onTurn = if (Skin.glowy || Skin.lux) C.ON_GRAD else C.ON_ACCENT
    private val turnDist: TextView = a.label(34f, onTurn, F.display).tnum()
    private val turnText: TextView = a.label(18f, onTurn, F.semibold, lines = 2)
    private val status: TextView = a.label(14f, C.TEXT, F.medium, lines = 2)
    private val etaBox = a.hbox(10f)
    private val eta: TextView = a.label(18f, C.TEXT, F.semibold).tnum()
    private val side = a.vbox(10f)
    private val muteBtn = IconBtn(a, Ic.SPEAKER, 22f)
    /** +, − and "back to me" while choosing where to go (drag the map to look around). */
    private val mapCtl = a.vbox(10f)
    private val recenter = IconBtn(a, Ic.LOCATE, 22f)
    private val overviewBtn = IconBtn(a, Ic.ROUTE, 22f)

    private var preview: Dest? = null
    private var previewRoute: Route? = null
    private var busy = false
    private var overview = false
    private var shownVersion = -1
    private var lastFix = 0L

    private inner class ArrowView : View(a) {
        var m: Maneuver? = null
            set(v) { field = v; invalidate() }
        override fun onDraw(c: Canvas) { m?.let { TurnArrow.draw(c, it, width / 2f, height / 2f, width * 0.9f, onTurn) } }
    }

    private fun small(t: String): TextView = a.label(14f, C.TEXT3, F.semibold).apply {
        text = t; gravity = Gravity.CENTER; background = roundBg(C.CARD2, 12f, C.BORDER2); pad(14f, 0f); isClickable = true
    }

    private fun accentBtn(t: String, click: () -> Unit): TextView = a.label(16f, C.ON_ACCENT, F.semibold).apply {
        text = t; gravity = Gravity.CENTER; background = roundBg(C.accent, 14f); pad(22f, 0f); isClickable = true
        setOnClickListener { click() }
    }

    init {
        map.background = null
        map.showParking = false
        map.pannable = true
        map.zoom = 15
        map.onPanned = { recenter.showIf(true) }
        view.addView(map, flp(MATCH, MATCH))
        fun ctl(b: IconBtn, desc: String, click: () -> Unit) {
            b.contentDescription = desc; b.circle = true; b.bgColor = C.FLOAT; b.fg = C.TEXT
            b.setOnClickListener { click() }
            mapCtl.addView(b, lp(S.i(56), S.i(56)))
        }
        ctl(recenter, "Back to my position") { map.mode = 0; map.fixed = null; recenter.showIf(false) }
        ctl(IconBtn(a, Ic.PLUS, 22f), "Zoom in") { map.zoom = map.zoom + 1 }
        ctl(IconBtn(a, Ic.MINUS, 22f), "Zoom out") { map.zoom = map.zoom - 1 }
        recenter.showIf(false)
        view.addView(mapCtl, flp(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, S.i(14), S.i(14)) })

        // ---- planning panel
        panel.background = cardBg(C.FLOAT)
        panel.pad(16f, 14f)
        val bar = a.hbox(10f)
        bar.background = roundBg(C.CARD2, 14f, C.BORDER2)
        bar.pad(12f, 0f)
        bar.addView(IconBtn(a, Ic.SEARCH, 20f).apply { isClickable = false }, lp(S.i(28), S.i(28)))
        search.hint = "Where to? Place, address or lat, lon"
        search.setHintTextColor(C.MUTED); search.setTextColor(C.TEXT)
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, S.p(17f)); search.typeface = F.medium
        search.background = null; search.setSingleLine(true); search.setPadding(0, 0, 0, 0)
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        search.imeOptions = EditorInfo.IME_ACTION_SEARCH
        search.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { runSearch(search.text.toString()); true } else false
        }
        bar.addView(search, lp(0, S.i(52), 1f))
        val go = IconBtn(a, Ic.SEND, 20f).apply { contentDescription = "Search"; setOnClickListener { runSearch(search.text.toString()) } }
        bar.addView(go, lp(S.i(40), S.i(40)))
        panel.addView(bar, lp(MATCH, S.i(56)))
        listScroll.addView(list)
        listScroll.isVerticalScrollBarEnabled = false
        panel.addView(listScroll, lp(MATCH, 0, 1f))
        val foot = a.hbox(10f)
        foot.addView(note, lp(0, WRAP, 1f))
        mapsBtn.setOnClickListener { manageMaps(a) }
        foot.addView(mapsBtn, lp(WRAP, S.i(40)))
        panel.addView(foot, lp(MATCH, WRAP))
        val pw = if (portrait) MATCH else S.i(430)
        view.addView(panel, flp(pw, if (portrait) S.i(520) else MATCH, Gravity.START or Gravity.TOP).apply { setMargins(S.i(14), S.i(14), S.i(14), S.i(14)) })

        // ---- driving: next turn at the top left
        turnBox.background = if (Skin.glowy || Skin.lux) Skin.gradient(18f) else roundBg(C.accent, 18f)
        turnBox.pad(16f, 12f)
        turnBox.addView(arrow, lp(S.i(76), S.i(76)))
        val tcol = a.vbox(2f)
        tcol.addView(turnDist, lp(WRAP, WRAP))
        tcol.addView(turnText, lp(MATCH, WRAP))
        turnBox.addView(tcol, lp(0, WRAP, 1f))
        val tw = if (portrait) MATCH else S.i(470)
        view.addView(turnBox, flp(tw, WRAP, Gravity.START or Gravity.TOP).apply { setMargins(S.i(14), S.i(14), S.i(14), 0) })
        status.background = roundBg(C.FLOAT, 12f)
        status.pad(12f, 8f)
        view.addView(status, flp(tw, WRAP, Gravity.START or Gravity.TOP).apply { setMargins(S.i(14), S.i(14 + 118), S.i(14), 0) })

        // ---- driving: arrival time at the bottom, buttons at the right
        etaBox.background = roundBg(C.FLOAT, 16f, C.BORDER)
        etaBox.pad(16f, 0f)
        etaBox.addView(eta, lp(WRAP, WRAP))
        view.addView(etaBox, flp(WRAP, S.i(56), Gravity.START or Gravity.BOTTOM).apply { setMargins(S.i(14), 0, 0, S.i(14)) })
        fun sideBtn(b: IconBtn, desc: String, click: () -> Unit) {
            b.contentDescription = desc; b.circle = true; b.bgColor = C.FLOAT; b.fg = C.TEXT
            b.setOnClickListener { click(); update() }
            side.addView(b, lp(S.i(60), S.i(60)))
        }
        sideBtn(IconBtn(a, Ic.CLOSE, 22f), "Stop navigation") { a.confirm("Stop navigation?", "") { Guide.stop(); preview = null; update() } }
        sideBtn(muteBtn, "Voice on or off") { Guide.muted = !Guide.muted; if (Guide.muted) Speaker.stop() }
        sideBtn(overviewBtn, "Whole route") { overview = !overview }
        sideBtn(IconBtn(a, Ic.PIN, 22f), "Go somewhere else") {
            a.confirm("Go somewhere else?", "This stops the current route.") { overview = false; Guide.stop(); showPlaces(); update() }
        }
        view.addView(side, flp(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, S.i(14), 0) })
        showPlaces()
        update()
    }

    // ---------------------------------------------------------------- planning

    private fun hideKeyboard() {
        try { a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0) } catch (_: Exception) {}
        search.clearFocus()
    }

    private fun header(t: String) = list.addView(a.caption(t), lp(MATCH, S.i(30)).apply { topMargin = S.i(4) })

    private fun row(ic: Ic, title: String, detail: String, click: () -> Unit) {
        val r = a.hbox(12f)
        r.background = roundBg(C.CARD2, 12f)
        r.pad(12f, 8f)
        r.isClickable = true
        r.setOnClickListener { click() }
        r.addView(IconBtn(a, ic, 20f).apply { isClickable = false; circle = true; bgColor = C.CARD; fg = C.accent }, lp(S.i(40), S.i(40)))
        val col = a.vbox(2f)
        col.addView(a.label(16f, C.TEXT, F.semibold).apply { text = title }, lp(MATCH, WRAP))
        if (detail.isNotEmpty()) col.addView(a.label(13f, C.TEXT2, F.regular).apply { text = detail }, lp(MATCH, WRAP))
        r.addView(col, lp(0, WRAP, 1f))
        list.addView(r, lp(MATCH, WRAP))
    }

    private fun away(lat: Double, lon: Double): String =
        if (Gps.lat.isNaN() || Gps.lat == 0.0) "" else Words.distance(Geo.dist(Gps.lat, Gps.lon, lat, lon)) + " away"

    /** Saved places and "nearby" searches. */
    fun showPlaces() {
        preview = null; previewRoute = null
        map.route = null; map.mark = null; map.mode = 0; map.fixed = null
        recenter.showIf(false)
        list.removeAllViews()
        header("Your places")
        for (p in Places.saved()) {
            if (!p.isSet) { row(Places.icon(p.icon), p.name, "Not set — tap to add") { Places.edit(a, p, null) }; continue }
            row(Places.icon(p.icon), p.name, if (p.hasSpot) away(p.lat, p.lon) else p.query) {
                if (p.hasSpot) choose(Dest(p.name, p.query, p.lat, p.lon)) else runSearch(p.query, pickFirst = true)
            }
        }
        header("Nearby")
        for (p in Places.NEARBY) row(Places.icon(p.icon), p.name, "Search near you") { runSearch(p.query) }
        refreshNote()
    }

    private fun refreshNote() {
        val files = OfflineMaps.files(a)
        val prog = OfflineMaps.progress(a)
        note.put(when {
            prog.isNotEmpty() -> "Downloading " + prog.joinToString("; ")
            files.isEmpty() -> "No offline map yet: the map needs internet. Download your country's map for offline use."
            !Gps.lat.isNaN() && !OfflineMaps.covers(Gps.lat, Gps.lon) -> "Your offline map doesn't cover where you are."
            else -> "Offline map: " + files.joinToString { it.name.removeSuffix(".map") }
        })
    }

    fun searchFor(q: String) { search.setText(q); runSearch(q) }

    private fun runSearch(q: String, pickFirst: Boolean = false) {
        val text = q.trim()
        if (text.isEmpty() || busy) return
        hideKeyboard()
        busy = true
        list.removeAllViews()
        header("Searching “$text”…")
        val la = Gps.lat; val lo = Gps.lon
        Thread {
            val res = try { Result.success(NavNet.search(text, la, lo)) } catch (e: Exception) { Result.failure(e) }
            a.runOnUiThread {
                busy = false
                list.removeAllViews()
                val hits = res.getOrNull()
                when {
                    hits == null -> { header("Search needs internet"); row(Ic.CLOSE, "Couldn't search", res.exceptionOrNull()?.message ?: "") { showPlaces() } }
                    hits.isEmpty() -> { header("Nothing found for “$text”"); row(Ic.BACK, "Back", "") { showPlaces() } }
                    pickFirst -> choose(hits[0])
                    else -> {
                        header("Results")
                        for (h in hits) row(Ic.PIN, h.name, listOf(away(h.lat, h.lon), h.detail).filter { it.isNotEmpty() }.joinToString(" · ")) { choose(h) }
                        row(Ic.BACK, "Back to your places", "") { showPlaces() }
                    }
                }
            }
        }.start()
    }

    /** Work out the route to [d] and show it before starting. */
    fun choose(d: Dest) {
        if (Gps.lat.isNaN() || Gps.lat == 0.0) { Toast.makeText(a, "Waiting for the GPS position…", Toast.LENGTH_SHORT).show(); return }
        if (busy) return
        busy = true
        preview = d
        list.removeAllViews()
        header("Finding the route…")
        row(Ic.PIN, d.name, d.detail) {}
        map.fixed = doubleArrayOf(d.lat, d.lon); map.mode = 2; map.zoom = 15
        recenter.showIf(true)
        val la = Gps.lat; val lo = Gps.lon; val br = if (Gps.speedKmh > 6f) Gps.bearing else null
        Thread {
            var why = ""
            val r = try { NavNet.route(la, lo, br, d.lat, d.lon) } catch (e: Exception) {
                why = e.message ?: ""
                try { OfflineRoute.route(la, lo, d.lat, d.lon) } catch (e2: Exception) { why = "No internet, and ${e2.message}"; null }
            }
            a.runOnUiThread {
                busy = false
                if (preview !== d) return@runOnUiThread
                list.removeAllViews()
                if (r == null) { header("No route"); row(Ic.CLOSE, "Couldn't find a route", why) { showPlaces() }; return@runOnUiThread }
                previewRoute = r
                map.route = r.points; map.mode = 1
                recenter.showIf(true)
                header("Route")
                row(Ic.PIN, d.name, d.detail) {}
                list.addView(a.label(26f, C.TEXT, F.display).apply { text = "${Words.duration(r.seconds)} · ${Words.distance(r.meters)}" }.tnum(), lp(MATCH, WRAP))
                list.addView(a.label(14f, C.TEXT2, F.regular, lines = 3).apply {
                    text = when (r.source) {
                        "tomtom" -> "With live traffic (TomTom)."
                        "offline" -> "Rough route from your offline map (no internet). Check the road signs."
                        else -> "No live traffic. Voice directions in English."
                    }
                }, lp(MATCH, WRAP))
                val btns = a.hbox(10f)
                btns.addView(accentBtn("Start") { Guide.start(a, d, r); overview = false; update() }, lp(0, S.i(54), 1f))
                btns.addView(small("Back").apply { setOnClickListener { showPlaces() } }, lp(S.i(110), S.i(54)))
                list.addView(btns, lp(MATCH, WRAP).apply { topMargin = S.i(8) })
            }
        }.start()
    }

    // ---------------------------------------------------------------- update

    fun update() {
        val driving = Guide.active
        panel.showIf(!driving)
        mapCtl.showIf(!driving)
        map.pannable = !driving
        turnBox.showIf(driving)
        etaBox.showIf(driving)
        side.showIf(driving)
        if (!Gps.lat.isNaN()) map.setPosition(Gps.lat, Gps.lon, Gps.bearing)
        if (!driving) {
            map.headingUp = false; map.carAt = 0.5f
            if (Gps.lastFix != lastFix) { lastFix = Gps.lastFix; if (preview == null) refreshNote() }
            status.showIf(false)
            return
        }
        val r = Guide.route ?: return
        val m = r.maneuvers.getOrNull(Guide.next)
        if (Guide.version != shownVersion) {
            shownVersion = Guide.version
            arrow.m = m
            turnDist.put(if (Guide.status == "Arrived") "Arrived" else Words.distance(Guide.toNext))
            turnText.put(if (Guide.status == "Arrived") (Guide.dest?.name ?: "") else m?.let { Words.instruction(it) } ?: "")
            eta.put(Guide.eta())
            status.put(Guide.status)
            status.showIf(Guide.status.isNotEmpty() && Guide.status != "Arrived")
            map.route = r.points
            map.mark = m?.takeIf { it.type != "arrive" && it.type != "depart" }?.let { doubleArrayOf(it.lat, it.lon) }
        }
        muteBtn.alpha = if (Guide.muted) 0.45f else 1f
        overviewBtn.fg = if (overview) C.accent else C.TEXT
        overviewBtn.invalidate()
        if (overview) { map.mode = 1; map.headingUp = false }
        else {
            map.mode = 0; map.headingUp = true; map.carAt = if (portrait) 0.7f else 0.74f
            val k = Gps.speedKmh
            map.zoom = when { k > 90 -> 15; k > 50 -> 16; else -> 17 }
        }
        map.invalidate()
    }

    companion object {
        /** Offline maps: what's downloaded, downloads in progress, and download another one. */
        fun manageMaps(a: MainActivity) {
            OfflineMaps.load(a)
            val files = OfflineMaps.files(a)
            val prog = OfflineMaps.progress(a)
            val items = ArrayList<Pair<String, () -> Unit>>()
            items.add("Download a map…" to { pickMap(a, "") })
            for (f in files) items.add("Delete ${f.name.removeSuffix(".map")} (${OfflineMaps.sizeText(f)})" to {
                a.confirm("Delete this map?", f.name) { OfflineMaps.delete(a, f); a.rebuild() }
            })
            val msg = buildString {
                append(if (files.isEmpty()) "No maps downloaded yet." else "Downloaded: " + files.joinToString { it.name.removeSuffix(".map") })
                if (prog.isNotEmpty()) append("\nDownloading: " + prog.joinToString("; "))
                append("\nMaps are from OpenStreetMap (free). Big files: use Wi-Fi.")
            }
            a.dialog().setTitle("Offline maps").setMessage(msg).setPositiveButton("Close", null)
                .setNegativeButton(items[0].first) { _, _ -> items[0].second() }
                .apply { if (items.size > 1) setNeutralButton("Delete…") { _, _ ->
                    a.dialog().setTitle("Delete a map").setItems(items.drop(1).map { it.first }.toTypedArray()) { _, i -> items[i + 1].second() }.show()
                } }
                .show()
        }

        private fun pickMap(a: MainActivity, path: String) {
            Toast.makeText(a, "Loading the list of maps…", Toast.LENGTH_SHORT).show()
            Thread {
                val res = try { Result.success(OfflineMaps.list(path)) } catch (e: Exception) { Result.failure(e) }
                a.runOnUiThread {
                    val l = res.getOrNull()
                    if (l == null || l.isEmpty()) {
                        Toast.makeText(a, "Couldn't load the list of maps (${res.exceptionOrNull()?.message ?: "empty"}). Check the internet.", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val names = l.map { if (it.isDir) it.label + "  ›" else "${it.label}  ·  ${it.size}" }
                    a.dialog().setTitle(if (path.isEmpty()) "Choose a region" else path.trimEnd('/').substringAfterLast('/').replaceFirstChar { it.uppercase() })
                        .setItems(names.toTypedArray()) { _, i ->
                            val e = l[i]
                            if (e.isDir) pickMap(a, e.path)
                            else a.confirm("Download ${e.label}?", "${e.size}. It downloads in the background (shown in the notifications) and is used as soon as it's done.") {
                                try { OfflineMaps.download(a, e); Toast.makeText(a, "Downloading ${e.label}…", Toast.LENGTH_LONG).show() }
                                catch (x: Exception) { Toast.makeText(a, "Couldn't start the download: ${x.message}", Toast.LENGTH_LONG).show() }
                            }
                        }.setNegativeButton("Cancel", null).show()
                }
            }.start()
        }
    }
}
