package com.obdix.app.core

import org.junit.Assert.*
import org.junit.Test

class ObdParserTest {
    @Test fun dtcFormats() {
        assertEquals("P0133", ObdParser.dtcCode(0x01, 0x33))
        assertEquals("C1234", ObdParser.dtcCode(0x52, 0x34))
        assertEquals("B2345", ObdParser.dtcCode(0xA3, 0x45))
        assertEquals("U0123", ObdParser.dtcCode(0xC1, 0x23))
    }

    @Test fun storedAndPending() {
        val stored = ObdParser.dtcs("43 01 01 00 00\r>", DtcStatus.STORED)
        assertEquals(1, stored.size)
        assertEquals("P0101", stored.first().code)
        assertEquals(DtcStatus.STORED, stored.first().status)
        val pending = ObdParser.dtcs("47 02 99\r>", DtcStatus.PENDING)
        assertEquals("P0299", pending.single().code)
    }

    @Test fun stripCanHeaderAndLengthByte() {
        val result = ObdParser.parsePid("SEARCHING...\r7E8 04 41 0C 1A F8\r>", 
            StandardPids.catalog.first { it.pid == 0x0C })
        assertNotNull(result)
        assertEquals(1726.0, result!!.value, 0.001)
    }

    @Test fun oilAndRail() {
        val oil = StandardPids.catalog.first { it.pid == 0x5C }
        val rail = StandardPids.catalog.first { it.pid == 0x23 }
        assertEquals(90.0, ObdParser.parsePid("41 5C 82\r>", oil)!!.value, 0.01)
        assertEquals(1000.0, ObdParser.parsePid("41 23 27 10\r>", rail)!!.value, 0.01)
    }

    @Test fun capabilities() {
        val supported = ObdParser.supportedPids("41 00 80 00 00 01\r>", 0)
        assertTrue(1 in supported)
        assertTrue(32 in supported)
        assertFalse(12 in supported)
    }

    @Test fun malformedResponseNotInvented() {
        val rpm = StandardPids.catalog.first { it.pid == 0x0C }
        assertNull(ObdParser.parsePid("NO DATA\r>", rpm))
        assertNull(ObdParser.parsePid("41 0C 1A\r>", rpm))
        assertTrue(ObdParser.dtcs("STOPPED\r>", DtcStatus.PERMANENT).isEmpty())
    }
}
