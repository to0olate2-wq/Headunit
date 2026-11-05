package app.drivelauncher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

interface ObdLink {
    val input: InputStream
    val output: OutputStream
    fun close()
}

class BtLink(private val s: BluetoothSocket) : ObdLink {
    override val input: InputStream get() = s.inputStream
    override val output: OutputStream get() = s.outputStream
    override fun close() = s.close()

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @SuppressLint("MissingPermission")
        fun connect(d: BluetoothDevice): BtLink {
            val attempts = listOf<() -> BluetoothSocket>(
                { d.createRfcommSocketToServiceRecord(SPP) },
                { d.createInsecureRfcommSocketToServiceRecord(SPP) },
                { d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(d, 1) as BluetoothSocket },
            )
            var last: Exception? = null
            for (a in attempts) {
                var s: BluetoothSocket? = null
                try {
                    s = a()
                    s.connect()
                    return BtLink(s)
                } catch (e: Exception) {
                    last = e
                    try { s?.close() } catch (_: Exception) {}
                }
            }
            throw IOException("Bluetooth connection failed (${last?.message ?: "unknown"})")
        }
    }
}

class WifiLink(host: String, port: Int) : ObdLink {
    private val s = Socket()
    init {
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 3000
    }
    override val input: InputStream get() = s.getInputStream()
    override val output: OutputStream get() = s.getOutputStream()
    override fun close() = s.close()
}

/** Talks to an ELM327 adapter: sends a command, reads until the '>' prompt. */
class Elm(private val l: ObdLink) {
    private val sb = StringBuilder()
    private var silent = 0

    fun cmd(c: String, timeoutMs: Long): String {
        val ins = l.input
        while (ins.available() > 0) ins.read()
        l.output.write((c + "\r").toByteArray())
        l.output.flush()
        sb.setLength(0)
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (ins.available() > 0) {
                val ch = ins.read()
                if (ch < 0) throw IOException("Adapter closed the connection")
                if (ch == '>'.code) { silent = 0; return sb.toString() }
                sb.append(ch.toChar())
            } else {
                Thread.sleep(8)
            }
        }
        if (sb.isBlank()) {
            silent++
            if (silent >= 8) throw IOException("Adapter stopped responding")
        }
        return sb.toString()
    }
}

/**
 * OBD-II over a Bluetooth (or Wi-Fi) ELM327 adapter. Runs on its own thread,
 * reconnects by itself, and only reports the readings this car supports.
 */
object Obd {
    enum class State { OFF, NO_PERMISSION, NO_ADAPTER, CONNECTING, NO_ECU, CONNECTED, ERROR }

    @Volatile var state = State.OFF
    @Volatile var detail = "Not started"
    @Volatile var adapterName = ""
    val values = ConcurrentHashMap<String, Float>()
    val supported: MutableSet<Int> = Collections.synchronizedSet(HashSet())
    @Volatile var fuelLitres = 0.0
    @Volatile var fuelKm = 0.0

    // check-engine codes
    @Volatile var mil: Boolean? = null
    @Volatile var dtcStored: List<String> = emptyList()
    @Volatile var dtcPending: List<String> = emptyList()
    @Volatile var dtcStatus = "Not read yet"
    @Volatile var dtcAt = 0L
    @Volatile private var dtcRequest = 0 // 1 read, 2 clear
    @Volatile var version = 0
    private var isCan: Boolean? = null

    fun requestReadCodes() { dtcRequest = 1; dtcStatus = "Reading…"; version++ }
    fun requestClearCodes() { dtcRequest = 2; dtcStatus = "Clearing…"; version++ }

    private var app: Context? = null
    @Volatile private var running = false
    @Volatile private var link: ObdLink? = null
    private var thread: Thread? = null
    private val NAME = Regex("OBD|ELM|V-?LINK|VGATE|ICAR|KONNWEI|VIECAR|VEEPEAK|CAR ?SCANNER|OBDLINK|SCAN ?TOOL|KW9|LELINK|BAFX|NEXAS", RegexOption.IGNORE_CASE)

    val connected: Boolean get() = state == State.CONNECTED
    fun v(k: String): Float? = if (connected) values[k] else null

    /** Trip fuel economy in L/100 km (needs the MAF reading). */
    fun economy(): Float? = if (fuelKm > 0.5) (fuelLitres / fuelKm * 100.0).toFloat() else null
    fun resetEconomy() { fuelLitres = 0.0; fuelKm = 0.0 }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (running) return
        running = true
        thread = Thread({ loop() }, "obd").apply { isDaemon = true; start() }
    }

    fun restart(ctx: Context) {
        stop()
        start(ctx)
    }

    fun stop() {
        running = false
        try { link?.close() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
        state = State.OFF
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun loop() {
        var backoff = 3000L
        while (running) {
            val mode = try { Prefs.obdMode } catch (_: Exception) { "auto" }
            if (mode == "off") {
                state = State.OFF; detail = "Turned off in settings"
                sleep(3000); continue
            }
            var l: ObdLink? = null
            try {
                l = open(mode)
                if (l != null) {
                    link = l
                    backoff = 3000L
                    session(l)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                state = State.ERROR
                detail = e.message ?: e.javaClass.simpleName
                Diag.log("obd: $detail")
            } finally {
                try { l?.close() } catch (_: Exception) {}
                link = null
                if (state == State.CONNECTED) state = State.ERROR
            }
            if (!running) break
            sleep(backoff)
            backoff = min(backoff * 2, 30_000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(mode: String): ObdLink? {
        val ctx = app ?: return null
        if (mode == "wifi") {
            val hp = Prefs.obdHost.split(":")
            val host = hp[0]
            val port = hp.getOrNull(1)?.toIntOrNull() ?: 35000
            adapterName = "Wi-Fi $host:$port"
            state = State.CONNECTING; detail = "Connecting to $adapterName"
            return WifiLink(host, port)
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            state = State.NO_PERMISSION; detail = "Bluetooth permission needed"
            return null
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            state = State.NO_ADAPTER; detail = "This unit's Bluetooth isn't available to apps. A Wi-Fi OBD2 adapter will work instead."
            return null
        }
        if (!adapter.isEnabled) {
            state = State.NO_ADAPTER; detail = "Bluetooth is off"
            return null
        }
        val bonded = try { adapter.bondedDevices ?: emptySet() } catch (_: Exception) { emptySet<BluetoothDevice>() }
        val addr = Prefs.obdAddr
        val dev = if (mode == "bt" && addr.isNotEmpty()) bonded.firstOrNull { it.address == addr }
        else bonded.firstOrNull { NAME.containsMatchIn(it.name ?: "") }
        if (dev == null) {
            state = State.NO_ADAPTER
            detail = if (bonded.isEmpty()) "No paired Bluetooth devices. Pair your OBD2 adapter (PIN 1234 or 0000) in Bluetooth settings."
            else "No OBD2 adapter among paired devices. Pair it, or pick it under OBD adapter below."
            return null
        }
        adapterName = dev.name ?: dev.address
        state = State.CONNECTING; detail = "Connecting to $adapterName"
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        return BtLink.connect(dev)
    }

    private fun session(l: ObdLink) {
        val io = Elm(l)
        io.cmd("ATZ", 4000)
        sleep(400)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) io.cmd(c, 2000)
        detail = "Adapter ready, talking to the car…"
        supported.clear()
        var ok = false
        for (attempt in 0 until 3) {
            val r = io.cmd("0100", 9000)
            val b = ObdParse.parse(r, 0x00)
            if (b != null && b.size >= 4) { supported.addAll(ObdParse.supported(0x00, b)); ok = true; break }
            state = State.NO_ECU; detail = "Adapter connected, car not answering. Is the ignition on?"
            sleep(3000)
            if (!running) return
        }
        if (!ok) throw IOException("Car not answering (ignition off?)")
        if (0x20 in supported) ObdParse.parse(io.cmd("0120", 3000), 0x20)?.let { supported.addAll(ObdParse.supported(0x20, it)) }
        if (0x40 in supported) ObdParse.parse(io.cmd("0140", 3000), 0x40)?.let { supported.addAll(ObdParse.supported(0x40, it)) }
        isCan = io.cmd("ATDPN", 2000).uppercase().filter { it.isLetterOrDigit() }.let { p ->
            val d = p.lastOrNull()
            if (d == null) null else d in '6'..'9'
        }
        val fast = listOf(0x0C, 0x0D).filter { it in supported }
        val slow = listOf(0x05, 0x11, 0x04, 0x0B, 0x10, 0x0F, 0x42, 0x2F, 0x5C).filter { it in supported }
        Diag.log("obd connected to $adapterName, car supports: " + supported.sorted().joinToString(" ") { ObdParse.hex2(it) })
        state = State.CONNECTED
        detail = "Connected"
        var i = 0
        var lastVolt = 0L
        var lastMil = 0L
        var lastTick = SystemClock.elapsedRealtime()
        readCodes(io)
        while (running) {
            when (dtcRequest) {
                1 -> { dtcRequest = 0; readCodes(io) }
                2 -> {
                    dtcRequest = 0
                    val r = io.cmd("04", 5000)
                    dtcStatus = if (r.uppercase().contains("44")) "Codes cleared" else "The car did not accept the clear request"
                    Diag.log("obd clear codes: ${r.trim()}")
                    readCodes(io)
                }
            }
            if (SystemClock.elapsedRealtime() - lastMil > 30_000) {
                ObdParse.milStatus(io.cmd("0101", 2000))?.let { mil = it.first }
                lastMil = SystemClock.elapsedRealtime()
                version++
            }
            for (p in fast) query(io, p)
            if (slow.isNotEmpty()) { query(io, slow[i % slow.size]); i++ }
            val now = SystemClock.elapsedRealtime()
            if (0x42 !in supported && now - lastVolt > 5000) {
                ObdParse.volts(io.cmd("ATRV", 2000))?.let { values["volt"] = it }
                lastVolt = now
            }
            val dt = (now - lastTick) / 1000.0
            lastTick = now
            val maf = values["maf"]
            val spd = values["speed"]
            if (maf != null && dt in 0.0..5.0) {
                fuelLitres += maf * dt / (14.7 * 745.0)
                if (spd != null) fuelKm += spd * dt / 3600.0
            }
            if (fast.isEmpty() && slow.isEmpty()) sleep(1000)
        }
    }

    private fun readCodes(io: Elm) {
        try {
            ObdParse.milStatus(io.cmd("0101", 2000))?.let { mil = it.first }
            dtcStored = ObdParse.dtcs(io.cmd("03", 5000), 0x43, isCan)
            dtcPending = ObdParse.dtcs(io.cmd("07", 5000), 0x47, isCan)
            dtcAt = System.currentTimeMillis()
            dtcStatus = if (dtcStored.isEmpty() && dtcPending.isEmpty()) "No trouble codes" else
                "${dtcStored.size} stored, ${dtcPending.size} pending"
            Diag.log("obd codes: stored=$dtcStored pending=$dtcPending mil=$mil")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            dtcStatus = "Couldn't read codes: ${e.message}"
        }
        version++
    }

    private fun query(io: Elm, pid: Int) {
        val r = io.cmd("01" + ObdParse.hex2(pid), 2000)
        val b = ObdParse.parse(r, pid) ?: return
        ObdParse.decode(pid, b)?.let { (k, value) -> values[k] = value }
    }

    fun describe(): String {
        val sb = StringBuilder()
        sb.append("OBD: ").append(state).append(" — ").append(detail)
        if (adapterName.isNotEmpty()) sb.append(" (").append(adapterName).append(")")
        if (supported.isNotEmpty()) sb.append("\nSupported PIDs: ").append(supported.sorted().joinToString(" ") { ObdParse.hex2(it) })
        if (values.isNotEmpty()) sb.append("\nValues: ").append(values.entries.joinToString(", ") { "${it.key}=${fmt("%.1f", it.value)}" })
        return sb.toString()
    }
}
