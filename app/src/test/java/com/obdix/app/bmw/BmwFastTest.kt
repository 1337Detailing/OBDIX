package com.obdix.app.bmw

import com.obdix.app.transport.ByteTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BmwFastTest {
    @Test fun fastTelegramChecksumAndRoundTrip() {
        val source = BmwFastPacket(0x12, 0xF1, listOf(0x1A, 0x90))
        val encoded = BmwFastCodec.encode(source)
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0x12, 0xF1.toByte(), 0x1A, 0x90.toByte(),
                (0x82 + 0x12 + 0xF1 + 0x1A + 0x90).toByte()),
            encoded
        )
        assertEquals(source, BmwFastCodec.parse(encoded))
        assertEquals(encoded.size, BmwFastCodec.expectedLength(
            encoded.map { it.toInt() and 0xFF }))
    }

    @Test fun rejectsChecksumCorruption() {
        val original = BmwFastCodec.encode(BmwFastPacket(0x12, 0xF1, listOf(0x1A, 0x90)))
        original[4] = 0x91.toByte()
        assertNull(BmwFastCodec.parse(original))
        assertNull(BmwFastCodec.parse(byteArrayOf(0x01, 0x02)))
    }

    @Test fun extendedLengthFrames() {
        for (length in listOf(63, 64, 255, 256, 1024)) {
            val data = BmwFastPacket(0xF1, 0x12, List(length) { it.and(0xFF) })
            val encoded = BmwFastCodec.encode(data)
            assertEquals(data, BmwFastCodec.parse(encoded))
        }
    }

    private class TestTransport(private val reply: ByteArray) : ByteTransport {
        private val inbox = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        override suspend fun send(bytes: ByteArray) {
            sent += bytes
            val traffic = bytes + reply
            inbox.add(traffic.take(2).toByteArray())
            inbox.add(traffic.drop(2).take(3).toByteArray())
            inbox.add(traffic.drop(5).toByteArray())
        }
        override suspend fun receive(): ByteArray = if (inbox.isEmpty()) byteArrayOf()
            else inbox.removeFirst()
        override suspend fun close() = Unit
    }

    @Test fun ignoresAdapterEchoAndParsesTargetReply() = runBlocking {
        val response = BmwFastCodec.encode(BmwFastPacket(0xF1, 0x12, listOf(0x5A, 0x90, 0x01)))
        val transport = TestTransport(response)
        val client = BmwKdcSession(transport)
        val result = client.identify(0x12, "DDE / moteur")
        assertEquals(BmwResponseState.POSITIVE, result.status)
        assertEquals("5A 90 01", result.responseHex)
        assertEquals(1, transport.sent.size)
    }

    @Test fun negativeResponseIsReportedNotInvented() = runBlocking {
        val response = BmwFastCodec.encode(BmwFastPacket(0xF1, 0x12,
            listOf(0x7F, 0x1A, 0x11)))
        val transport = TestTransport(response)
        val result = BmwKdcSession(transport).identify(0x12, "DDE / moteur")
        assertEquals(BmwResponseState.NEGATIVE, result.status)
        assertTrue(result.detail.contains("11"))
    }

    @Test fun refusesUnknownEcuWithoutTransmitting() = runBlocking {
        val fake = TestTransport(byteArrayOf())
        try {
            BmwKdcSession(fake).identify(0x99, "Suspect")
            fail("Should reject unexpected address")
        } catch (_: IllegalArgumentException) {
            assertTrue(fake.sent.isEmpty())
        }
    }
}
