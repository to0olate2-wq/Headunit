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
}
