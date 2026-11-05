package app.drivelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CarLogicTest {
    @Test fun sunBangkokNovember() {
        val t = Sun.utcMinutes(13.7563, 100.5018, 310)!!
        // about 06:14 and 17:49 Bangkok time (UTC+7)
        assertEquals(-46.0, t.first, 6.0)
        assertEquals(649.0, t.second, 6.0)
    }

    @Test fun sunLondonMidsummer() {
        val t = Sun.utcMinutes(51.5, -0.12, 172)!!
        assertEquals(3.71 * 60, t.first, 6.0)
        assertEquals(20.35 * 60, t.second, 6.0)
    }

    @Test fun isDayBangkok() {
        val tz = TimeZone.getTimeZone("Asia/Bangkok")
        // 2026-11-06 12:00 and 22:00 local
        assertTrue(Sun.isDay(13.75, 100.5, 1_793_941_200_000L, tz))
        assertTrue(!Sun.isDay(13.75, 100.5, 1_793_977_200_000L, tz))
    }

    @Test fun tripDetector() {
        val d = TripDetector(startKmh = 8f, stopMs = 180_000L)
        assertEquals(0, d.update(0, 5f))
        assertEquals(1, d.update(1000, 20f))
        assertEquals(0, d.update(60_000, 0f))       // stopped at a light
        assertEquals(0, d.update(120_000, 30f))     // moving again
        assertEquals(0, d.update(200_000, 0f))
        assertEquals(2, d.update(300_001, 0f))      // 3 minutes still → trip ends
        assertEquals(120_000L, d.endedAt)
        assertTrue(!d.active)
    }

    @Test fun fuelAverage() {
        val e = listOf(
            FuelEntry(1, 40.0, 1400.0, 10_000.0, true),
            FuelEntry(2, 20.0, 700.0, 10_300.0, false),
            FuelEntry(3, 25.0, 900.0, 10_600.0, true),
        )
        assertEquals(45.0 / 600.0 * 100.0, FuelStats.avgL100(e)!!, 1e-9)
        assertEquals(1600.0 / 600.0, FuelStats.costPerKm(e)!!, 1e-9)
        assertNull(FuelStats.avgL100(e.take(2)))
    }

    @Test fun serviceState() {
        val now = 1_800_000_000_000L
        val i = ServiceItem("Oil", 10_000, 0, 50_000.0, now)
        assertEquals(0, i.state(55_000.0, now))
        assertEquals(1, i.state(59_700.0, now))
        assertEquals(2, i.state(60_100.0, now))
    }

    @Test fun dtcDescriptions() {
        assertEquals("Oxygen sensor slow response (bank 1, sensor 1)", DtcInfo.describe("P0133"))
        assertEquals("Ignition system or misfire", DtcInfo.describe("P0399"))
        assertEquals("Maker-specific engine / transmission code", DtcInfo.describe("P1326"))
    }
}
