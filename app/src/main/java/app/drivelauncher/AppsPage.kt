package app.drivelauncher

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Owner's app drawer settings: hidden apps, folders, how often each app is opened. */
object Drawer {
    fun key(ri: ResolveInfo) = ri.activityInfo.packageName + "/" + ri.activityInfo.name

    fun hidden(): MutableSet<String> = try {
        val a = JSONArray(Prefs.gs("hiddenApps", "[]")); (0 until a.length()).map { a.getString(it) }.toMutableSet()
    } catch (_: Exception) { mutableSetOf() }

    fun setHidden(s: Set<String>) = Prefs.ss("hiddenApps", JSONArray(s.toList()).toString())

    /** Folder name → app keys, in the owner's order. */
    fun folders(): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        try {
            val o = JSONObject(Prefs.gs("folders", "{}"))
            for (k in o.keys()) { val a = o.getJSONArray(k); out[k] = (0 until a.length()).map { a.getString(it) }.toMutableList() }
        } catch (_: Exception) {}
        return out
    }

    fun setFolders(f: Map<String, List<String>>) {
        val o = JSONObject(); f.forEach { (k, v) -> if (v.isNotEmpty()) o.put(k, JSONArray(v)) }
        Prefs.ss("folders", o.toString())
    }

    fun uses(): JSONObject = try { JSONObject(Prefs.gs("appUses", "{}")) } catch (_: Exception) { JSONObject() }

    fun used(key: String) {
        val u = uses(); u.put(key, u.optInt(key, 0) + 1); Prefs.ss("appUses", u.toString())
    }
}

/** All installed apps: search, sort, folders, hidden apps, icon packs. */
class AppsPage(private val a: MainActivity, widthPx: Int) {
    val view = FrameLayout(a)
    private val page: LinearLayout = a.vbox(12f)
    private val grid = GridView(a)
    private val count: TextView = a.label(14f, C.MUTED, F.regular)
    private val search = EditText(a)
    private val sortBtn = IconBtn(a, Ic.SORT, 20f)
    private val sortName: TextView = a.label(14f, C.TEXT3, F.semibold)
    private val hiddenBtn = IconBtn(a, Ic.HIDE, 20f)
    private var all: List<ResolveInfo> = emptyList()
    private var items: List<Any> = emptyList() // ResolveInfo or Pair<String, List<ResolveInfo>> (folder)
    private var showHidden = false
    private val cellH = S.i(128)

    // folder panel
    private val folderBox = FrameLayout(a)
    private val folderTitle: TextView = a.label(24f, C.TEXT, F.bold)
    private val folderGrid = GridView(a)
    private var folderName = ""
    private var folderApps: List<ResolveInfo> = emptyList()

    private inner class Adapter(val src: () -> List<Any>) : BaseAdapter() {
        override fun getCount() = src().size
        override fun getItem(position: Int): Any = src()[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: a.vbox(8f).apply {
                gravity = Gravity.CENTER
                background = Skin.panel(C.CARD, if (Skin.glass) 22f else 18f)
                pad(8f, 12f)
                addView(FolderIcon(a), lp(S.i(56), S.i(56)))
                addView(a.label(13f, C.TEXT2, F.medium, lines = 2).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
                layoutParams = AbsListView.LayoutParams(MATCH, cellH)
            }
            val icon = cell.getChildAt(0) as FolderIcon
            val label = cell.getChildAt(1) as TextView
            when (val it = src()[position]) {
                is ResolveInfo -> {
                    icon.set(listOf(Apps.icon(a, it)), false)
                    label.text = it.loadLabel(a.packageManager)
                    cell.alpha = if (Drawer.key(it) in hiddenNow) 0.45f else 1f
                }
                is Pair<*, *> -> {
                    @Suppress("UNCHECKED_CAST") val apps = it.second as List<ResolveInfo>
                    icon.set(apps.take(4).map { r -> Apps.icon(a, r) }, true)
                    label.text = it.first as String
                    cell.alpha = 1f
                }
            }
            return cell
        }
    }

    private var hiddenNow: Set<String> = emptySet()
    private val adapter = Adapter { items }
    private val folderAdapter = Adapter { folderApps }

    init {
        val head = a.hbox(12f)
        head.addView(a.label(32f, C.TEXT, F.bold).apply { text = "Apps" }, lp(WRAP, WRAP))
        // search box
        val sb = a.hbox(8f)
        sb.background = roundBg(C.CARD2, 999f, C.BORDER2)
        sb.pad(14f, 0f)
        sb.addView(IconBtn(a, Ic.SEARCH, 18f).apply { isClickable = false; fg = C.MUTED }, lp(S.i(22), S.i(22)))
        search.hint = "Search apps"
        search.setHintTextColor(C.MUTED)
        search.setTextColor(C.TEXT)
        search.setTextSize(TypedValue.COMPLEX_UNIT_PX, S.p(16f))
        search.typeface = F.medium
        search.background = null
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_DONE
        search.setPadding(0, 0, 0, 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { build() }
        })
        sb.addView(search, lp(0, WRAP, 1f))
        val clear = IconBtn(a, Ic.CLOSE, 16f).apply { fg = C.MUTED; contentDescription = "Clear search" }
        clear.setOnClickListener { search.setText(""); hideKeyboard() }
        sb.addView(clear, lp(S.i(36), S.i(36)))
        head.addView(sb, lp(0, S.i(48), 1f))
        val sortBox = a.hbox(4f)
        sortBox.background = roundBg(C.CARD2, 14f, C.BORDER2)
        sortBox.pad(8f, 0f, 14f, 0f)
        sortBtn.isClickable = false; sortBtn.fg = C.TEXT3
        sortBox.addView(sortBtn, lp(S.i(32), S.i(32)))
        sortBox.addView(sortName, lp(WRAP, WRAP))
        sortBox.isClickable = true
        sortBox.contentDescription = "Sort apps"
        sortBox.setOnClickListener {
            Prefs.ss("appSort", when (Prefs.gs("appSort", "az")) { "az" -> "used"; "used" -> "new"; else -> "az" })
            build()
        }
        head.addView(sortBox, lp(WRAP, S.i(48)))
        hiddenBtn.contentDescription = "Show hidden apps"
        hiddenBtn.radius = 14f
        hiddenBtn.setOnClickListener { showHidden = !showHidden; build() }
        head.addView(hiddenBtn, lp(S.i(48), S.i(48)))
        page.addView(head, lp(MATCH, WRAP).apply { leftMargin = S.i(8) })
        page.addView(count, lp(MATCH, WRAP).apply { leftMargin = S.i(10) })

        val cols = max(3, (widthPx - S.i(32)) / S.i(136))
        for (g in listOf(grid, folderGrid)) {
            g.numColumns = cols
            g.horizontalSpacing = S.i(12)
            g.verticalSpacing = S.i(12)
            g.stretchMode = GridView.STRETCH_COLUMN_WIDTH
            g.selector = ColorDrawable(0)
        }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            when (val it = items[pos]) {
                is ResolveInfo -> open(it)
                is Pair<*, *> -> @Suppress("UNCHECKED_CAST") openFolder(it.first as String, it.second as List<ResolveInfo>)
            }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            when (val it = items[pos]) {
                is ResolveInfo -> appMenu(it, null)
                is Pair<*, *> -> folderMenu(it.first as String)
            }
            true
        }
        page.addView(grid, lp(MATCH, 0, 1f))
        view.addView(page, flp(MATCH, MATCH))

        // folder panel on top of the grid
        folderBox.setBackgroundColor(withAlpha(C.BG, 0.75f))
        folderBox.isClickable = true
        folderBox.setOnClickListener { closeFolder() }
        val panel = a.vbox(12f)
        panel.background = cardBg(if (Skin.id == Skin.CLASSIC) C.CARD2 else C.FLOAT)
        panel.pad(20f)
        panel.isClickable = true
        val ph = a.hbox(10f)
        ph.addView(IconBtn(a, Ic.FOLDER, 22f).apply { isClickable = false; fg = C.accent }, lp(S.i(32), S.i(32)))
        ph.addView(folderTitle, lp(0, WRAP, 1f))
        val close = IconBtn(a, Ic.CLOSE, 20f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Close folder" }
        close.setOnClickListener { closeFolder() }
        ph.addView(close, lp(S.i(44), S.i(44)))
        panel.addView(ph, lp(MATCH, WRAP))
        folderGrid.adapter = folderAdapter
        folderGrid.setOnItemClickListener { _, _, pos, _ -> open(folderApps[pos]) }
        folderGrid.setOnItemLongClickListener { _, _, pos, _ -> appMenu(folderApps[pos], folderName); true }
        panel.addView(folderGrid, lp(MATCH, 0, 1f))
        folderBox.addView(panel, flp(MATCH, MATCH).apply { setMargins(S.i(60), S.i(30), S.i(60), S.i(30)) })
        folderBox.visibility = View.GONE
        view.addView(folderBox, flp(MATCH, MATCH))
    }

    private fun hideKeyboard() {
        try { a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0) } catch (_: Exception) {}
        search.clearFocus()
    }

    private fun open(ri: ResolveInfo) {
        Drawer.used(Drawer.key(ri))
        hideKeyboard()
        Apps.launch(a, ri)
    }

    fun refresh() {
        all = Apps.launchables(a)
        build()
    }

    private fun build() {
        val pm = a.packageManager
        hiddenNow = Drawer.hidden()
        val q = search.text.toString().trim().lowercase()
        val sort = Prefs.gs("appSort", "az")
        sortName.put(when (sort) { "used" -> "Most used"; "new" -> "Newest"; else -> "A–Z" })
        hiddenBtn.set(fg = if (showHidden) C.accent else C.TEXT3, bg = if (showHidden) C.CARD2 else 0)
        val uses = Drawer.uses()
        val installed = HashMap<String, Long>()
        fun firstInstall(ri: ResolveInfo) = installed.getOrPut(ri.activityInfo.packageName) {
            try { pm.getPackageInfo(ri.activityInfo.packageName, 0).firstInstallTime } catch (_: Exception) { 0L }
        }
        val sorted = when (sort) {
            "used" -> all.sortedByDescending { uses.optInt(Drawer.key(it), 0) }
            "new" -> all.sortedByDescending { firstInstall(it) }
            else -> all
        }
        val visible = sorted.filter { showHidden || Drawer.key(it) !in hiddenNow }
        if (q.isNotEmpty()) {
            items = visible.filter { it.loadLabel(pm).toString().lowercase().contains(q) }
            count.put("${items.size} found")
        } else {
            val folders = Drawer.folders()
            val inFolder = folders.values.flatten().toSet()
            val byKey = visible.associateBy { Drawer.key(it) }
            val fItems = folders.mapNotNull { (name, keys) ->
                val apps = keys.mapNotNull { byKey[it] }
                if (apps.isEmpty()) null else name to apps
            }
            items = fItems + visible.filter { Drawer.key(it) !in inFolder }
            count.put("${all.size - hiddenNow.size} apps" + (if (hiddenNow.isNotEmpty()) " · ${hiddenNow.size} hidden" else "") +
                " · tap and hold an app to hide it or put it in a folder")
        }
        adapter.notifyDataSetChanged()
        if (folderBox.visibility == View.VISIBLE) {
            val f = Drawer.folders()[folderName]
            val byKey = all.associateBy { Drawer.key(it) }
            folderApps = f?.mapNotNull { byKey[it] } ?: emptyList()
            if (folderApps.isEmpty()) closeFolder() else folderAdapter.notifyDataSetChanged()
        }
    }

    private fun openFolder(name: String, apps: List<ResolveInfo>) {
        folderName = name; folderApps = apps
        folderTitle.text = name
        folderAdapter.notifyDataSetChanged()
        folderBox.visibility = View.VISIBLE
        if (Motion.on) { folderBox.alpha = 0f; folderBox.animate().alpha(1f).setDuration(180L).start() }
    }

    private fun closeFolder() { folderBox.visibility = View.GONE }

    private fun appMenu(ri: ResolveInfo, inFolder: String?) {
        val key = Drawer.key(ri)
        val pkg = ri.activityInfo.packageName
        val hidden = Drawer.hidden()
        val opts = ArrayList<Pair<String, () -> Unit>>()
        if (inFolder != null) opts.add("Take out of \"$inFolder\"" to {
            val f = Drawer.folders(); f[inFolder]?.remove(key); Drawer.setFolders(f); build()
        }) else opts.add("Put in a folder…" to { chooseFolder(key) })
        opts.add((if (key in hidden) "Show in the app list again" else "Hide from the app list") to {
            val h = Drawer.hidden(); if (!h.remove(key)) h.add(key); Drawer.setHidden(h); build()
        })
        opts.add("App info" to {
            try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) } catch (_: Exception) {}
        })
        opts.add("Uninstall" to {
            try { a.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) } catch (_: Exception) {}
        })
        a.dialog().setTitle(ri.loadLabel(a.packageManager)).setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()
    }

    private fun chooseFolder(key: String) {
        val f = Drawer.folders()
        val names = f.keys.toList()
        a.dialog().setTitle("Put in a folder").setItems((names + "+ New folder").toTypedArray()) { _, i ->
            if (i < names.size) {
                f.values.forEach { it.remove(key) }
                f[names[i]]!!.add(key); Drawer.setFolders(f); build()
            } else nameDialog("New folder", "") { n ->
                f.values.forEach { it.remove(key) }
                f.getOrPut(n) { ArrayList() }.add(key); Drawer.setFolders(f); build()
            }
        }.show()
    }

    private fun folderMenu(name: String) {
        a.dialog().setTitle(name).setItems(arrayOf("Rename", "Remove folder (apps stay)")) { _, i ->
            val f = Drawer.folders()
            if (i == 0) nameDialog("Rename folder", name) { n ->
                val nf = LinkedHashMap<String, List<String>>()
                f.forEach { (k, v) -> if (k == name) nf[n] = v else nf[k] = v }
                Drawer.setFolders(nf); build()
            } else { f.remove(name); Drawer.setFolders(f); build() }
        }.show()
    }

    private fun nameDialog(title: String, value: String, ok: (String) -> Unit) {
        val e = EditText(a).apply { setText(value); setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val box = FrameLayout(a).apply { setPadding(48, 24, 48, 0); addView(e) }
        a.dialog().setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> val n = e.text.toString().trim(); if (n.isNotEmpty()) ok(n) }
            .setNegativeButton("Cancel", null).show()
    }
}

/** App icon, or a folder showing up to four small icons. */
class FolderIcon(ctx: android.content.Context) : View(ctx) {
    private var icons: List<Drawable?> = emptyList()
    private var folder = false

    fun set(list: List<Drawable?>, isFolder: Boolean) { icons = list; folder = isFolder; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width; val h = height
        if (!folder) {
            val d = icons.firstOrNull() ?: return
            d.setBounds(0, 0, w, h); d.draw(c); return
        }
        val bg = roundBg(C.CARD2, 16f, C.BORDER2)
        bg.setBounds(0, 0, w, h); bg.draw(c)
        val pad = w * 0.12f; val gap = w * 0.06f
        val s = (w - 2 * pad - gap) / 2
        icons.take(4).forEachIndexed { i, d ->
            d ?: return@forEachIndexed
            val x = pad + (i % 2) * (s + gap); val y = pad + (i / 2) * (s + gap)
            d.setBounds(x.toInt(), y.toInt(), (x + s).toInt(), (y + s).toInt()); d.draw(c)
        }
    }
}
