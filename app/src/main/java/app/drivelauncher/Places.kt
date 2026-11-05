package app.drivelauncher

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** A saved destination: an address or search text, or a spot on the map (lat/lon). */
class Place(val name: String, val query: String, val lat: Double, val lon: Double, val icon: String) {
    val hasSpot: Boolean get() = !lat.isNaN() && !lon.isNaN()
    val isSet: Boolean get() = hasSpot || query.isNotBlank()
    fun toJson(): JSONObject = JSONObject().put("name", name).put("query", query).put("icon", icon)
        .put("lat", if (lat.isNaN()) "" else lat.toString()).put("lon", if (lon.isNaN()) "" else lon.toString())

    companion object {
        fun from(o: JSONObject) = Place(o.optString("name"), o.optString("query"),
            o.optString("lat").toDoubleOrNull() ?: Double.NaN, o.optString("lon").toDoubleOrNull() ?: Double.NaN, o.optString("icon", "star"))
    }
}

/** Quick destinations: Home, Work, your saved places, and "nearest fuel / coffee / food / parking". */
object Places {
    val NEARBY = listOf(
        Place("Fuel", "gas station", Double.NaN, Double.NaN, "fuel"),
        Place("Coffee", "coffee", Double.NaN, Double.NaN, "coffee"),
        Place("Food", "restaurant", Double.NaN, Double.NaN, "food"),
        Place("Parking", "parking", Double.NaN, Double.NaN, "parking"),
    )

    fun icon(name: String): Ic = when (name) {
        "home" -> Ic.HOME; "work" -> Ic.WORK; "fuel" -> Ic.FUEL; "coffee" -> Ic.COFFEE
        "food" -> Ic.FOOD; "parking" -> Ic.PARK; else -> Ic.STAR
    }

    /** Home and Work always come first (possibly not set yet), then the owner's own places. */
    fun saved(): MutableList<Place> {
        val out = ArrayList<Place>()
        try {
            val a = JSONArray(Prefs.places)
            for (i in 0 until a.length()) out.add(Place.from(a.getJSONObject(i)))
        } catch (_: Exception) {}
        if (out.none { it.icon == "home" }) out.add(0, Place("Home", "", Double.NaN, Double.NaN, "home"))
        if (out.none { it.icon == "work" }) out.add(1, Place("Work", "", Double.NaN, Double.NaN, "work"))
        return out
    }

    fun save(list: List<Place>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        Prefs.places = a.toString()
    }

    /** Which navigation app to use: the owner's choice, else Google Maps, else Waze, else any maps app. */
    private fun navPkg(a: MainActivity): String? = when (Prefs.navApp) {
        "maps" -> "com.google.android.apps.maps".takeIf { Apps.canLaunch(a, it) }
        "waze" -> "com.waze".takeIf { Apps.canLaunch(a, it) }
        else -> null
    } ?: listOf("com.google.android.apps.maps", "com.waze").firstOrNull { Apps.canLaunch(a, it) }

    private fun start(a: MainActivity, uri: String, pkg: String?): Boolean = try {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        a.startActivity(i); true
    } catch (e: Exception) { Diag.log("places: ${e.message}"); false }

    /** Start navigation to a saved place, or search nearby for "fuel", "coffee"… */
    fun go(a: MainActivity, p: Place) {
        if (!p.isSet) { edit(a, p, null); return }
        val pkg = navPkg(a)
        val q = Uri.encode(p.query)
        val nearby = NEARBY.any { it.icon == p.icon } && !p.hasSpot
        val ok = when {
            pkg == "com.waze" && p.hasSpot -> start(a, "https://waze.com/ul?ll=${p.lat},${p.lon}&navigate=yes", pkg)
            pkg == "com.waze" && nearby -> start(a, "https://waze.com/ul?q=$q", pkg)
            pkg == "com.waze" -> start(a, "https://waze.com/ul?q=$q&navigate=yes", pkg)
            nearby && Gps.lat != 0.0 -> start(a, "geo:${Gps.lat},${Gps.lon}?q=$q", pkg)
            p.hasSpot -> start(a, "google.navigation:q=${p.lat},${p.lon}", pkg)
            else -> start(a, "google.navigation:q=$q", pkg)
        } || start(a, if (p.hasSpot) "geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}" else "geo:0,0?q=$q", null)
        if (!ok) Toast.makeText(a, "No maps app installed", Toast.LENGTH_SHORT).show()
    }

    /** Add or change a place. [index] = position in the saved list, null for a new one. */
    fun edit(a: MainActivity, p: Place?, index: Int?) {
        val list = saved()
        val idx = index ?: p?.let { pl -> list.indexOfFirst { it.icon == pl.icon && it.name == pl.name } }?.takeIf { it >= 0 }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        fun field(label: String, value: String, enabled: Boolean = true): EditText {
            box.addView(TextView(a).apply { text = label; setPadding(0, 16, 0, 0) })
            return EditText(a).apply {
                setText(value); setSingleLine(true); isEnabled = enabled
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                box.addView(this)
            }
        }
        val fixed = p?.icon == "home" || p?.icon == "work"
        val name = field("Name", p?.name ?: "", !fixed)
        val addr = field("Address or place name (as you would type it in Maps)", p?.query ?: "")
        val here = CheckBox(a).apply {
            text = if (Gps.fresh) "Use where the car is now" else "Use where the car is now (waiting for GPS)"
            isChecked = p?.hasSpot == true && p.query.isBlank()
            box.addView(this)
        }
        val d = a.dialog().setTitle(if (p == null) "New place" else "Edit ${p.name}").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim().ifEmpty { addr.text.toString().trim().take(18).ifEmpty { "Place" } }
                val q = addr.text.toString().trim()
                val spot = here.isChecked && Gps.lat != 0.0
                val np = Place(n, if (spot) "" else q, if (spot) Gps.lat else Double.NaN, if (spot) Gps.lon else Double.NaN, p?.icon ?: "star")
                if (!np.isSet && !fixed) return@setPositiveButton
                if (idx != null) list[idx] = np else list.add(np)
                save(list); a.rebuild()
            }
            .setNegativeButton("Cancel", null)
        if (idx != null && !fixed) d.setNeutralButton("Delete") { _, _ -> list.removeAt(idx); save(list); a.rebuild() }
        if (idx != null && fixed && p?.isSet == true) d.setNeutralButton("Clear") { _, _ ->
            list[idx] = Place(p.name, "", Double.NaN, Double.NaN, p.icon); save(list); a.rebuild()
        }
        d.show()
    }

    /** Settings: list of places to change. */
    fun manage(a: MainActivity) {
        val list = saved()
        val names = list.map { "${it.name}${if (it.isSet) "" else "  (not set)"}" } + "+ Add a place"
        a.dialog().setTitle("Saved places").setItems(names.toTypedArray()) { _, i ->
            if (i < list.size) edit(a, list[i], i) else edit(a, null, null)
        }.show()
    }
}

/** Round chip button with an icon and a short name (used on the Next turn card and the places card). */
fun MainActivity.placeChip(p: Place, big: Boolean): LinearLayout {
    val a = this
    val v = a.hbox(8f).apply { gravity = Gravity.CENTER }
    v.background = when {
        Skin.sport -> ChamferBg(C.CARD2, 8f, flat = true)
        Skin.lux -> roundBg(C.CARD2, if (big) 12f else 999f, C.BORDER2)
        else -> roundBg(C.CARD2, if (big) 18f else 999f, C.BORDER)
    }
    v.pad(if (big) 12f else 14f, 0f)
    val ic = IconBtn(a, Places.icon(p.icon), if (big) 22f else 18f).apply {
        isClickable = false
        fg = if (p.isSet || p.icon !in setOf("home", "work")) C.accent else C.MUTED
    }
    v.addView(ic, lp(S.i(if (big) 28 else 22), S.i(if (big) 28 else 22)))
    v.addView(a.label(if (big) 15f else 14f, if (p.isSet || p.icon !in setOf("home", "work")) C.TEXT else C.MUTED, F.semibold).apply {
        text = if (!p.isSet && p.icon in setOf("home", "work")) "Set ${p.name.lowercase()}" else p.name
    }, lp(WRAP, WRAP))
    v.isClickable = true
    v.contentDescription = p.name
    v.setOnClickListener { Places.go(a, p) }
    return v
}

/** Card with big buttons for Home, Work, saved places and nearby searches. */
class PlacesCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(10f)

    init {
        view.background = cardBg()
        view.pad(18f, 14f)
        val head = a.hbox()
        head.addView(a.caption("Quick destinations"), lp(0, WRAP, 1f))
        val add = IconBtn(a, Ic.PLUS, 18f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Add a place" }
        add.setOnClickListener { Places.edit(a, null, null) }
        head.addView(add, lp(S.i(44), S.i(44)))
        view.addView(head, lp(MATCH, WRAP))
        val all = Places.saved().take(4) + Places.NEARBY
        val grid = FrameLayout(a)
        val rows = a.vbox(8f)
        all.chunked(4).forEach { chunk ->
            val r = a.hbox(8f, center = false)
            chunk.forEachIndexed { i, p ->
                val chip = a.placeChip(p, true)
                val savedIdx = if (Places.NEARBY.contains(p)) null else i
                chip.setOnLongClickListener { if (savedIdx != null) Places.edit(a, p, null); true }
                r.addView(chip, lp(0, MATCH, 1f))
            }
            repeat(4 - chunk.size) { r.addView(a.spacer(), lp(0, MATCH, 1f)) }
            rows.addView(r, lp(MATCH, 0, 1f))
        }
        grid.addView(rows, flp(MATCH, MATCH))
        view.addView(grid, lp(MATCH, 0, 1f))
    }

    override fun update() {}
}
