package app.drivelauncher

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private fun lc(s: CharSequence?) = s?.toString()?.lowercase(Locale.ROOT)?.trim() ?: ""

/** Is the car moving (for "while driving" options)? */
fun driving(): Boolean = (Gps.fresh && Gps.speedKmh > 10f) || (Obd.v("speed") ?: 0f) > 10f || Trips.current != null

// ------------------------------------------------------------------ phone

/**
 * Calls through the head unit's own Bluetooth phone: favourite contacts, recent calls, dialling, and a call banner
 * built from the phone app's call notification (answer / decline / hang up buttons are the notification's own).
 * Calls made inside ZLink (CarPlay / Android Auto) stay in ZLink.
 */
object PhoneHub {
    class Fav(val name: String, val number: String)
    class Recent(val name: String, val number: String, val type: Int, val time: Long)

    @Volatile var active = false
        private set
    var incoming = false
        private set
    var caller = ""
        private set
    var state = ""
        private set
    var appPkg = ""
        private set
    private var key = ""
    private var answer: PendingIntent? = null
    private var decline: PendingIntent? = null
    private var hangup: PendingIntent? = null
    @Volatile var version = 0
        private set

    private val ANSWER = listOf("answer", "accept", "pick up", "รับสาย", "رد", "接听")
    private val DECLINE = listOf("decline", "reject", "ignore", "dismiss", "ปฏิเสธ", "رفض", "拒绝")
    private val HANGUP = listOf("hang up", "end call", "end", "วางสาย", "إنهاء", "挂断")

    /** @return true if this was a call notification */
    fun onPosted(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val acts = n.actions ?: emptyArray()
        fun find(words: List<String>) = acts.firstOrNull { a -> val t = lc(a.title); words.any { t == it || t.startsWith(it) } }?.actionIntent
        val tmpl = n.extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val a = find(ANSWER); val d = find(DECLINE); val h = find(HANGUP)
        val isCall = n.category == Notification.CATEGORY_CALL || tmpl.endsWith("CallStyle") ||
            ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0 && (a != null || h != null))
        if (!isCall) return false
        val ex = n.extras
        caller = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.ifBlank { null } ?: "Call"
        state = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        answer = a; decline = d; hangup = h
        incoming = a != null
        if (state.isBlank()) state = if (incoming) "Incoming call" else "On a call"
        key = sbn.key ?: ""
        appPkg = sbn.packageName ?: ""
        active = true
        version++
        return true
    }

    /** For tests: pretend a call is ringing (or end it with null). */
    fun setCallForTest(name: String?, ringing: Boolean = true) {
        active = name != null; caller = name ?: ""; incoming = ringing; state = if (ringing) "Incoming call" else "On a call"
        appPkg = "com.syu.bt"; version++
    }

    fun onRemoved(sbnKey: String?) {
        if (active && sbnKey == key) { active = false; answer = null; decline = null; hangup = null; version++ }
    }

    fun doAnswer() = send(answer)
    fun doDecline() = send(decline ?: hangup)
    fun doHangup() = send(hangup ?: decline)

    private fun send(pi: PendingIntent?) {
        try { pi?.send() } catch (e: Exception) { Diag.log("call button: ${e.message}") }
    }

    fun favs(): MutableList<Fav> = try {
        val a = JSONArray(Prefs.phoneFavs)
        (0 until a.length()).map { val o = a.getJSONObject(it); Fav(o.optString("name"), o.optString("number")) }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    fun saveFavs(l: List<Fav>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("name", it.name).put("number", it.number)) }
        Prefs.phoneFavs = a.toString()
    }

    fun granted(ctx: Context, p: String) = try { ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }

    fun recent(ctx: Context, max: Int): List<Recent> {
        if (!granted(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<Recent>()
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val num = c.getString(1) ?: continue
                    if (out.any { it.number == num }) continue
                    out.add(Recent(c.getString(0)?.ifBlank { null } ?: num, num, c.getInt(2), c.getLong(3)))
                }
            }
        } catch (e: Exception) { Diag.log("call log: ${e.message}") }
        return out
    }

    /** The head unit's phone app (whatever handles "dial a number"). */
    fun phoneApp(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")), 0)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
    } catch (_: Exception) { null }

    fun dial(a: MainActivity, number: String) {
        val tel = Uri.parse("tel:" + Uri.encode(number))
        val direct = granted(a, Manifest.permission.CALL_PHONE)
        for (action in if (direct) listOf(Intent.ACTION_CALL, Intent.ACTION_DIAL) else listOf(Intent.ACTION_DIAL)) {
            try { a.startActivity(Intent(action, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        openApp(a)
    }

    fun openApp(a: MainActivity) {
        val p = phoneApp(a)
        if (p != null && Apps.launch(a, p)) return
        Toast.makeText(a, "No phone app found on this head unit", Toast.LENGTH_LONG).show()
    }

    fun askAccess(a: MainActivity) {
        try {
            a.requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), MainActivity.REQ_PHONE)
        } catch (_: Exception) {}
    }

    /** Add a favourite: pick from the unit's contacts or type it in. */
    fun addFav(a: MainActivity) {
        a.dialog().setTitle("Add a favourite").setItems(arrayOf("Choose from contacts", "Type a name and number")) { _, i ->
            if (i == 0) try {
                @Suppress("DEPRECATION")
                a.startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), MainActivity.REQ_CONTACT)
            } catch (_: Exception) {
                Toast.makeText(a, "No contacts on this head unit — type the number instead", Toast.LENGTH_LONG).show()
            } else typeFav(a)
        }.show()
    }

    private fun typeFav(a: MainActivity) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val name = EditText(a).apply { hint = "Name"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS }
        val num = EditText(a).apply { hint = "Phone number"; setSingleLine(true); inputType = InputType.TYPE_CLASS_PHONE }
        box.addView(name); box.addView(num)
        a.dialog().setTitle("Favourite contact").setView(box).setPositiveButton("Save") { _, _ ->
            val n = num.text.toString().trim()
            if (n.isNotEmpty()) { val l = favs(); l.add(Fav(name.text.toString().trim().ifEmpty { n }, n)); saveFavs(l); a.rebuild() }
        }.setNegativeButton("Cancel", null).show()
    }

    /** Result of the contacts picker. */
    fun onPicked(a: MainActivity, uri: Uri) {
        try {
            a.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(1) ?: return
                    val l = favs(); l.add(Fav(c.getString(0) ?: n, n)); saveFavs(l); a.rebuild()
                }
            }
        } catch (e: Exception) { Toast.makeText(a, "Couldn't read that contact", Toast.LENGTH_SHORT).show() }
    }
}

// ------------------------------------------------------------------ messages

/**
 * New messages from chat apps on the head unit (LINE, WhatsApp, Messenger, Telegram, SMS…): shown in a banner,
 * read aloud, and (if the owner turns it on) answered automatically while driving through the notification's own
 * reply button. Messages that arrive inside ZLink (CarPlay / Android Auto) never reach the head unit.
 */
object MessageHub {
    class Msg(val pkg: String, val app: String, val sender: String, val text: String, val time: Long, val key: String,
              val reply: Notification.Action?, val group: Boolean)

    private val CHAT = setOf("jp.naver.line.android", "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "com.facebook.mlite",
        "org.telegram.messenger", "org.thunderdog.challegram", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.android.mms", "com.viber.voip", "org.thoughtcrime.securesms", "com.tencent.mm", "com.discord", "com.instagram.android",
        "com.kakao.talk", "com.imo.android.imoim", "com.snapchat.android", "com.microsoft.teams", "com.Slack")

    val recent = ArrayList<Msg>()
    @Volatile var version = 0
        private set
    var last: Msg? = null
        private set
    private val seen = HashMap<String, String>()
    private val replied = HashMap<String, Long>()

    fun onPosted(ctx: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val pkg = sbn.packageName ?: return false
        val ex = n.extras ?: return false
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val isChat = n.category == Notification.CATEGORY_MESSAGE || pkg in CHAT || msgs != null
        if (!isChat) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return true
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return true
        var sender = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        var time = n.`when`
        if (msgs != null && msgs.isNotEmpty()) {
            val b = msgs.last() as? Bundle
            if (b != null) {
                text = b.getCharSequence("text")?.toString() ?: text
                time = b.getLong("time", time)
                val who = b.getCharSequence("sender")?.toString()
                    ?: (if (android.os.Build.VERSION.SDK_INT >= 28) personName(b) else null)
                val conv = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                sender = if (conv != null && who != null) "$who · $conv" else who ?: sender
            }
        }
        if (text.isBlank() || sender.isBlank()) return true
        val k = sbn.key ?: "$pkg/$sender"
        val sig = "$time|$text"
        if (seen[k] == sig) return true
        seen[k] = sig
        val reply = n.actions?.firstOrNull { act ->
            act.remoteInputs?.isNotEmpty() == true &&
                (android.os.Build.VERSION.SDK_INT < 28 || act.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY || act.semanticAction == 0)
        }
        val group = ex.getBoolean("android.isGroupConversation", false)
        val m = Msg(pkg, Apps.label(ctx, pkg), sender, text, System.currentTimeMillis(), k, reply, group)
        synchronized(recent) { recent.add(0, m); while (recent.size > 12) recent.removeAt(recent.size - 1) }
        last = m
        version++
        val read = Prefs.readMsgs
        if (read == 2 || (read == 1 && driving())) Speaker.say(ctx, spoken(m))
        if (Prefs.autoReply && driving() && reply != null && !group) {
            val prev = replied[k] ?: 0L
            if (SystemClock.elapsedRealtime() - prev > 20 * 60_000L || prev == 0L) {
                if (reply(ctx, m, Prefs.autoReplyText)) { replied[k] = SystemClock.elapsedRealtime(); Diag.log("auto-replied on ${m.app}") }
            }
        }
        return true
    }

    @android.annotation.TargetApi(28)
    private fun personName(b: Bundle): String? = try {
        @Suppress("DEPRECATION") (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString()
    } catch (_: Exception) { null }

    fun spoken(m: Msg): String = if (Prefs.readText) "${m.sender} on ${m.app}: ${m.text.take(240)}" else "New ${m.app} message from ${m.sender}"

    /** Send a reply through the chat app's own reply button. */
    fun reply(ctx: Context, m: Msg, text: String): Boolean {
        val a = m.reply ?: return false
        val inputs = a.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val b = Bundle()
            for (r in inputs) b.putCharSequence(r.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, b)
            a.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: Exception) { Diag.log("reply: ${e.message}"); false }
    }

    fun addForTest(m: Msg) { synchronized(recent) { recent.add(0, m) }; last = m; version++ }
}

/** Reads text aloud; music is turned down while it speaks. Thai text uses a Thai voice when the unit has one. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayList<String>()
    private var focus: AudioFocusRequest? = null

    fun say(ctx: Context, text: String) {
        val app = ctx.applicationContext
        val t = tts
        if (t == null) {
            queue.add(text)
            tts = TextToSpeech(app) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (!ready) Diag.log("text to speech not available ($st)")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { release(app) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { release(app) }
                })
                val q = ArrayList(queue); queue.clear()
                q.forEach { speak(app, it) }
            }
            return
        }
        if (ready) speak(app, text) else queue.add(text)
    }

    private fun speak(ctx: Context, text: String) {
        val t = tts ?: return
        try {
            val thai = text.any { it in '฀'..'๿' }
            val loc = if (thai) Locale("th", "TH") else Locale.getDefault()
            if (t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t.language = loc
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            t.setAudioAttributes(attrs)
            val am = ctx.getSystemService(AudioManager::class.java)
            val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            focus = fr
            am?.requestAudioFocus(fr)
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "msg" + SystemClock.elapsedRealtime())
        } catch (e: Exception) { Diag.log("speak: ${e.message}") }
    }

    private fun release(ctx: Context) {
        try { if (tts?.isSpeaking != true) focus?.let { ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }
}

// ------------------------------------------------------------------ banners

/** Call and message banners at the top of the launcher. */
object Banners {
    private var shownPhone = -1
    private var shownMsg = -1
    private var msgUntil = 0L
    private var view: View? = null
    private var kind = ""

    fun attach(a: MainActivity) { shownPhone = -1; shownMsg = MessageHub.version; view = null; kind = "" }

    fun update(a: MainActivity) {
        val box = a.bannerBox ?: return
        if (PhoneHub.version != shownPhone) {
            shownPhone = PhoneHub.version
            if (PhoneHub.active) { put(a, box, callBanner(a), "call"); return }
            if (kind == "call") clear(box)
        }
        if (PhoneHub.active) return
        if (MessageHub.version != shownMsg) {
            shownMsg = MessageHub.version
            val m = MessageHub.last
            if (m != null) { put(a, box, msgBanner(a, m), "msg"); msgUntil = SystemClock.elapsedRealtime() + 15_000 }
        }
        if (kind == "msg" && SystemClock.elapsedRealtime() > msgUntil) clear(box)
    }

    private fun put(a: MainActivity, box: FrameLayout, v: View, k: String) {
        box.removeAllViews()
        box.addView(v, FrameLayout.LayoutParams(minOf(S.i(720), a.window.decorView.width - S.i(32)).coerceAtLeast(S.i(320)), WRAP,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = S.i(16) })
        view = v; kind = k
        if (Motion.on) { v.alpha = 0f; v.translationY = -S.p(30f); v.animate().alpha(1f).translationY(0f).setDuration(220L).start() }
    }

    private fun clear(box: FrameLayout) { box.removeAllViews(); view = null; kind = "" }

    private fun shell(a: MainActivity): LinearLayout = a.hbox(14f).apply {
        background = if (Skin.id == Skin.CLASSIC) roundBg(C.CARD2, 20f, C.BORDER2) else Skin.panel(C.FLOAT, 22f)
        pad(18f, 14f)
        elevation = S.p(12f)
        isClickable = true
    }

    private fun avatar(a: MainActivity, name: String, color: Int): TextView = a.label(22f, C.ON_GRAD, F.semibold).apply {
        text = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
        gravity = Gravity.CENTER
        background = roundBg(color, 999f)
    }

    private fun round(a: MainActivity, ic: Ic, color: Int, desc: String, click: () -> Unit) = IconBtn(a, ic, 24f).apply {
        circle = true; bgColor = color; fg = 0xFFFFFFFF.toInt(); sw = 2.2f; contentDescription = desc
        setOnClickListener { click() }
    }

    private fun callBanner(a: MainActivity): View {
        val v = shell(a)
        v.addView(avatar(a, PhoneHub.caller, 0xFF2E7D5B.toInt()), lp(S.i(52), S.i(52)))
        val col = a.vbox(2f)
        col.addView(a.label(20f, C.TEXT, F.semibold).apply { text = PhoneHub.caller }, lp(MATCH, WRAP))
        col.addView(a.label(14f, C.MUTED, F.regular).apply { text = PhoneHub.state + " · " + Apps.label(a, PhoneHub.appPkg) }, lp(MATCH, WRAP))
        v.addView(col, lp(0, WRAP, 1f))
        if (PhoneHub.incoming) {
            v.addView(round(a, Ic.CLOSE, 0xFFE5484D.toInt(), "Decline") { PhoneHub.doDecline() }, lp(S.i(60), S.i(60)))
            v.addView(round(a, Ic.PHONE, 0xFF2EBD6B.toInt(), "Answer") { PhoneHub.doAnswer() }, lp(S.i(60), S.i(60)))
        } else {
            v.addView(round(a, Ic.CLOSE, 0xFFE5484D.toInt(), "Hang up") { PhoneHub.doHangup() }, lp(S.i(60), S.i(60)))
        }
        v.setOnClickListener { PhoneHub.appPkg.takeIf { it.isNotEmpty() }?.let { Apps.launch(a, it) } }
        return v
    }

    private fun msgBanner(a: MainActivity, m: MessageHub.Msg): View {
        val v = shell(a)
        val icon = FolderIcon(a).apply { set(listOf(Apps.iconFor(a, m.pkg)), false) }
        v.addView(icon, lp(S.i(44), S.i(44)))
        val col = a.vbox(2f)
        col.addView(a.label(17f, C.TEXT, F.semibold).apply { text = m.sender }, lp(MATCH, WRAP))
        col.addView(a.label(15f, C.TEXT2, F.regular, lines = 2).apply { text = m.text }, lp(MATCH, WRAP))
        v.addView(col, lp(0, WRAP, 1f))
        val read = IconBtn(a, Ic.SPEAKER, 22f).apply { bgColor = C.CARD2; radius = 14f; contentDescription = "Read aloud" }
        read.setOnClickListener { Speaker.say(a, MessageHub.spoken(m)); msgUntil = SystemClock.elapsedRealtime() + 15_000 }
        v.addView(read, lp(S.i(52), S.i(52)))
        if (m.reply != null) {
            val rep = IconBtn(a, Ic.SEND, 22f).apply { bgColor = C.CARD2; radius = 14f; contentDescription = "Quick reply" }
            rep.setOnClickListener { msgUntil = SystemClock.elapsedRealtime() + 30_000; quickReply(a, m) }
            v.addView(rep, lp(S.i(52), S.i(52)))
        }
        val close = IconBtn(a, Ic.CLOSE, 18f).apply { contentDescription = "Close" }
        close.setOnClickListener { msgUntil = 0L; update(a) }
        v.addView(close, lp(S.i(44), S.i(44)))
        v.setOnClickListener { Apps.launch(a, m.pkg) }
        return v
    }

    fun quickReply(a: MainActivity, m: MessageHub.Msg) {
        val opts = listOf(Prefs.autoReplyText, "On my way.", "OK 👍", "I'll call you back.", "ขับรถอยู่ เดี๋ยวตอบนะ")
        a.dialog().setTitle("Reply to ${m.sender}").setItems(opts.toTypedArray()) { _, i ->
            Toast.makeText(a, if (MessageHub.reply(a, m, opts[i])) "Sent" else "This app doesn't allow replies from here", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel", null).show()
    }
}

// ------------------------------------------------------------------ cards

/** Favourite contacts, recent calls and the current call. */
class PhoneCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(10f)
    private val call = a.hbox(12f)
    private val callName: TextView = a.label(20f, C.TEXT, F.semibold)
    private val callState: TextView = a.label(14f, C.MUTED, F.regular)
    private val answer = IconBtn(a, Ic.PHONE, 22f).apply { circle = true; bgColor = 0xFF2EBD6B.toInt(); fg = 0xFFFFFFFF.toInt(); contentDescription = "Answer" }
    private val end = IconBtn(a, Ic.CLOSE, 22f).apply { circle = true; bgColor = 0xFFE5484D.toInt(); fg = 0xFFFFFFFF.toInt(); contentDescription = "Decline or hang up" }
    private var shown = -1

    init {
        view.background = cardBg()
        view.pad(18f, 14f)
        val head = a.hbox(8f)
        head.addView(a.caption("Phone"), lp(0, WRAP, 1f))
        val app = a.label(13f, C.TEXT3, F.semibold).apply {
            text = "Phone app"; background = roundBg(C.CARD2, 12f, C.BORDER2); gravity = Gravity.CENTER; isClickable = true
            setPadding(S.i(12), 0, S.i(12), 0)
            setOnClickListener { PhoneHub.openApp(a) }
        }
        head.addView(app, lp(WRAP, S.i(40)))
        val add = IconBtn(a, Ic.PLUS, 18f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Add a favourite" }
        add.setOnClickListener { PhoneHub.addFav(a) }
        head.addView(add, lp(S.i(40), S.i(40)))
        view.addView(head, lp(MATCH, WRAP))
        // current call
        call.background = Skin.tile(C.CARD2)
        call.pad(14f, 10f)
        val cc = a.vbox(2f); cc.addView(callName, lp(MATCH, WRAP)); cc.addView(callState, lp(MATCH, WRAP))
        call.addView(cc, lp(0, WRAP, 1f))
        answer.setOnClickListener { PhoneHub.doAnswer() }
        end.setOnClickListener { if (PhoneHub.incoming) PhoneHub.doDecline() else PhoneHub.doHangup() }
        call.addView(end, lp(S.i(52), S.i(52)))
        call.addView(answer, lp(S.i(52), S.i(52)))
        view.addView(call, lp(MATCH, WRAP))
        // favourites
        val favs = PhoneHub.favs()
        val grid = a.hbox(8f, center = false)
        favs.take(5).forEach { f ->
            val cell = a.vbox(6f).apply { gravity = Gravity.CENTER; isClickable = true; contentDescription = "Call ${f.name}" }
            cell.addView(a.label(20f, C.ON_GRAD, F.semibold).apply {
                text = f.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#"
                gravity = Gravity.CENTER
                background = if (Skin.id == Skin.CLASSIC) roundBg(C.accent, 999f) else Skin.gradient(999f)
                if (Skin.lux || Skin.id == Skin.CLASSIC) setTextColor(C.ON_ACCENT)
            }, lp(S.i(52), S.i(52)))
            cell.addView(a.label(13f, C.TEXT2, F.medium).apply { text = f.name; gravity = Gravity.CENTER }, lp(MATCH, WRAP))
            cell.setOnClickListener { PhoneHub.dial(a, f.number) }
            cell.setOnLongClickListener {
                a.confirm("Remove ${f.name}?", f.number) { val l = PhoneHub.favs(); l.removeAll { it.number == f.number && it.name == f.name }; PhoneHub.saveFavs(l); a.rebuild() }
                true
            }
            grid.addView(cell, lp(0, WRAP, 1f))
        }
        if (favs.isNotEmpty()) { repeat(5 - favs.take(5).size) { grid.addView(a.spacer(), lp(0, WRAP, 1f)) }; view.addView(grid, lp(MATCH, WRAP)) }
        // recent calls
        val rec = PhoneHub.recent(a, 3)
        rec.forEach { r ->
            val row = a.hbox(10f)
            val ic = IconBtn(a, Ic.PHONE, 16f).apply {
                isClickable = false
                fg = if (r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE) C.WARN else C.MUTED
            }
            row.addView(ic, lp(S.i(24), S.i(24)))
            row.addView(a.label(15f, C.TEXT, F.medium).apply { text = r.name }, lp(0, WRAP, 1f))
            row.addView(a.label(13f, C.MUTED, F.regular).apply { text = Fmt.time(r.time) }, lp(WRAP, WRAP))
            row.isClickable = true
            row.setOnClickListener { PhoneHub.dial(a, r.number) }
            view.addView(row, lp(MATCH, S.i(36)))
        }
        if (favs.isEmpty() && rec.isEmpty()) {
            view.addView(a.label(14f, C.MUTED, F.regular, lines = 3).apply {
                text = "Add favourite contacts with +. Calls use the head unit's Bluetooth phone; calls inside ZLink stay in ZLink."
            }, lp(MATCH, WRAP))
            if (!PhoneHub.granted(a, Manifest.permission.READ_CALL_LOG)) {
                view.addView(a.label(13f, C.TEXT3, F.semibold).apply {
                    text = "Show recent calls"; background = roundBg(C.CARD2, 12f, C.BORDER2); gravity = Gravity.CENTER; isClickable = true
                    setOnClickListener { PhoneHub.askAccess(a) }
                }, lp(WRAP, S.i(40)))
            }
        }
        view.addView(a.spacer(), lp(MATCH, 0, 1f))
    }

    override fun update() {
        if (PhoneHub.version == shown) return
        shown = PhoneHub.version
        call.showIf(PhoneHub.active)
        callName.put(PhoneHub.caller)
        callState.put(PhoneHub.state)
        answer.showIf(PhoneHub.incoming)
    }
}

/** Latest messages: tap to hear one, tap and hold to reply. */
class MessagesCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(8f)
    private val list = a.vbox(8f)
    private val empty: TextView = a.label(14f, C.MUTED, F.regular, lines = 4)
    private var shown = -1

    init {
        view.background = cardBg()
        view.pad(18f, 14f)
        val head = a.hbox(8f)
        head.addView(a.caption("Messages"), lp(0, WRAP, 1f))
        val mode = a.label(12f, C.MUTED, F.semibold, caps = true)
        mode.text = when (Prefs.readMsgs) { 0 -> "Reading off"; 1 -> "Read aloud while driving"; else -> "Read aloud" }
        head.addView(mode, lp(WRAP, WRAP))
        view.addView(head, lp(MATCH, WRAP))
        view.addView(list, lp(MATCH, 0, 1f))
        empty.text = "New messages from chat apps on this head unit show here and can be read aloud. Messages inside ZLink stay in ZLink."
        view.addView(empty, lp(MATCH, WRAP))
    }

    override fun update() {
        if (MessageHub.version == shown) return
        shown = MessageHub.version
        list.removeAllViews()
        val msgs = synchronized(MessageHub.recent) { MessageHub.recent.take(4) }
        empty.showIf(msgs.isEmpty())
        for (m in msgs) {
            val row = a.hbox(10f)
            row.background = Skin.tile(C.CARD2)
            row.pad(12f, 8f)
            row.addView(FolderIcon(a).apply { set(listOf(Apps.iconFor(a, m.pkg)), false) }, lp(S.i(32), S.i(32)))
            val col = a.vbox(1f)
            col.addView(a.label(15f, C.TEXT, F.semibold).apply { text = m.sender }, lp(MATCH, WRAP))
            col.addView(a.label(14f, C.TEXT2, F.regular).apply { text = m.text }, lp(MATCH, WRAP))
            row.addView(col, lp(0, WRAP, 1f))
            row.addView(a.label(12f, C.MUTED, F.regular).apply { text = Fmt.time(m.time) }, lp(WRAP, WRAP))
            row.isClickable = true
            row.contentDescription = "Message from ${m.sender}. Tap to hear it."
            row.setOnClickListener { Speaker.say(a, MessageHub.spoken(m)) }
            row.setOnLongClickListener { if (m.reply != null) Banners.quickReply(a, m) else Apps.launch(a, m.pkg); true }
            list.addView(row, lp(MATCH, WRAP))
        }
    }
}
