package app.drivelauncher

import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Data sources, settings and diagnostics — shows what this unit can and can't provide. */
class SourcesPage(private val a: MainActivity, portrait: Boolean, private val w: Int, private val h: Int) {
    val view = ScrollView(a)

    private inner class SrcCard(icon: Ic, name: String, from: String) {
        val root: LinearLayout = a.vbox(12f)
        val ic = IconBtn(a, icon, 24f).apply { isClickable = false; bgColor = C.CARD2; fg = C.TEXT3 }
        val pill: TextView = a.pill()
        val body: TextView = a.label(15f, C.TEXT2, F.regular, lines = 4).apply { setLineSpacing(0f, 1.15f) }
        val btn: TextView = button("")

        init {
            root.background = cardBg()
            root.pad(20f)
            val head = a.hbox(14f)
            head.addView(ic, lp(S.i(48), S.i(48)))
            val col = a.vbox(2f)
            col.addView(a.label(18f, C.TEXT, F.semibold).apply { text = name }, lp(MATCH, WRAP))
            col.addView(a.label(13f, C.MUTED, F.regular).apply { text = from }, lp(MATCH, WRAP))
            head.addView(col, lp(0, WRAP, 1f))
            head.addView(pill, lp(WRAP, WRAP))
            root.addView(head, lp(MATCH, WRAP))
            root.addView(body, lp(MATCH, WRAP))
            root.addView(a.spacer(), lp(MATCH, 0, 1f))
            root.addView(btn, lp(WRAP, S.i(44)))
        }

        fun set(state: String, kind: Int, text: String, button: String?) {
            pill.pillState(state, kind)
            body.put(text)
            btn.showIf(button != null)
            if (button != null) btn.put(button)
        }
    }

    private fun button(text: String): TextView = a.label(14f, C.TEXT3, F.semibold).apply {
        this.text = text
        gravity = Gravity.CENTER
        background = roundBg(C.CARD2, 12f, C.BORDER2)
        pad(16f, 0f)
        isClickable = true
        isFocusable = true
    }

    private val gps = SrcCard(Ic.LOCATE, "GPS", "Built into the head unit")
    private val music = SrcCard(Ic.MUSIC, "Music", "Phone via ZLink / Bluetooth, or any app")
    private val nav = SrcCard(Ic.TURN_RIGHT, "Navigation", "Google Maps or Waze on this unit")
    private val obd = SrcCard(Ic.PLUG, "OBD2 adapter", "Bluetooth or Wi-Fi ELM327")
    private val can = SrcCard(Ic.CAR, "Vehicle CAN", "Doors and climate")
    private val tpms = SrcCard(Ic.TIRE, "Tire pressure", "USB TPMS receiver")
    private val diag: TextView = a.label(12f, C.TEXT2, F.regular, lines = 400).apply {
        typeface = Typeface.MONOSPACE
        isSingleLine = false
        ellipsize = null
        setLineSpacing(0f, 1.2f)
        setTextIsSelectable(true)
    }
    private var diagAt = -100_000L
    private val pinBtn = button("")
    private val segs = ArrayList<Pair<List<TextView>, () -> Int>>()
    private val dyn = ArrayList<() -> Unit>()
    private var segKey = ""

    init {
        val col = a.vbox(12f)
        col.pad(0f, 0f, 0f, 12f)

        // header
        val head = if (portrait) a.vbox(12f) else a.hbox(24f)
        val titles = a.vbox(6f)
        titles.addView(a.label(32f, C.TEXT, F.bold).apply { text = "Data sources" }, lp(MATCH, WRAP))
        titles.addView(a.label(15f, C.MUTED, F.regular, lines = 2).apply {
            text = "Each widget turns on only when its data is available, so the launcher works on any head unit and car."
        }, lp(MATCH, WRAP))
        head.addView(titles, if (portrait) lp(MATCH, WRAP) else lp(0, WRAP, 1f))
        val info = a.hbox(24f)
        info.background = roundBg(C.CARD, 16f, C.BORDER)
        info.pad(20f, 14f)
        fun kv(k: String, v: String) {
            val b = a.vbox(2f)
            b.addView(a.caption(k, 11f), lp(WRAP, WRAP))
            b.addView(a.label(16f, C.TEXT, F.semibold).apply { text = v }, lp(WRAP, WRAP))
            info.addView(b, lp(WRAP, WRAP))
        }
        kv("Unit", Build.MODEL.take(18))
        kv("Android", Build.VERSION.RELEASE)
        kv("Screen", "${w}×${h}")
        kv("Version", BuildConfig.VERSION_NAME)
        head.addView(info, lp(WRAP, WRAP))
        col.addView(head, lp(MATCH, WRAP).apply { leftMargin = S.i(8); rightMargin = S.i(8); bottomMargin = S.i(4) })

        // source cards
        val cards = listOf(gps, music, nav, obd, can, tpms)
        val perRow = if (portrait) 1 else 3
        cards.chunked(perRow).forEach { chunk ->
            val row = a.hbox(12f, center = false)
            chunk.forEach { row.addView(it.root, lp(0, S.i(250), 1f)) }
            col.addView(row, lp(MATCH, WRAP))
        }
        gps.btn.setOnClickListener { a.requestPerms(true) }
        music.btn.setOnClickListener { a.openNotificationAccess() }
        nav.btn.setOnClickListener { if (!MediaHub.accessGranted) a.openNotificationAccess() else a.openNav() }
        obd.btn.setOnClickListener { a.chooseObd() }
        can.set("Not available", 2, "Doors and climate need a CAN decoder box the launcher can read. This unit reports CAN version: Unknown, so these widgets stay hidden.", null)
        tpms.set("Not available", 2, "Tire pressure needs a TPMS receiver kit. Support is planned for a later version.", null)

        // settings, in groups
        fun group(title: String): LinearLayout {
            val g = a.vbox(4f)
            g.background = cardBg()
            g.pad(20f, 12f)
            g.addView(a.caption(title), lp(MATCH, S.i(32)).apply { topMargin = S.i(6) })
            col.addView(g, lp(MATCH, WRAP))
            return g
        }
        fun row(g: LinearLayout, name: String, vararg controls: View) {
            val r = a.hbox(10f)
            r.addView(a.label(16f, C.TEXT2, F.medium, lines = 2).apply { text = name }, lp(0, WRAP, 1f))
            controls.forEach { r.addView(it, lp(WRAP, if (it is LinearLayout) WRAP else S.i(44))) }
            g.addView(r, lp(MATCH, WRAP).apply { topMargin = S.i(4); bottomMargin = S.i(4) })
        }
        fun dynButton(text: () -> String, click: () -> Unit): TextView = button(text()).also { b ->
            b.setOnClickListener { click() }
            dyn.add { b.put(text()) }
        }

        val look = group("Look")
        val themes = a.hbox(12f, center = false)
        for ((id, name) in Skin.NAMES) {
            val tile = a.vbox(6f)
            val pv = ThemePreview(a, id).apply { chosen = id == Prefs.skin }
            tile.addView(pv, lp(S.i(168), S.i(96)))
            tile.addView(a.label(14f, if (id == Prefs.skin) C.TEXT else C.TEXT2, F.semibold).apply { text = name; gravity = Gravity.CENTER }, lp(MATCH, WRAP))
            tile.isClickable = true
            tile.contentDescription = "Theme $name"
            tile.setOnClickListener { if (Prefs.skin != id) { Prefs.skin = id; a.rebuild(); a.show(MainActivity.Page.SOURCES) } }
            themes.addView(tile, lp(S.i(168), WRAP))
        }
        val themeScroll = android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(themes) }
        look.addView(a.label(16f, C.TEXT2, F.medium).apply { text = "Theme" }, lp(MATCH, WRAP).apply { topMargin = S.i(4) })
        look.addView(themeScroll, lp(MATCH, WRAP).apply { topMargin = S.i(6); bottomMargin = S.i(8) })
        val swatches = a.hbox(10f)
        for (c in C.ACCENTS) {
            val swatch = IconBtn(a, if (c == C.accent) Ic.CHECK else Ic.DASH, 20f)
            swatch.bgColor = c
            swatch.circle = true
            swatch.fg = if (c == C.accent) C.ON_ACCENT else c
            swatch.sw = 2.4f
            swatch.contentDescription = "Accent colour"
            swatch.setOnClickListener { Prefs.accent = c; C.accent = c; a.rebuild() }
            swatches.addView(swatch, lp(S.i(44), S.i(44)))
        }
        row(look, "Accent colour", swatches)
        val paints = a.hbox(8f)
        for ((key, v) in CarArt.COLORS) {
            val sw = IconBtn(a, if (key == Prefs.carColor && !Prefs.carPhoto) Ic.CHECK else Ic.DASH, 18f)
            sw.bgColor = v.second; sw.circle = true; sw.sw = 2.4f
            sw.fg = if (key == Prefs.carColor && !Prefs.carPhoto) (if (key == "white" || key == "silver") C.ON_ACCENT else 0xFFFFFFFF.toInt()) else v.second
            sw.contentDescription = "Car colour ${v.first}"
            sw.setOnClickListener { Prefs.carColor = key; Prefs.carPhoto = false; CarArt.reset(); a.rebuild(); a.show(MainActivity.Page.SOURCES) }
            paints.addView(sw, lp(S.i(40), S.i(40)))
        }
        row(look, "Car colour (home screen car)", paints)
        row(look, "Your own car photo instead (a cut-out picture looks best)",
            button("Choose photo").apply { setOnClickListener { a.pickImage(MainActivity.REQ_CAR) } },
            dynButton({ if (Prefs.carPhoto) "Use 3D car" else "—" }) {
                if (Prefs.carPhoto) { Prefs.carPhoto = false; CarArt.reset(); a.rebuild(); a.show(MainActivity.Page.SOURCES) }
            })
        row(look, "Day / night look (Auto follows sunrise and sunset)",
            segmented(listOf("Auto", "Day", "Night"), { listOf("auto", "day", "night").indexOf(Prefs.theme) }) { i ->
                Prefs.theme = listOf("auto", "day", "night")[i]; a.applyTheme()
            })
        row(look, "Automatic brightness (bright by day, dimmer at night)",
            segmented(listOf("Off", "On"), { if (Prefs.autoBright) 1 else 0 }) { i -> Prefs.autoBright = i == 1; a.applyTheme() },
            dynButton({ if (ThemeMgr.canWriteSettings(a)) "Night ${Prefs.nightBright}%" else "Allow" }) {
                if (!ThemeMgr.canWriteSettings(a)) a.openWriteSettings()
                else { Prefs.nightBright = when (Prefs.nightBright) { 20 -> 40; 40 -> 60; else -> 20 }; a.applyTheme() }
            })
        row(look, "Background",
            segmented(listOf("Plain", "Stars", "Moving", "Photo"), { listOf("plain", "stars", "moving", "photo").indexOf(Prefs.background) }) { i ->
                if (i == 3) a.pickImage(MainActivity.REQ_BG) else { Prefs.background = listOf("plain", "stars", "moving")[i]; a.rebuild() }
            })
        row(look, "App icons (install an icon pack from the Play Store first)",
            dynButton({ if (Prefs.iconPack.isEmpty()) "Apps' own icons" else Apps.label(a, Prefs.iconPack) }) {
                val packs = IconPack.installed(a)
                val names = listOf("Apps' own icons") + packs.map { it.second }
                a.dialog().setTitle(if (packs.isEmpty()) "No icon packs installed" else "Icon pack")
                    .setItems(names.toTypedArray()) { _, i ->
                        Prefs.iconPack = if (i == 0) "" else packs[i - 1].first
                        Apps.clearIcons(); a.rebuild(); a.show(MainActivity.Page.SOURCES)
                    }.setNegativeButton("Cancel", null).show()
            })
        row(look, "Your logo or photo (for the \"Your logo\" card)", button("Choose picture").apply { setOnClickListener { a.pickImage(MainActivity.REQ_LOGO) } })
        row(look, "Home screen: tap and hold any card to change it, add pages or widgets",
            button("Reset layout").apply { setOnClickListener { a.confirm("Reset the home screen?", "Back to this theme's standard pages.") { Prefs.pages = ""; a.rebuild() } } })
        row(look, "Dock: tap and hold an app button to change it",
            button("Reset dock").apply { setOnClickListener { for (i in 0..3) Prefs.ss("dock$i", ""); a.rebuild() } })
        row(look, "Motion effects (page slides, glowing background, spinning wheels)",
            segmented(listOf("Off", "On"), { if (Prefs.motion) 1 else 0 }) { i -> Prefs.motion = i == 1; a.rebuild() })
        row(look, "Units", segmented(listOf("Metric", "Imperial"), { if (Prefs.imperial) 1 else 0 }) { i -> Prefs.imperial = i == 1; a.rebuild() })
        row(look, "Full screen (hide system bars)", segmented(listOf("Off", "On"), { if (Prefs.fullscreen) 1 else 0 }) { i -> Prefs.fullscreen = i == 1; a.applyFullscreen() })
        row(look, "Screen saver when parked", segmented(listOf("Off", "2 min", "5 min", "10 min"), { listOf(0, 2, 5, 10).indexOf(Prefs.saverMin) }) { i ->
            Prefs.saverMin = listOf(0, 2, 5, 10)[i]
        })

        val drive = group("Driving")
        row(drive, "Speed warning chime", dynButton({ if (Prefs.speedLimit > 0) "${Prefs.speedLimit} ${Units.speedUnitLower}" else "Off" }) {
            val opts = if (Prefs.imperial) listOf(0, 30, 40, 45, 50, 55, 60, 65, 70, 75, 80) else listOf(0, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)
            a.dialog().setTitle("Beep when faster than").setItems(opts.map { if (it == 0) "Off" else "$it ${Units.speedUnitLower}" }.toTypedArray()) { _, i ->
                Prefs.speedLimit = opts[i]
                if (opts[i] > 0) SpeedAlert.beep()
            }.setNegativeButton("Cancel", null).show()
        })
        row(drive, "Road speed limit signs (from OpenStreetMap; main roads are best covered)",
            segmented(listOf("Off", "On"), { if (Prefs.roadLimits) 1 else 0 }) { i -> Prefs.roadLimits = i == 1 })
        row(drive, "Beep when faster than the road's limit",
            segmented(listOf("Off", "At limit", "+5", "+10"), { listOf(-1, 0, 5, 10).indexOf(Prefs.roadWarn) }) { i ->
                Prefs.roadWarn = listOf(-1, 0, 5, 10)[i]
            })
        row(drive, "Music a little louder at higher speed",
            segmented(listOf("Off", "Low", "Medium", "High"), { Prefs.speedVolume }) { i -> Prefs.speedVolume = i })
        row(drive, "Navigation app for quick destinations",
            segmented(listOf("Auto", "Google Maps", "Waze"), { listOf("auto", "maps", "waze").indexOf(Prefs.navApp) }) { i ->
                Prefs.navApp = listOf("auto", "maps", "waze")[i]
            })
        row(drive, "Saved places (Home, Work and your own)", button("Edit places").apply { setOnClickListener { Places.manage(a) } })
        row(drive, "Floating speedometer on top of ZLink and Maps",
            segmented(listOf("Off", "On"), { if (Prefs.bubble) 1 else 0 }) { i -> Prefs.bubble = i == 1; if (i == 1 && !Overlay.allowed(a)) a.openOverlaySettings() },
            dynButton({ if (Overlay.allowed(a)) "Allowed" else "Allow" }) { a.openOverlaySettings() })
        row(drive, "When the car turns on, open", dynButton({
            when (val v = Prefs.autoApp) { "off", "" -> "Nothing"; "zlink" -> "ZLink" + (if (Apps.findZlink(a) == null) " (not found)" else ""); else -> Apps.label(a, v) }
        }) {
            a.chooseApp("When the car turns on, open", listOf("Nothing" to "off", "ZLink (find automatically)" to "zlink")) { Prefs.autoApp = it }
        })
        row(drive, "…and resume music", segmented(listOf("Off", "On"), { if (Prefs.autoPlay) 1 else 0 }) { i -> Prefs.autoPlay = i == 1 })
        row(drive, "…then come back to this screen", segmented(listOf("No", "After 20 s", "After 45 s"), { listOf(0, 20, 45).indexOf(Prefs.autoReturn) }) { i ->
            Prefs.autoReturn = listOf(0, 20, 45)[i]
        })
        row(drive, "Waking from sleep and opening apps from the background need \"Display over other apps\"",
            dynButton({ if (Overlay.allowed(a)) "Allowed" else "Allow" }) { a.openOverlaySettings() })
        row(drive, "Split screen app (next to the dashboard)", dynButton({ a.splitTarget()?.let { Apps.label(a, it) } ?: "None" }) {
            a.chooseApp("Split screen app", listOf("Automatic (Maps)" to "")) { Prefs.splitApp = it }
        })
        pinBtn.setOnClickListener { val l = MediaHub.cycleSource(a); pinBtn.put("Source: $l") }
        row(drive, "Music source (Auto picks whatever is playing)", pinBtn)
        row(drive, "Trip computer", button("Reset trip").apply { setOnClickListener { a.confirm("Reset trip?", "Distance, time and speeds go back to zero.") { Gps.resetTrip() } } })
        row(drive, "Odometer (for trips, fuel and service)", dynButton({ fmt("%,.0f km", Odometer.km) }) { a.showCarTab(2) })
        row(drive, "Currency for fuel costs", dynButton({ Prefs.currency }) {
            a.form("Currency symbol", listOf(Field("Symbol, e.g. ฿, $, AED", Prefs.currency, false)), null) { v, _ -> if (v[0].isNotEmpty()) Prefs.currency = v[0] }
        })

        val sys = group("Connections")
        row(sys, "OBD2 adapter", button("Choose adapter").apply { setOnClickListener { a.chooseObd() } })
        row(sys, "Music & navigation info", button("Notification access").apply { setOnClickListener { a.openNotificationAccess() } })
        row(sys, "Use as home screen", button("Default launcher").apply { setOnClickListener { a.openHomeSettings() } })

        // diagnostics
        val d = a.vbox(10f)
        d.background = cardBg()
        d.pad(20f)
        val dh = a.hbox(12f)
        dh.addView(a.caption("Diagnostics"), lp(0, WRAP, 1f))
        dh.addView(button("Save log to Downloads").apply {
            setOnClickListener {
                val msg = try { "Saved: " + Diag.save(a, diagText()) } catch (e: Exception) { "Couldn't save: ${e.message}" }
                Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
            }
        }, lp(WRAP, S.i(44)))
        d.addView(dh, lp(MATCH, WRAP))
        d.addView(a.label(14f, C.MUTED, F.regular, lines = 3).apply {
            text = "If music, navigation or OBD don't show up, play a song through ZLink, then take a photo of this box — it shows exactly what the unit shares with the launcher."
        }, lp(MATCH, WRAP))
        diag.background = roundBg(C.BG, 12f, C.BORDER)
        diag.pad(14f)
        d.addView(diag, lp(MATCH, WRAP))
        col.addView(d, lp(MATCH, WRAP))

        view.isFillViewport = true
        view.isVerticalScrollBarEnabled = true
        view.addView(col, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        refreshSegments()
    }

    /** Row of option buttons; the chosen one is highlighted. */
    private fun segmented(names: List<String>, current: () -> Int, onPick: (Int) -> Unit): View {
        val box = a.hbox(6f)
        val list = ArrayList<TextView>()
        names.forEachIndexed { i, n ->
            val b = button(n)
            b.setOnClickListener { onPick(i); refreshSegments() }
            list.add(b)
            box.addView(b, lp(WRAP, S.i(44)))
        }
        segs.add(list to current)
        return box
    }

    private fun refreshSegments() {
        val key = segs.joinToString(",") { it.second().toString() }
        if (key == segKey) return
        segKey = key
        for ((list, cur) in segs) {
            val sel = cur()
            list.forEachIndexed { i, b ->
                b.background = roundBg(if (i == sel) C.accent else C.CARD2, 12f, if (i == sel) 0 else C.BORDER2)
                b.setTextColor(if (i == sel) C.ON_ACCENT else C.TEXT3)
            }
        }
    }

    private fun diagText(): String = MediaHub.describe(a) + "\n" + Obd.describe() + "\nGPS: " +
        (if (!Gps.permission) "no permission" else if (Gps.fresh) fmt("fix ±%.0f m, %d/%d satellites", Gps.accuracy, Gps.satsUsed, Gps.satsSeen) else "no fix yet (${Gps.satsSeen} satellites in view)") +
        "\nRoad limit: " + (RoadLimit.signNumber()?.let { "$it${if (RoadLimit.postedMph) " mph" else ""} ${RoadLimit.road}" } ?: "unknown") + " (" + RoadLimit.status + ")" +
        "\nNavigation: " + (if (NavHub.active) "${NavHub.pkg}: ${NavHub.title} | ${NavHub.text} | ${NavHub.sub}" else "no active route") +
        "\nZLink app: " + (Apps.findZlink(a) ?: "not found") +
        "\nDisplay over other apps: " + (if (Overlay.allowed(a)) "allowed" else "not allowed") +
        ", modify system settings: " + (if (ThemeMgr.canWriteSettings(a)) "allowed" else "not allowed") +
        "\nAuto-start: " + Prefs.autoApp + " → " + (AutoStart.target(a) ?: "nothing") + ", resume music " + Prefs.autoPlay +
        "\nTrips saved: ${Trips.list().size}, odometer ${fmt("%.1f", Odometer.km)} km, parking ${if (Parking.has) "saved" else "none"}" +
        "\nTheme: ${Skin.NAMES[Skin.id]}, ${Prefs.theme} (now ${if (C.night) "night" else "day"}), background ${Prefs.background}"

    fun update() {
        // GPS
        when {
            !Gps.permission -> gps.set("Permission needed", 1, "Allow location so the launcher can show speed, trip and map.", "Allow location")
            Gps.fresh -> gps.set("Active", 0, fmt("Speed, trip, heading and map. Accuracy ±%.0f m · %d satellites.", Gps.accuracy, Gps.satsUsed), null)
            else -> gps.set("Waiting for fix", 1, "No satellite fix yet. Check the GPS antenna is connected and has a view of the sky.", null)
        }
        // Music
        val t = MediaHub.current
        when {
            !MediaHub.accessGranted -> music.set("Needs access", 1, "Allow notification access so songs from ZLink, Bluetooth, Anghami or Spotify can be shown.", "Allow access")
            t != null -> music.set("Active", 0, "Now: ${t.title}${if (t.artist.isNotEmpty()) " — " + t.artist else ""}\nFrom ${MediaHub.sourceName(a, t)} (${t.via}).", "Notification access")
            else -> music.set("Idle", 2, "Nothing playing right now. Start music on your phone through ZLink or Bluetooth and it appears here.", "Notification access")
        }
        // Navigation
        when {
            !MediaHub.accessGranted -> nav.set("Needs access", 1, "Uses the same notification access as Music.", "Allow access")
            NavHub.active -> nav.set("Route active", 0, "${NavHub.title} ${NavHub.text}".trim(), "Open maps")
            else -> nav.set("No route", 2, "Start navigation in Google Maps or Waze on this unit to show turns. Navigation inside ZLink stays on the ZLink screen.", "Open maps")
        }
        // OBD
        when (Obd.state) {
            Obd.State.CONNECTED -> obd.set("Connected", 0, "${Obd.adapterName}: ${Obd.values.size} live readings from the car.", "Choose adapter")
            Obd.State.CONNECTING -> obd.set("Connecting", 1, Obd.detail, "Choose adapter")
            Obd.State.NO_ECU -> obd.set("Car silent", 1, Obd.detail, "Choose adapter")
            Obd.State.NO_PERMISSION -> obd.set("Permission needed", 1, "Allow Bluetooth so the launcher can talk to the adapter.", "Allow Bluetooth")
            Obd.State.OFF -> obd.set("Off", 2, Obd.detail, "Choose adapter")
            else -> obd.set("Not connected", 2, Obd.detail, "Choose adapter")
        }
        pinBtn.put("Source: " + MediaHub.pinLabel(a))
        refreshSegments()
        dyn.forEach { it() }
        val now = SystemClock.elapsedRealtime()
        if (now - diagAt > 1500) {
            diagAt = now
            val sb = StringBuilder(diagText())
            sb.append("\n\nRecent notifications:\n")
            for (n in Diag.recentNotes(10)) sb.append("• ").append(n.pkg).append(" | ").append(n.title).append(" | ").append(n.text.take(60)).append(" | ").append(n.flags).append('\n')
            sb.append("\nLog:\n")
            for (l in Diag.recentLines(14)) sb.append(l).append('\n')
            diag.put(sb.toString().trimEnd())
        }
    }
}
