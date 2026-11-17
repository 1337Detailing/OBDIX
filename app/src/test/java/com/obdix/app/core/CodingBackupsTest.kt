package com.obdix.app.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Properties

class CodingBackupsTest {
    private class Fake : CodingBackend {
        var value = "aus"
        val steps = mutableListOf<String>()
        override suspend fun readCurrent(ecu: String, key: String): String {
            steps += "read"; return value
        }
        override suspend fun saveBackup(operation: CodingOperation) { steps += "backend-backup" }
        override suspend fun write(operation: CodingOperation) {
            steps += "write"; value = operation.newValue
        }
        override suspend fun verify(operation: CodingOperation): Boolean {
            steps += "verify"; return value == operation.newValue
        }
    }

    @Test fun snapshotsSurviveReload() {
        val folder = Files.createTempDirectory("obdix-backup").toFile()
        try {
            val store = CodingBackupStore(folder)
            val saved = store.save("WBATEST123", "FRM", "hardware-v1", "welcome", "aus", "ein")
            assertEquals("aus", CodingBackupStore(folder).load(saved.id).originalValue)
            assertTrue(folder.listFiles()!!.any { it.name.endsWith(".properties") })
        } finally { folder.deleteRecursively() }
    }

    @Test fun tamperingIsDetected() {
        val folder = Files.createTempDirectory("obdix-backup").toFile()
        try {
            val store = CodingBackupStore(folder)
            val saved = store.save("WBATEST123", "FRM", "hardware-v1", "welcome", "aus", "ein")
            val file = File(folder, "${saved.id}.properties")
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            properties.setProperty("original", "altered")
            file.outputStream().use { properties.store(it, "tampered") }
            try { store.load(saved.id); fail("Tampering should be detected") }
            catch (_: IllegalStateException) { /* deliberate */ }
        } finally { folder.deleteRecursively() }
    }

    @Test fun applyBacksUpBeforeWritingAndCanRestore() = runBlocking {
        val folder = Files.createTempDirectory("obdix-backup").toFile()
        try {
            val backend = Fake()
            val coordinator = DurableCodingCoordinator(backend, CodingBackupStore(folder))
            val operation = CodingOperation("FRM", "welcome", "aus", "ein")
            val saved = coordinator.apply("WBATEST123", "hw-v1", operation, true)
            assertEquals("ein", backend.value)
            assertEquals(listOf("read", "backend-backup", "write", "verify"), backend.steps)
            backend.steps.clear()
            coordinator.restore(saved.id, "WBATEST123", "hw-v1", true)
            assertEquals("aus", backend.value)
            assertEquals(listOf("read", "write", "verify"), backend.steps)
        } finally { folder.deleteRecursively() }
    }

    @Test fun staleValueAndWrongVehicleBlockRestore() = runBlocking {
        val folder = Files.createTempDirectory("obdix-backup").toFile()
        try {
            val backend = Fake()
            val coordinator = DurableCodingCoordinator(backend, CodingBackupStore(folder))
            val saved = coordinator.apply("WBATEST123", "hw-v1",
                CodingOperation("FRM", "welcome", "aus", "ein"), true)
            backend.steps.clear()
            try {
                coordinator.restore(saved.id, "DIFFERENTCAR", "hw-v1", true)
                fail("Wrong VIN must abort")
            } catch (_: IllegalStateException) { assertTrue(backend.steps.isEmpty()) }
            backend.value = "other"
            try {
                coordinator.restore(saved.id, "WBATEST123", "hw-v1", true)
                fail("Stale value must abort")
            } catch (_: IllegalStateException) { assertEquals(listOf("read"), backend.steps) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun rejectsUnsafeFilenames() {
        val folder = Files.createTempDirectory("obdix-backup").toFile()
        try {
            try {
                CodingBackupStore(folder).load("../escape")
                fail("Path traversal must be rejected")
            } catch (_: IllegalArgumentException) { /* deliberately rejected */ }
        } finally { folder.deleteRecursively() }
    }
}
