package com.obdix.app.core

interface VehicleGateway {
    val name: String
    val supportsEcuScan: Boolean
    val supportsServiceReset: Boolean
    val supportsCoding: Boolean
}

/** SAE J1979 emissions diagnostics; not a substitute for manufacturer-specific diagnostics. */
object GenericObdGateway : VehicleGateway {
    override val name = "OBD-II générique"
    override val supportsEcuScan = false
    override val supportsServiceReset = false
    override val supportsCoding = false
}

object BmwEseriesGateway : VehicleGateway {
    override val name = "BMW E-Series"
    override val supportsEcuScan = false
    override val supportsServiceReset = false
    override val supportsCoding = false
}

data class CodingOperation(val ecu: String, val key: String, val expectedOriginal: String, val newValue: String)
interface CodingBackend {
    suspend fun readCurrent(ecu: String, key: String): String
    suspend fun saveBackup(operation: CodingOperation)
    suspend fun write(operation: CodingOperation)
    suspend fun verify(operation: CodingOperation): Boolean
}

/** The guard prevents silent overwrites and non-verified writes; no BMW backend is bundled. */
class CodingGuard(private val backend: CodingBackend) {
    suspend fun applyConfirmed(operation: CodingOperation, confirmed: Boolean) {
        require(confirmed) { "Confirmation explicite requise" }
        require(operation.ecu.isNotBlank() && operation.key.isNotBlank())
        require(operation.newValue != operation.expectedOriginal) { "Aucune modification" }
        val current = backend.readCurrent(operation.ecu, operation.key)
        check(current == operation.expectedOriginal) { "Valeur d'origine différente : abandon" }
        backend.saveBackup(operation)
        backend.write(operation)
        check(backend.verify(operation)) { "Vérification après écriture impossible" }
    }
}
