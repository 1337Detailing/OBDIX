package com.obdix.app.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodingGuardTest {
    private val operation = CodingOperation("FRM", "welcome_lights", "off", "on")
    private class FakeBackend : CodingBackend {
        var current = "off"
        val steps = mutableListOf<String>()
        override suspend fun readCurrent(ecu: String, key: String): String {
            steps += "read"
            return current
        }
        override suspend fun saveBackup(operation: CodingOperation) {
            steps += "backup"
        }
        override suspend fun write(operation: CodingOperation) {
            steps += "write"
            current = operation.newValue
        }
        override suspend fun verify(operation: CodingOperation): Boolean {
            steps += "verify"
            return current == operation.newValue
        }
    }

    @Test fun confirmedWriteAlwaysBacksUp() = runBlocking {
        val backend = FakeBackend()
        CodingGuard(backend).applyConfirmed(operation, confirmed = true)
        assertEquals(listOf("read", "backup", "write", "verify"), backend.steps)
    }

    @Test fun confirmationRequired() = runBlocking {
        val backend = FakeBackend()
        try {
            CodingGuard(backend).applyConfirmed(operation, confirmed = false)
            fail("Should reject a write without confirmation")
        } catch (_: IllegalArgumentException) {
            assertTrue(backend.steps.isEmpty())
        }
    }

    @Test fun staleOriginalMustAbortBeforeBackup() = runBlocking {
        val backend = FakeBackend().apply { current = "unknown" }
        try {
            CodingGuard(backend).applyConfirmed(operation, true)
            fail("Should abort on mismatched current value")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("read"), backend.steps)
        }
    }
}
