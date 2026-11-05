package app.drivelauncher

import java.util.Locale

/** Pure ELM327 / OBD-II parsing (no Android classes, unit-tested on the JVM). */
object ObdParse {
    fun hex2(v: Int): String = String.format(Locale.US, "%02X", v)

    /** Data bytes that follow "41 <pid>" in an ELM327 reply, or null if the car didn't answer. */
    fun parse(resp: String, pid: Int): IntArray? {
        val key = "41" + hex2(pid)
        val lines = resp.uppercase(Locale.US).split('\r', '\n')
            .map { it.replace(" ", "").replace("\t", "").trim() }
            .filter { it.isNotEmpty() }
        for (raw in lines) {
            // drop CAN frame prefixes like "0:" in multi-line replies
            val ln = if (raw.length > 2 && raw[1] == ':') raw.substring(2) else raw
            val idx = ln.indexOf(key)
            if (idx < 0) continue
            val hex = ln.substring(idx + 4)
            val n = hex.length / 2
            if (n == 0) continue
            val out = IntArray(n)
            for (k in 0 until n) out[k] = hex.substring(k * 2, k * 2 + 2).toIntOrNull(16) ?: return null
            return out
        }
        return null
    }

    /** Battery voltage from "ATRV" ("12.6V"). */
    fun volts(resp: String): Float? =
        Regex("(\\d{1,2}(?:\\.\\d{1,2})?)\\s*V").find(resp.uppercase(Locale.US))?.groupValues?.get(1)?.toFloatOrNull()

    /** PIDs flagged as supported by a 0100 / 0120 / 0140 reply. */
    fun supported(base: Int, b: IntArray): Set<Int> {
        val out = HashSet<Int>()
        for (byteIdx in 0 until minOf(4, b.size)) for (bit in 0 until 8)
            if ((b[byteIdx] shr (7 - bit)) and 1 == 1) out.add(base + byteIdx * 8 + bit + 1)
        return out
    }

    /** Converts raw bytes into (name, value). */
    fun decode(pid: Int, b: IntArray): Pair<String, Float>? {
        if (b.isEmpty()) return null
        val a = b[0].toFloat()
        val ab = if (b.size >= 2) (b[0] * 256 + b[1]).toFloat() else Float.NaN
        return when (pid) {
            0x0C -> if (ab.isNaN()) null else "rpm" to ab / 4f
            0x0D -> "speed" to a
            0x05 -> "coolant" to a - 40f
            0x0F -> "iat" to a - 40f
            0x11 -> "throttle" to a * 100f / 255f
            0x04 -> "load" to a * 100f / 255f
            0x0B -> "map" to a
            0x10 -> if (ab.isNaN()) null else "maf" to ab / 100f
            0x42 -> if (ab.isNaN()) null else "volt" to ab / 1000f
            0x2F -> "fuel" to a * 100f / 255f
            0x5C -> "oil" to a - 40f
            else -> null
        }
    }

    fun isNoData(resp: String): Boolean {
        val u = resp.uppercase(Locale.US)
        return u.contains("NO DATA") || u.contains("UNABLE TO CONNECT") || u.contains("CAN ERROR") ||
            u.contains("BUS INIT") && u.contains("ERROR") || u.contains("STOPPED")
    }

    /** "P0133" etc. from two raw DTC bytes. */
    fun dtcName(a: Int, b: Int): String =
        "PCBU"[(a shr 6) and 3].toString() + ((a shr 4) and 3) + Integer.toHexString(a and 0xF).uppercase(Locale.US) + hex2(b)

    /**
     * Trouble codes from a mode 03 (stored, service 0x43) or 07 (pending, 0x47) reply.
     * Handles CAN (count byte, multi-frame "0:" lines) and older protocols (3 codes per line, 0000 padding).
     */
    fun dtcs(resp: String, service: Int, can: Boolean?): List<String> {
        val key = hex2(service)
        val raw = resp.uppercase(Locale.US).split('\r', '\n')
            .map { it.replace(" ", "").replace("\t", "").trim() }
            .filter { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch in 'A'..'F' || ch == ':' } }
        val messages = ArrayList<String>()
        val multi = StringBuilder()
        for (ln in raw) {
            when {
                ln.length <= 3 && !ln.contains(':') -> {} // CAN multi-frame byte count, e.g. "00A"
                ln.length > 2 && ln[1] == ':' -> multi.append(ln.substring(2))
                else -> messages.add(ln)
            }
        }
        if (multi.isNotEmpty()) messages.add(0, multi.toString())
        val out = LinkedHashSet<String>()
        for (m in messages) {
            val idx = m.indexOf(key)
            if (idx < 0) continue
            val hex = m.substring(idx + 2)
            val bytes = ArrayList<Int>()
            var i = 0
            while (i + 1 < hex.length) { hex.substring(i, i + 2).toIntOrNull(16)?.let { bytes.add(it) }; i += 2 }
            var start = 0
            val isCan = can ?: (bytes.isNotEmpty() && bytes.size - 1 >= bytes[0] * 2 && (bytes.size - 1) % 2 == 0)
            var count = Int.MAX_VALUE
            if (isCan && bytes.isNotEmpty()) { count = bytes[0]; start = 1 }
            var k = start
            var n = 0
            while (k + 1 < bytes.size && n < count) {
                val a = bytes[k]; val b = bytes[k + 1]
                if (a != 0 || b != 0) out.add(dtcName(a, b))
                k += 2; n++
            }
        }
        return out.toList()
    }

    /** Check-engine light and number of stored codes from a 0101 reply. */
    fun milStatus(resp: String): Pair<Boolean, Int>? {
        val b = parse(resp, 0x01) ?: return null
        return ((b[0] and 0x80) != 0) to (b[0] and 0x7F)
    }
}
