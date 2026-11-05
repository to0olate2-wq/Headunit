package app.drivelauncher

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.widget.Toast

class Track(
    val title: String,
    val artist: String,
    val pkg: String,
    val art: Bitmap?,
    val duration: Long,
    val position: Long,
    val posAt: Long,
    val playing: Boolean,
    val speed: Float,
    /** session | notification | broadcast */
    val via: String,
) {
    fun positionNow(): Long =
        if (playing && posAt > 0) position + ((SystemClock.elapsedRealtime() - posAt) * speed).toLong() else position
}

/**
 * Finds "what's playing" from every source a head unit can offer and picks the
 * best one automatically:
 *  1. Android media sessions (Spotify/Anghami on the unit, many Bluetooth/ZLink builds)
 *  2. Media-style notifications (ZLink, Bluetooth music apps that only post a notification)
 *  3. Legacy "metachanged" / AVRCP broadcasts sent by some head-unit music players
 *  4. If nothing reports song info, the buttons still work by sending media keys,
 *     which reach whatever is playing (ZLink, Bluetooth audio, radio).
 */
object MediaHub {
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    val controllers = LinkedHashMap<String, MediaController>()
    private val callbacks = HashMap<String, MediaController.Callback>()
    private val fromNotif = HashSet<String>()
    private val lastPlayingAt = HashMap<String, Long>()

    private var notifTrack: Track? = null
    private var notifAt = 0L
    private var notifActions: Array<Notification.Action>? = null
    private var notifContent: PendingIntent? = null
    private var bcTrack: Track? = null
    private var bcAt = 0L

    var current: Track? = null
        private set
    private var currentController: MediaController? = null
    var version = 0
        private set
    var accessGranted = false
        private set
    val ready: Boolean get() = app != null

    private val MEDIA_HINTS = listOf("zlink", "carlink", "carplay", "autokit", "tlink", "easyconnect", "androidauto",
        "bluetooth", ".bt", "btmusic", "music", "player", "audio", "radio", "media")
    private val NOISE = listOf("running", "service", "connected", "connecting", "is active", "tap to", "usb ")

    fun init(ctx: Context) {
        if (app != null) { tryAttach(); return }
        app = ctx.applicationContext
        registerBroadcasts()
        tryAttach()
    }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ctx.packageName)
    }

    /** Call whenever notification access may have changed. */
    fun tryAttach() {
        val a = app ?: return
        accessGranted = hasAccess(a)
        if (!accessGranted || msm != null) return
        try {
            val m = a.getSystemService(MediaSessionManager::class.java) ?: return
            val cn = ComponentName(a, MediaListenerService::class.java)
            m.addOnActiveSessionsChangedListener({ list -> onSessions(list) }, cn, main)
            msm = m
            onSessions(m.getActiveSessions(cn))
            Diag.log("media: listening to media sessions")
        } catch (e: Exception) {
            Diag.log("media: session access failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    fun onListenerConnected(ctx: Context) {
        init(ctx)
        msm = null
        tryAttach()
    }

    private fun onSessions(list: List<MediaController>?) {
        val seen = HashSet<String>()
        list?.forEach { mc -> addController(mc); seen.add(mc.packageName) }
        val it = controllers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in seen && e.key !in fromNotif) {
                callbacks.remove(e.key)?.let { cb -> try { e.value.unregisterCallback(cb) } catch (_: Exception) {} }
                it.remove()
            }
        }
        pick()
    }

    private fun addController(mc: MediaController) {
        val k = mc.packageName
        val existing = controllers[k]
        if (existing != null && existing.sessionToken == mc.sessionToken) return
        if (existing != null) callbacks.remove(k)?.let { try { existing.unregisterCallback(it) } catch (_: Exception) {} }
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state != null && isPlayingState(state.state)) lastPlayingAt[k] = SystemClock.elapsedRealtime()
                pick()
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = pick()
            override fun onSessionDestroyed() {
                controllers.remove(k); callbacks.remove(k); fromNotif.remove(k)
                pick()
            }
        }
        try { mc.registerCallback(cb, main) } catch (_: Exception) {}
        controllers[k] = mc
        callbacks[k] = cb
        if (isPlaying(mc)) lastPlayingAt[k] = SystemClock.elapsedRealtime()
        Diag.log("media session found: $k (${stateName(mc.playbackState?.state)})")
    }

    private fun isPlayingState(s: Int) = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING ||
        s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING

    private fun isPlaying(mc: MediaController) = mc.playbackState?.let { isPlayingState(it.state) } ?: false

    fun stateName(s: Int?): String = when (s) {
        null -> "no state"
        PlaybackState.STATE_PLAYING -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_STOPPED -> "stopped"
        PlaybackState.STATE_BUFFERING -> "buffering"
        PlaybackState.STATE_NONE -> "none"
        PlaybackState.STATE_ERROR -> "error"
        else -> "state $s"
    }

    private fun str(v: CharSequence?): String? = v?.toString()?.takeIf { it.isNotBlank() }

    private fun fromController(mc: MediaController): Track {
        val md = mc.metadata
        val st = mc.playbackState
        val desc = md?.description
        val title = str(md?.getString(MediaMetadata.METADATA_KEY_TITLE)) ?: str(md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?: str(desc?.title) ?: ""
        val artist = str(md?.getString(MediaMetadata.METADATA_KEY_ARTIST)) ?: str(md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
            ?: str(md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)) ?: str(desc?.subtitle) ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: desc?.iconBitmap
        val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return Track(title, artist, mc.packageName, art, dur, st?.position ?: 0L, st?.lastPositionUpdateTime ?: 0L,
            isPlaying(mc), st?.playbackSpeed?.takeIf { it > 0f } ?: 1f, "session")
    }

    /** Chooses which source to show. Runs on the main thread. */
    fun pick() {
        val now = SystemClock.elapsedRealtime()
        val pin = try { Prefs.mediaPin } catch (_: Exception) { "" }
        val playingSession = controllers.values.filter { isPlaying(it) }.maxByOrNull { lastPlayingAt[it.packageName] ?: 0L }
        val pausedSession = controllers.values.filter { it.metadata != null }.maxByOrNull { lastPlayingAt[it.packageName] ?: 0L }
        val nt = notifTrack?.takeIf { now - notifAt < 30 * 60_000L }
        val bt = bcTrack?.takeIf { now - bcAt < 30 * 60_000L }
        var ctrl: MediaController? = null
        val pinned = if (pin.isNotEmpty()) controllers[pin] else null
        val t: Track? = when {
            pinned != null -> { ctrl = pinned; fromController(pinned) }
            pin == "notification" && nt != null -> nt
            pin == "broadcast" && bt != null -> bt
            playingSession != null -> { ctrl = playingSession; fromController(playingSession) }
            nt?.playing == true -> nt
            bt?.playing == true -> bt
            pausedSession != null -> { ctrl = pausedSession; fromController(pausedSession) }
            nt != null -> nt
            else -> bt
        }
        currentController = ctrl
        current = t?.takeIf { it.title.isNotEmpty() }
        version++
    }

    /** Up-next list (title to artist) when the playing app shares its queue. */
    /** Used only by the automated screen tests to show sample music. */
    fun showForTest(t: Track?) {
        current = t
        version++
    }

    fun queue(): List<Pair<String, String>> {
        val q = try { currentController?.queue } catch (_: Exception) { null } ?: return emptyList()
        val curTitle = current?.title
        val out = ArrayList<Pair<String, String>>()
        var after = curTitle == null || q.none { it.description.title?.toString() == curTitle }
        for (item in q) {
            val title = item.description.title?.toString() ?: continue
            if (!after) { if (title == curTitle) after = true; continue }
            out.add(title to (item.description.subtitle?.toString() ?: ""))
            if (out.size >= 6) break
        }
        return out
    }

    // ---------- notifications ----------

    fun onNotification(ctx: Context, sbn: StatusBarNotification) {
        init(ctx)
        val pkg = sbn.packageName ?: return
        if (pkg == ctx.packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: Bundle()
        val token = sessionToken(ex)
        if (token != null) {
            try {
                fromNotif.add(pkg)
                addController(MediaController(ctx, token))
                pick()
            } catch (e: Exception) {
                Diag.log("media: token from $pkg failed: ${e.message}")
            }
            return
        }
        val template = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val lowPkg = pkg.lowercase()
        val mediaLike = template.contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT ||
            MEDIA_HINTS.any { lowPkg.contains(it) }
        if (!mediaLike) return
        val title = str(ex.getCharSequence(Notification.EXTRA_TITLE)) ?: return
        val text = str(ex.getCharSequence(Notification.EXTRA_TEXT)) ?: ""
        val label = Apps.label(ctx, pkg)
        val strongMedia = template.contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT
        if (!strongMedia) {
            val all = (title + " " + text).lowercase()
            if (title.equals(label, true) || NOISE.any { all.contains(it) }) return
        }
        val actions = n.actions
        val playing = guessPlaying(actions)
        notifTrack = Track(title, text, pkg, largeIcon(ctx, n), 0L, 0L, 0L, playing, 1f, "notification")
        notifAt = SystemClock.elapsedRealtime()
        notifActions = actions
        notifContent = n.contentIntent
        pick()
    }

    fun onRemoved(pkg: String) {
        if (notifTrack?.pkg == pkg) { notifTrack = null; notifActions = null; notifContent = null; pick() }
    }

    @Suppress("DEPRECATION")
    private fun sessionToken(ex: Bundle): MediaSession.Token? = try {
        if (Build.VERSION.SDK_INT >= 33) ex.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        else ex.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
    } catch (_: Exception) { null }

    private fun guessPlaying(actions: Array<Notification.Action>?): Boolean {
        if (actions.isNullOrEmpty()) return true
        val titles = actions.map { (it.title ?: "").toString().lowercase() }
        if (titles.any { it.contains("pause") || it.contains("暂停") }) return true
        if (titles.any { it == "play" || it.contains("播放") }) return false
        return true
    }

    @Suppress("DEPRECATION")
    fun largeIcon(ctx: Context, n: Notification): Bitmap? {
        try {
            val ic = n.getLargeIcon()
            if (ic != null) {
                val d = ic.loadDrawable(ctx)
                if (d != null) return toBitmap(d, 256)
            }
        } catch (_: Exception) {
        }
        return try { n.extras?.get(Notification.EXTRA_LARGE_ICON) as? Bitmap } catch (_: Exception) { null }
    }

    fun toBitmap(d: Drawable, max: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth.coerceAtMost(max) else max
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight.coerceAtMost(max) else max
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    // ---------- broadcasts ----------

    private const val AVRCP_TRACK = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT"
    private const val AVRCP_META = "android.bluetooth.avrcp-controller.profile.extra.METADATA"
    private const val AVRCP_STATE = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            try { onBroadcast(i) } catch (e: Exception) { Diag.log("broadcast parse: ${e.message}") }
        }
    }

    private fun registerBroadcasts() {
        val a = app ?: return
        val f = IntentFilter()
        listOf(
            "com.android.music.metachanged", "com.android.music.playstatechanged",
            "com.htc.music.metachanged", "com.miui.player.metachanged", "com.sec.android.app.music.metachanged",
            "com.samsung.sec.android.MusicPlayer.metachanged", "com.andrew.apollo.metachanged",
            "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged",
            "com.maxmpz.audioplayer.TRACK_CHANGED", "com.maxmpz.audioplayer.STATUS_CHANGED",
            AVRCP_TRACK,
        ).forEach { f.addAction(it) }
        try {
            if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else a.registerReceiver(receiver, f)
        } catch (e: Exception) {
            Diag.log("media: broadcast receiver failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun onBroadcast(i: Intent) {
        val ex = i.extras ?: return
        val pkg = i.`package` ?: i.action?.substringBeforeLast('.') ?: "broadcast"
        var title: String? = null
        var artist = ""
        var playing: Boolean? = null
        var dur = 0L
        var pos = 0L
        if (i.action == AVRCP_TRACK) {
            val md = ex.get(AVRCP_META) as? MediaMetadata
            val st = ex.get(AVRCP_STATE) as? PlaybackState
            title = str(md?.getString(MediaMetadata.METADATA_KEY_TITLE))
            artist = str(md?.getString(MediaMetadata.METADATA_KEY_ARTIST)) ?: ""
            dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (st != null) { playing = isPlayingState(st.state); pos = st.position }
        } else {
            title = ex.keySet().firstOrNull { it in setOf("track", "title", "TRACK", "TITLE", "name") }?.let { str(ex.get(it)?.toString()) }
            artist = ex.keySet().firstOrNull { it in setOf("artist", "ARTIST") }?.let { ex.get(it)?.toString() } ?: ""
            for (k in listOf("playing", "isPlaying", "playstate", "is_playing")) {
                val v = ex.get(k)
                if (v is Boolean) { playing = v; break }
            }
            (ex.get("duration") as? Number)?.let { dur = it.toLong() }
            (ex.get("position") as? Number)?.let { pos = it.toLong() }
        }
        Diag.log("broadcast ${i.action}: ${title ?: "-"} / $artist playing=$playing")
        val prevT = bcTrack
        val t = title ?: prevT?.title ?: return
        bcTrack = Track(t, if (title != null) artist else prevT?.artist ?: artist, pkg, null, dur, pos,
            SystemClock.elapsedRealtime(), playing ?: prevT?.playing ?: true, 1f, "broadcast")
        bcAt = SystemClock.elapsedRealtime()
        pick()
    }

    // ---------- controls ----------

    fun playPause(ctx: Context) {
        val c = currentController
        if (c != null) {
            if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play()
            return
        }
        val t = current
        if (t?.via == "notification") {
            val want = if (t.playing) listOf("pause", "暂停") else listOf("play", "播放")
            if (notifAction(want, 1)) return
        }
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun next(ctx: Context) {
        currentController?.let { it.transportControls.skipToNext(); return }
        if (current?.via == "notification" && notifAction(listOf("next", "skip", "下一"), 2)) return
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun prev(ctx: Context) {
        currentController?.let { it.transportControls.skipToPrevious(); return }
        if (current?.via == "notification" && notifAction(listOf("prev", "previous", "back", "上一"), 0)) return
        mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** Find a notification button by its name; if there are exactly 3 buttons fall back to position. */
    private fun notifAction(words: List<String>, threeIndex: Int): Boolean {
        val acts = notifActions ?: return false
        val hit = acts.firstOrNull { a -> val t = (a.title ?: "").toString().lowercase(); words.any { t.contains(it) } }
            ?: if (acts.size == 3) acts[threeIndex] else null
        return try { hit?.actionIntent?.send(); hit != null } catch (_: Exception) { false }
    }

    fun key(ctx: Context, code: Int) = mediaKey(ctx, code)

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Diag.log("media key sent: $code")
    }

    /** Opens the app that is playing (or ZLink when the music comes from the phone). */
    fun openSourceApp(ctx: Context) {
        val t = current
        if (t?.via == "notification") {
            try { notifContent?.send(); return } catch (_: Exception) {}
        }
        val pkg = t?.pkg?.takeIf { Apps.canLaunch(ctx, it) } ?: Apps.findZlink(ctx) ?: Apps.findMusic(ctx)
        if (pkg == null || !Apps.launch(ctx, pkg)) Toast.makeText(ctx, "No music app found", Toast.LENGTH_SHORT).show()
    }

    /** Manual override: Auto -> each source -> Auto. Returns a label for the new choice. */
    fun cycleSource(ctx: Context): String {
        val options = ArrayList<String>()
        options.add("")
        options.addAll(controllers.keys)
        if (notifTrack != null) options.add("notification")
        if (bcTrack != null) options.add("broadcast")
        val cur = Prefs.mediaPin
        val idx = options.indexOf(cur)
        val nextPin = options[(idx + 1) % options.size]
        Prefs.mediaPin = nextPin
        pick()
        return pinLabel(ctx, nextPin)
    }

    fun pinLabel(ctx: Context, pin: String = Prefs.mediaPin): String = when (pin) {
        "" -> "Auto"
        "notification" -> "Notification (${notifTrack?.let { Apps.label(ctx, it.pkg) } ?: "?"})"
        "broadcast" -> "Broadcast"
        else -> Apps.label(ctx, pin)
    }

    fun sourceName(ctx: Context, t: Track): String {
        val label = Apps.label(ctx, t.pkg)
        return if (t.pkg.lowercase().contains("zlink") || label.lowercase().contains("zlink")) "Phone via ZLink" else label
    }

    fun describe(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Notification access: ").append(if (accessGranted) "yes" else "NO").append('\n')
        sb.append("Source choice: ").append(pinLabel(ctx)).append('\n')
        if (controllers.isEmpty()) sb.append("Media sessions: none\n")
        for ((k, mc) in controllers) {
            val t = fromController(mc)
            sb.append("Session ").append(k).append(" [").append(stateName(mc.playbackState?.state)).append("] ")
                .append(t.title.ifEmpty { "(no title)" }).append(" / ").append(t.artist).append('\n')
        }
        notifTrack?.let { sb.append("Notification track: ${it.pkg} ${it.title} / ${it.artist} playing=${it.playing}\n") }
        bcTrack?.let { sb.append("Broadcast track: ${it.pkg} ${it.title} / ${it.artist} playing=${it.playing}\n") }
        val c = current
        sb.append("Showing: ").append(if (c == null) "nothing" else "${c.title} (${c.via}, ${c.pkg})")
        return sb.toString()
    }
}

/** Turn-by-turn info read from the navigation app's notification. */
object NavHub {
    val NAV_PKGS = setOf(
        "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "ru.yandex.yandexnavi",
        "ru.yandex.yandexmaps", "com.sygic.aura", "net.osmand", "net.osmand.plus", "app.organicmaps",
        "com.mapswithme.maps.pro", "com.tomtom.gplay.navapp", "com.navitel", "cz.aponia.bor3", "com.nng.igo.primong.igoworld",
    )
    var active = false
    var title = ""
    var text = ""
    var sub = ""
    var icon: Bitmap? = null
    var pkg = ""
    var version = 0

    /** Returns true if the notification belonged to a navigation app. */
    fun onPosted(ctx: Context, sbn: StatusBarNotification): Boolean {
        val p = sbn.packageName ?: return false
        if (p !in NAV_PKGS) return false
        val n = sbn.notification ?: return true
        if (n.flags and Notification.FLAG_ONGOING_EVENT == 0) return true
        val ex = n.extras ?: return true
        title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        icon = MediaHub.largeIcon(ctx, n)
        pkg = p
        active = title.isNotBlank() || text.isNotBlank()
        version++
        return true
    }

    fun onRemoved(p: String) {
        if (p == pkg && active) { active = false; icon = null; version++ }
    }

    fun appName(ctx: Context): String = when (pkg) {
        "com.google.android.apps.maps" -> "Google Maps"
        "com.waze" -> "Waze"
        "com.here.app.maps" -> "HERE WeGo"
        else -> Apps.label(ctx, pkg)
    }
}
