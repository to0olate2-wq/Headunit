package app.drivelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast

object Apps {
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Drawable>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    fun icon(ctx: Context, ri: ResolveInfo): Drawable? {
        val k = ri.activityInfo.packageName + "/" + ri.activityInfo.name
        icons[k]?.let { return it }
        val packed = IconPack.icon(ctx, ri.activityInfo.packageName, ri.activityInfo.name)
        return packed?.also { icons[k] = it } ?: try { ri.loadIcon(ctx.packageManager).also { icons[k] = it } } catch (_: Exception) { null }
    }

    /** Icon of an app by package (its launcher activity), honouring the icon pack. */
    fun iconFor(ctx: Context, pkg: String): Drawable? {
        val cls = try { ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.className } catch (_: Exception) { null }
        if (cls != null) IconPack.icon(ctx, pkg, cls)?.let { return it }
        return try { ctx.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    }

    /** Forget cached icons (after the icon pack changed). */
    fun clearIcons() { icons.clear() }

    fun canLaunch(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: Exception) { false }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = try { ctx.packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null } ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: Exception) { false }
    }

    fun launch(ctx: Context, ri: ResolveInfo) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { ctx.startActivity(i) } catch (_: Exception) {
            Toast.makeText(ctx, "Can't open this app", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchables(ctx: Context): List<ResolveInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    private var zlinkCache: String? = null
    private var zlinkChecked = 0L

    /** ZLink / CarPlay / Android Auto host app on this unit, found by name. */
    fun findZlink(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - zlinkChecked < 60_000) return zlinkCache
        zlinkChecked = now
        val pm = ctx.packageManager
        val all = launchables(ctx)
        val words = listOf("zlink", "carlink", "autokit", "tlink", "carplay")
        zlinkCache = all.firstOrNull { ri ->
            val p = ri.activityInfo.packageName.lowercase()
            val l = ri.loadLabel(pm).toString().lowercase()
            words.any { p.contains(it) || l.contains(it) }
        }?.activityInfo?.packageName
        return zlinkCache
    }

    fun findMaps(ctx: Context): String? =
        listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps", "app.organicmaps", "net.osmand", "com.sygic.aura")
            .firstOrNull { canLaunch(ctx, it) }

    fun findMusic(ctx: Context): String? =
        listOf("com.anghami", "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music", "com.deezer.android.app")
            .firstOrNull { canLaunch(ctx, it) }
}

/**
 * Android icon packs (the common ADW / Nova / Apex format): an app that lists in res/xml/appfilter.xml
 * (or assets/appfilter.xml) which of its drawables replaces which app's icon.
 */
object IconPack {
    private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
        "com.anddoes.launcher.THEME", "com.gau.go.launcherex.theme", "com.fede.launcher.THEME_ICONPACK")
    private var loadedFor = ""
    private var map: Map<String, String> = emptyMap()
    private var res: android.content.res.Resources? = null

    /** Installed icon packs: package to name. */
    fun installed(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val out = LinkedHashMap<String, String>()
        for (a in ACTIONS) try {
            for (ri in pm.queryIntentActivities(Intent(a), 0)) {
                val p = ri.activityInfo.packageName
                if (p !in out) out[p] = ri.loadLabel(pm).toString()
            }
        } catch (_: Exception) {}
        return out.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    private fun load(ctx: Context, pack: String) {
        if (pack == loadedFor) return
        loadedFor = pack
        map = emptyMap(); res = null
        if (pack.isEmpty()) return
        try {
            val r = ctx.packageManager.getResourcesForApplication(pack)
            val m = HashMap<String, String>()
            val id = r.getIdentifier("appfilter", "xml", pack)
            val parser: org.xmlpull.v1.XmlPullParser = if (id != 0) r.getXml(id) else {
                val f = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                f.setInput(r.assets.open("appfilter.xml"), "UTF-8"); f
            }
            var ev = parser.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component") ?: ""
                    val dr = parser.getAttributeValue(null, "drawable") ?: ""
                    // ComponentInfo{com.app/com.app.MainActivity}
                    val inner = comp.substringAfter('{', "").substringBefore('}', "")
                    if (inner.contains('/') && dr.isNotEmpty()) {
                        val pkg = inner.substringBefore('/')
                        var cls = inner.substringAfter('/')
                        if (cls.startsWith(".")) cls = pkg + cls
                        m["$pkg/$cls"] = dr
                        if (!m.containsKey(pkg)) m[pkg] = dr
                    }
                }
                ev = parser.next()
            }
            map = m; res = r
            Diag.log("icon pack $pack: ${m.size} icons")
        } catch (e: Exception) {
            Diag.log("icon pack $pack: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    fun icon(ctx: Context, pkg: String, cls: String): Drawable? {
        val pack = Prefs.iconPack
        if (pack.isEmpty()) return null
        load(ctx, pack)
        val r = res ?: return null
        val name = map["$pkg/$cls"] ?: map[pkg] ?: return null
        return try {
            val id = r.getIdentifier(name, "drawable", pack).takeIf { it != 0 } ?: r.getIdentifier(name, "mipmap", pack)
            if (id == 0) null else r.getDrawable(id, null)
        } catch (_: Exception) { null }
    }
}

object Vol {
    fun adjust(ctx: Context, dir: Int) {
        try {
            ctx.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {}
    }

    fun frac(ctx: Context): Float = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    } catch (_: Exception) { 0f }
}

/** Gives the launcher access to "now playing" and navigation notifications. */
class MediaListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MediaHub.init(this)
    }

    override fun onListenerConnected() {
        Diag.log("notification access connected")
        MediaHub.onListenerConnected(this)
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val p = sbn?.packageName ?: return
        NavHub.onRemoved(p)
        MediaHub.onRemoved(p)
    }

    private fun handle(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification ?: return
            val ex = n.extras
            if (sbn.packageName != packageName && ex != null) {
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val flags = buildList {
                    ex.getString(android.app.Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')?.let { add(it) }
                    n.category?.let { add("cat=$it") }
                    if (ex.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) add("SESSION")
                    if (n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) add("ongoing")
                    n.actions?.let { a -> add("actions=" + a.joinToString("/") { (it.title ?: "").toString() }) }
                }.joinToString(" ")
                Diag.note(sbn.packageName, title, text, flags)
            }
            if (!NavHub.onPosted(this, sbn)) MediaHub.onNotification(this, sbn)
        } catch (e: Exception) {
            Diag.log("notification handling: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
