package com.obdix.app.core

import java.util.Locale

enum class DtcStatus(val label: String) {
    STORED("Mémorisé"), PENDING("En attente"), PERMANENT("Permanent")
}

data class Dtc(val code: String, val status: DtcStatus, val summary: String, val advice: String)
data class Measurement(val pid: Int, val label: String, val value: Double, val unit: String)

data class PidDefinition(
    val pid: Int,
    val label: String,
    val unit: String,
    val decode: (List<Int>) -> Double
)

object StandardPids {
    val catalog = listOf(
        PidDefinition(0x04, "Charge moteur", "%") { it[0] * 100.0 / 255.0 },
        PidDefinition(0x05, "Temp. liquide", "°C") { it[0].toDouble() - 40.0 },
        PidDefinition(0x0B, "Pression admission absolue", "kPa") { it[0].toDouble() },
        PidDefinition(0x0C, "Régime", "tr/min") { (it[0] * 256 + it[1]) / 4.0 },
        PidDefinition(0x0D, "Vitesse", "km/h") { it[0].toDouble() },
        PidDefinition(0x0F, "Temp. admission", "°C") { it[0].toDouble() - 40.0 },
        PidDefinition(0x10, "Débit d'air", "g/s") { (it[0] * 256 + it[1]) / 100.0 },
        PidDefinition(0x23, "Pression rail (jauge)", "bar") { (it[0] * 256 + it[1]) / 10.0 },
        PidDefinition(0x5C, "Temp. huile", "°C") { it[0].toDouble() - 40.0 },
        PidDefinition(0x59, "Pression rail absolue", "bar") { (it[0] * 256 + it[1]) / 10.0 }
    )
}

object TroubleCatalog {
    private val known = mapOf(
        "P0101" to ("Débitmètre : plage/performance" to "Contrôler admission, MAF et faisceau."),
        "P0087" to ("Pression carburant trop faible" to "Comparer pression demandée/réelle avec un outil constructeur."),
        "P0299" to ("Suralimentation insuffisante" to "Contrôler circuit de suralimentation, commande et fuites."),
        "P0401" to ("Débit EGR insuffisant" to "Contrôler EGR et circuit de recirculation."),
        "P0300" to ("Ratés d'allumage multiples" to "Contrôler allumage, carburant et compression.")
    )
    fun summary(code: String): String = known[code]?.first ?: "Défaut OBD-II : $code"
    fun advice(code: String): String = known[code]?.second ?: "Lire les données figées et consulter les informations constructeur."
}

/** Parse textual ELM replies without trusting unknown headers or human-readable status lines. */
object ObdParser {
    private val hexPair = Regex("[0-9A-F]{2}")
    private val canHeader = Regex("^([0-9A-F]{3}|[0-9A-F]{8})(?:\\s+|:)")

    fun frames(reply: String): List<List<Int>> = reply.uppercase(Locale.ROOT)
        .replace('>', ' ')
        .split('\r', '\n')
        .mapNotNull { original ->
            var line = original.trim()
            if (line.isBlank() || line.contains("SEARCHING") || line.contains("BUS INIT") ||
                line.contains("NO DATA") || line.contains("STOPPED") || line.contains("ERROR") ||
                line.contains("UNABLE") || line == "OK" || line == "?"
            ) return@mapNotNull null
            line = line.replace(canHeader, "")
            line = line.replace(Regex("^[0-9A-F]{1,2}:"), "")
            if (!line.matches(Regex("[0-9A-F\\s:]+"))) return@mapNotNull null
            val normalized = line.replace(Regex("[^0-9A-F]"), "")
            if (normalized.length < 2 || normalized.length % 2 != 0) return@mapNotNull null
            val bytes = hexPair.findAll(normalized).map { it.value.toInt(16) }.toList()
            if (bytes.isEmpty()) null else bytes
        }

    fun parsePid(reply: String, definition: PidDefinition): Measurement? {
        val required = if (definition.pid in setOf(0x0C, 0x10, 0x23, 0x59)) 2 else 1
        val bytes = frames(reply)
            .firstNotNullOfOrNull { frame ->
                val idx = frame.indices.firstOrNull {
                    it + 1 < frame.size && frame[it] == 0x41 && frame[it + 1] == definition.pid
                } ?: return@firstNotNullOfOrNull null
                frame.drop(idx + 2).takeIf { it.size >= required }
            } ?: return null
        return Measurement(definition.pid, definition.label, definition.decode(bytes), definition.unit)
    }

    fun supportedPids(reply: String, basePid: Int): Set<Int> {
        val index = frames(reply).firstNotNullOfOrNull { bytes ->
            val start = bytes.indices.firstOrNull {
                it + 5 < bytes.size && bytes[it] == 0x41 && bytes[it + 1] == basePid
            } ?: return@firstNotNullOfOrNull null
            bytes.drop(start + 2).take(4)
        } ?: return emptySet()
        return buildSet {
            for (bit in 0..31) {
                if (index[bit / 8] and (1 shl (7 - bit % 8)) != 0) add(basePid + bit + 1)
            }
        }
    }

    fun dtcs(reply: String, status: DtcStatus): List<Dtc> {
        val responseMode = when (status) {
            DtcStatus.STORED -> 0x43
            DtcStatus.PENDING -> 0x47
            DtcStatus.PERMANENT -> 0x4A
        }
        return frames(reply).flatMap { frame ->
            val start = frame.indexOf(responseMode)
            if (start < 0) return@flatMap emptyList()
            val payload = frame.drop(start + 1)
            payload.chunked(2).mapNotNull { pair ->
                if (pair.size != 2 || pair.all { it == 0 }) null
                else {
                    val code = dtcCode(pair[0], pair[1])
                    Dtc(code, status, TroubleCatalog.summary(code), TroubleCatalog.advice(code))
                }
            }
        }.distinctBy { it.code to it.status }
    }

    fun dtcCode(high: Int, low: Int): String {
        require(high in 0..255 && low in 0..255)
        val category = "PCBU"[high ushr 6]
        return "%c%d%X%X%X".format(
            Locale.ROOT, category, (high ushr 4) and 0x03, high and 0x0F,
            (low ushr 4) and 0x0F, low and 0x0F
        )
    }

    fun protocolName(raw: String): String = raw.trim().replace(">", "")
        .lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Non identifié"
}
