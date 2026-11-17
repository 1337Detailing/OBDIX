package com.obdix.app.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * An immutable, app-private snapshot. No ECU coding backend is bundled yet.
 * The fingerprint should represent an exact ECU identity/software version, not just its address.
 */
data class CodingSnapshot(
    val id: String,
    val vehicleId: String,
    val ecu: String,
    val ecuFingerprint: String,
    val key: String,
    val originalValue: String,
    val modifiedValue: String,
    val savedAtMillis: Long
)

class CodingBackupStore(private val directory: File) {
    init {
        require(directory.exists() && directory.isDirectory || directory.mkdirs()) {
            "Dossier de sauvegarde indisponible"
        }
    }

    @Synchronized
    fun save(
        vehicleId: String,
        ecu: String,
        ecuFingerprint: String,
        key: String,
        originalValue: String,
        modifiedValue: String
    ): CodingSnapshot {
        require(vehicleId.isNotBlank() && ecu.isNotBlank() && ecuFingerprint.isNotBlank() &&
            key.isNotBlank() && originalValue != modifiedValue) { "Sauvegarde invalide" }
        val snapshot = CodingSnapshot(
            id = UUID.randomUUID().toString().replace("-", ""),
            vehicleId = vehicleId, ecu = ecu, ecuFingerprint = ecuFingerprint, key = key,
            originalValue = originalValue, modifiedValue = modifiedValue,
            savedAtMillis = System.currentTimeMillis()
        )
        val properties = asProperties(snapshot)
        val temporary = File(directory, ".${snapshot.id}.tmp")
        val target = pathFor(snapshot.id)
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, "OBDIX immutable coding backup")
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
        return snapshot
    }

    @Synchronized
    fun load(id: String): CodingSnapshot {
        val path = pathFor(id)
        require(path.isFile) { "Sauvegarde absente" }
        val props = Properties().apply {
            path.inputStream().use { load(it) }
        }
        require(props.getProperty("schema") == "1") { "Format non pris en charge" }
        val snapshot = CodingSnapshot(
            id = props.getProperty("id") ?: error("ID manquant"),
            vehicleId = props.getProperty("vehicleId") ?: error("Véhicule manquant"),
            ecu = props.getProperty("ecu") ?: error("ECU manquant"),
            ecuFingerprint = props.getProperty("ecuFingerprint") ?: error("Version ECU manquante"),
            key = props.getProperty("key") ?: error("Clé manquante"),
            originalValue = props.getProperty("original") ?: error("Origine manquante"),
            modifiedValue = props.getProperty("modified") ?: error("Modification manquante"),
            savedAtMillis = props.getProperty("savedAt")?.toLongOrNull() ?: error("Date absente")
        )
        check(snapshot.id == id && props.getProperty("checksum") == digest(snapshot)) {
            "Sauvegarde corrompue ou altérée"
        }
        return snapshot
    }

    private fun pathFor(id: String): File {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Identifiant invalide" }
        return File(directory, "$id.properties")
    }

    private fun asProperties(snapshot: CodingSnapshot) = Properties().apply {
        setProperty("schema", "1")
        setProperty("id", snapshot.id)
        setProperty("vehicleId", snapshot.vehicleId)
        setProperty("ecu", snapshot.ecu)
        setProperty("ecuFingerprint", snapshot.ecuFingerprint)
        setProperty("key", snapshot.key)
        setProperty("original", snapshot.originalValue)
        setProperty("modified", snapshot.modifiedValue)
        setProperty("savedAt", snapshot.savedAtMillis.toString())
        setProperty("checksum", digest(snapshot))
    }

    private fun digest(snapshot: CodingSnapshot): String {
        val canonical = listOf(
            snapshot.id, snapshot.vehicleId, snapshot.ecu, snapshot.ecuFingerprint,
            snapshot.key, snapshot.originalValue, snapshot.modifiedValue,
            snapshot.savedAtMillis.toString()
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Candidate engine for a future ECU-specific backend. It does not make
 * an unvalidated coding job safe: the caller still needs verified hardware/definitions.
 */
class DurableCodingCoordinator(
    private val backend: CodingBackend,
    private val backups: CodingBackupStore
) {
    suspend fun apply(
        vehicleId: String,
        ecuFingerprint: String,
        operation: CodingOperation,
        confirmed: Boolean
    ): CodingSnapshot {
        require(confirmed) { "Confirmation explicite requise" }
        require(operation.ecu.isNotBlank() && operation.key.isNotBlank() &&
            operation.expectedOriginal != operation.newValue)
        check(backend.readCurrent(operation.ecu, operation.key) == operation.expectedOriginal) {
            "Valeur initiale différente : écriture bloquée"
        }
        val snapshot = backups.save(
            vehicleId, operation.ecu, ecuFingerprint, operation.key,
            operation.expectedOriginal, operation.newValue
        )
        backend.saveBackup(operation)
        backend.write(operation)
        check(backend.verify(operation)) { "Écriture non vérifiée : arrêt" }
        return snapshot
    }

    suspend fun restore(
        backupId: String,
        vehicleId: String,
        ecuFingerprint: String,
        confirmed: Boolean
    ) {
        require(confirmed) { "Confirmation de restauration requise" }
        val snapshot = backups.load(backupId)
        check(snapshot.vehicleId == vehicleId && snapshot.ecuFingerprint == ecuFingerprint) {
            "Véhicule ou version ECU incompatibles"
        }
        check(backend.readCurrent(snapshot.ecu, snapshot.key) == snapshot.modifiedValue) {
            "Le codage a changé depuis la sauvegarde"
        }
        val undo = CodingOperation(
            ecu = snapshot.ecu, key = snapshot.key,
            expectedOriginal = snapshot.modifiedValue, newValue = snapshot.originalValue
        )
        backend.write(undo)
        check(backend.verify(undo)) { "Restauration non vérifiée : arrêt" }
    }
}
