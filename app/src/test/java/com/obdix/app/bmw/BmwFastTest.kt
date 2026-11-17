package com.obdix.app.bmw

import com.obdix.app.transport.ByteTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BmwFastTest {
    @Test fun encodeAndRoundTrip() {
        val packet = BmwFastPacket(0x12, 0xF1, listOf(0x1A, 0x80))
        val raw = BmwFastCodec.encode(packet)
        assertEquals(packet, BmwFastCodec.parse(raw))
        assertEquals(raw.size, BmwFastCodec.expectedLength(raw.map { it.toInt() and 255 }))
        assertEquals(raw.last().toInt() and 255,
            BmwFastCodec.checksum(raw.dropLast(1).map { it.toInt() and 255 }))
    }

    @Test fun checksumFailureRejects() {
        val raw = BmwFastCodec.encode(BmwFastPacket(0x12, 0xF1, listOf(0x1A, 0x80)))
        raw[4] = 0x81.toByte()
        assertNull(BmwFastCodec.parse(raw))
    }

    @Test fun variableLengthHeaders() {
        for (length in listOf(1, 63, 64, 255, 256, 1024)) {
            val packet = BmwFastPacket(0xF1, 0x12, List(length) { it and 255 })
            assertEquals(packet, BmwFastCodec.parse(BmwFastCodec.encode(packet)))
        }
    }

    @Test fun kwpFaultDecodingAndStatuses() {
        val reports = BmwFaultCodec.decode(listOf(
            0x58, 0x03, 0x4B, 0xC2, 0x60,
            0x45, 0x60, 0x20, 0x12, 0x34, 0x40
        ))!!
        assertEquals(3, reports.size)
        assertEquals("4BC2", reports[0].code)
        assertEquals("Actif", reports[0].state)
        assertEquals("Mémorisé", reports[1].state)
        assertEquals("Intermittent", reports[2].state)
        assertNull(BmwFaultCodec.decode(listOf(0x58, 0x02, 0x4B, 0xC2)))
        assertNull(BmwFaultCodec.decode(listOf(0x59, 0x00)))
        assertEquals(emptyList<BmwFault>(), BmwFaultCodec.decode(listOf(0x58, 0x00)))
    }

    private class Stub(private val answer: ByteArray) : ByteTransport {
        val sent = mutableListOf<ByteArray>()
        private val inbox = ArrayDeque<ByteArray>()
        override suspend fun send(bytes: ByteArray) {
            sent += bytes
            val stream = bytes + answer
            inbox.add(stream.take(3).toByteArray())
            inbox.add(stream.drop(3).take(4).toByteArray())
            inbox.add(stream.drop(7).toByteArray())
        }
        override suspend fun receive(): ByteArray = if (inbox.isEmpty()) byteArrayOf() else inbox.removeFirst()
        override suspend fun close() = Unit
    }

    @Test fun identifyIgnoresEcho() = runBlocking {
        val reply = BmwFastCodec.encode(BmwFastPacket(0xF1, 0x12, listOf(0x5A, 0x80, 0x01)))
        val transport = Stub(reply)
        val result = BmwKdcSession(transport).identify(0x12, "DDE / moteur")
        assertEquals(BmwResponseState.POSITIVE, result.status)
        assertEquals("5A 80 01", result.responseHex)
    }

    @Test fun readsKwpFaultMemoryWithoutWriting() = runBlocking {
        val reply = BmwFastCodec.encode(BmwFastPacket(0xF1, 0x12,
            listOf(0x58, 0x01, 0x4B, 0xC2, 0x60)))
        val transport = Stub(reply)
        val result = BmwKdcSession(transport).readFaults(0x12, "DDE / moteur")
        assertEquals(BmwResponseState.POSITIVE, result.status)
        assertEquals("4BC2", result.faults.single().code)
        val sentPacket = BmwFastCodec.parse(transport.sent.single())!!
        assertEquals(listOf(0x18, 0x02, 0xFF, 0xFF), sentPacket.payload)
    }

    @Test fun negativeResponsesNotDisplayedAsFaults() = runBlocking {
        val reply = BmwFastCodec.encode(BmwFastPacket(0xF1, 0x12,
            listOf(0x7F, 0x18, 0x11)))
        val result = BmwKdcSession(Stub(reply)).readFaults(0x12, "DDE / moteur")
        assertEquals(BmwResponseState.NEGATIVE, result.status)
        assertTrue(result.faults.isEmpty())
    }

    @Test fun unknownAddressCannotTransmit() = runBlocking {
        val transport = Stub(byteArrayOf())
        try {
            BmwKdcSession(transport).readFaults(0xFF, "unknown")
            fail("Unknown address must fail")
        } catch (_: IllegalArgumentException) {
            assertTrue(transport.sent.isEmpty())
        }
    }
}
