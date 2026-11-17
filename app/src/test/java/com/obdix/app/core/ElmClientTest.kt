package com.obdix.app.core

import com.obdix.app.transport.ByteTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmClientTest {
    private class FakeTransport : ByteTransport {
        val commands = mutableListOf<String>()
        var closed = false
        private var pending = ByteArray(0)
        override suspend fun send(bytes: ByteArray) {
            val command = bytes.decodeToString().trim()
            commands += command
            pending = when (command) {
                "ATZ" -> "ELM327 v1.5\r>".toByteArray()
                "ATI" -> "ELM327 v1.5\r>".toByteArray()
                "ATDP" -> "AUTO, ISO 15765-4 CAN\r>".toByteArray()
                "010C" -> "41 0C 1A F8\r>".toByteArray()
                "04" -> "44\r>".toByteArray()
                else -> "OK\r>".toByteArray()
            }
        }
        override suspend fun receive(): ByteArray = pending.also { pending = ByteArray(0) }
        override suspend fun close() { closed = true }
    }
    @Test fun startupAndRead() = runBlocking {
        val fake = FakeTransport()
        val client = ElmClient { fake }
        client.connect()
        assertEquals("ELM327 v1.5", client.adapter)
        assertTrue(fake.commands.contains("ATSP0"))
        val response = client.query("010C")
        assertEquals(1726.0, ObdParser.parsePid(response,
            StandardPids.catalog.first { it.pid == 0x0C })!!.value, 0.001)
        client.close()
        assertTrue(fake.closed)
    }

    @Test fun unknownWritesAreForbidden() = runBlocking {
        val client = ElmClient { FakeTransport() }
        try {
            client.query("2E1122")
            fail("Writing must be denied")
        } catch (_: IllegalArgumentException) {
            // The general diagnostic API must remain read-only.
        }
    }

    @Test fun clearRequiresSeparateEntryPoint() = runBlocking {
        val fake = FakeTransport()
        val client = ElmClient { fake }
        client.connect()
        assertEquals("44\r>", client.clearEmissionsConfirmed())
        assertEquals(1, fake.commands.count { it == "04" })
    }
}
