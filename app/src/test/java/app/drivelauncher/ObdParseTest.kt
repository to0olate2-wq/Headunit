package app.drivelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParseTest {
    @Test fun rpm() = assertEquals("rpm" to 1726f, ObdParse.decode(0x0C, ObdParse.parse("410C1AF8\r\r", 0x0C)!!))

    @Test fun speedWithEchoAndSearching() =
        assertEquals("speed" to 60f, ObdParse.decode(0x0D, ObdParse.parse("010D\rSEARCHING...\r41 0D 3C \r\r", 0x0D)!!))

    @Test fun noData() {
        assertNull(ObdParse.parse("NO DATA\r", 0x0C))
        assertTrue(ObdParse.isNoData("NO DATA"))
    }

    @Test fun supportedPids() = assertEquals(
        setOf(1, 3, 4, 5, 6, 7, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
        ObdParse.supported(0, ObdParse.parse("4100BE3FA813", 0x00)!!),
    )

    @Test fun volts() {
        assertEquals(12.6f, ObdParse.volts("12.6V\r"))
        assertEquals(14f, ObdParse.volts("14V"))
    }

    @Test fun canFramePrefix() = assertEquals(4, ObdParse.parse("0: 41 00 BE 3F A8 13", 0x00)!!.size)

    @Test fun maf() = assertEquals("maf" to 13.05f, ObdParse.decode(0x10, ObdParse.parse("41100519", 0x10)!!))

    @Test fun dtcCanSingleFrame() = assertEquals(listOf("P0133", "P0171"), ObdParse.dtcs("43 02 01 33 01 71 \r\r", 0x43, true))

    @Test fun dtcCanMultiFrame() = assertEquals(
        listOf("P0133", "P0171", "P0420", "C0035"),
        ObdParse.dtcs("00C\r0: 43 04 01 33 01 71\r1: 04 20 40 35 00 00\r", 0x43, true),
    )

    @Test fun dtcOldProtocolPadding() = assertEquals(listOf("P0300", "U0100"), ObdParse.dtcs("43 03 00 C1 00 00 00\r", 0x43, false))

    @Test fun dtcNone() {
        assertEquals(emptyList<String>(), ObdParse.dtcs("43 00\r", 0x43, true))
        assertEquals(emptyList<String>(), ObdParse.dtcs("NO DATA\r", 0x43, null))
    }

    @Test fun dtcPendingAutoDetect() = assertEquals(listOf("P0442"), ObdParse.dtcs("47 01 04 42\r", 0x47, null))

    @Test fun mil() {
        assertEquals(true to 2, ObdParse.milStatus("41 01 82 07 E5 00"))
        assertEquals(false to 0, ObdParse.milStatus("41 01 00 07 E5 00"))
    }
}
