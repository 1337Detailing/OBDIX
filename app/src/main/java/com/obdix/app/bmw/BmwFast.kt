package com.obdix.app.bmw

import com.obdix.app.transport.ByteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class BmwFastPacket(val target: Int, val source: Int, val payload: List<Int>) {
    init {
        require(target in 0..255 && source in 0..255)
        require(payload.isNotEmpty() && payload.size <= 4095)
        require(payload.all { it in 0..255 })
    }
}

/** Self-contained BMW-FAST UART telegram codec; no EDIABAS code is embedded. */
object BmwFastCodec {
    fun checksum(bytes: List<Int>): Int = bytes.sum().and(0xFF)

    fun encode(packet: BmwFastPacket): ByteArray {
        val size = packet.payload.size
        val bytes = when {
            size <= 63 -> mutableListOf(0x80 or size, packet.target, packet.source)
            size <= 255 -> mutableListOf(0x80, packet.target, packet.source, size)
            else -> mutableListOf(0x80, packet.target, packet.source, 0x00,
                size ushr 8, size and 0xFF)
        }
        bytes.addAll(packet.payload)
        bytes.add(checksum(bytes))
        return bytes.map { it.toByte() }.toByteArray()
    }

    fun parse(bytes: ByteArray): BmwFastPacket? {
        if (bytes.size < 5) return null
        val u = bytes.map { it.toInt() and 0xFF }
        if (u[0] and 0xC0 != 0x80) return null
        var headerSize = 3
        var size = u[0] and 0x3F
        if (size == 0) {
            if (u.size < 6) return null
            headerSize = 4
            size = u[3]
            if (size == 0) {
                if (u.size < 8) return null
                headerSize = 6
                size = (u[4] shl 8) or u[5]
            }
        }
        if (size !in 1..4095 || u.size != headerSize + size + 1) return null
        if (checksum(u.dropLast(1)) != u.last()) return null
        return BmwFastPacket(u[1], u[2], u.subList(headerSize, u.lastIndex))
    }

    fun expectedLength(bytes: List<Int>): Int? {
        if (bytes.size < 3 || bytes[0] and 0xC0 != 0x80) return null
        val headerSize: Int
        val size: Int
        if (bytes[0] and 0x3F != 0) {
            headerSize = 3
            size = bytes[0] and 0x3F
        } else {
            if (bytes.size < 4) return null
            if (bytes[3] != 0) {
                headerSize = 4
                size = bytes[3]
            } else {
                if (bytes.size < 6) return null
                headerSize = 6
                size = bytes[4] * 256 + bytes[5]
            }
        }
        return (headerSize + size + 1).takeIf { size in 1..4095 }
    }
}

enum class BmwResponseState { POSITIVE, NEGATIVE, NO_REPLY, INVALID }

data class BmwFault(val code: String, val statusByte: Int) {
    val state: String get() = when ((statusByte ushr 5) and 3) {
        3 -> "Actif"
        1 -> "Mémorisé"
        2 -> "Intermittent"
        else -> "État non déterminé"
    }
    val warningRequested: Boolean get() = statusByte and 0x80 != 0
}

object BmwFaultCodec {
    /**
     * KWP2000 ReadDTCByStatus (18 02 FF FF), response: 58, count, then
     * [2-byte manufacturer code, 1-byte status] repeated. Reject truncation.
     */
    fun decode(payload: List<Int>): List<BmwFault>? {
        if (payload.size < 2 || payload[0] != 0x58) return null
        val count = payload[1]
        if (count !in 0..100 || payload.size != 2 + count * 3) return null
        return (0 until count).map { i ->
            val offset = 2 + i * 3
            BmwFault("%04X".format((payload[offset] shl 8) or payload[offset + 1]),
                payload[offset + 2])
        }
    }
}

data class BmwEcuIdentity(
    val address: Int,
    val name: String,
    val status: BmwResponseState,
    val responseHex: String,
    val detail: String
)

data class BmwFaultReport(
    val address: Int,
    val name: String,
    val status: BmwResponseState,
    val faults: List<BmwFault>,
    val responseHex: String,
    val detail: String
)

object BmwE90Addresses {
    // This is a candidate scan list: absent equipment will not answer.
    val targets = listOf(
        0x12 to "DDE / moteur",
        0x18 to "EGS / boîte",
        0x29 to "DSC / ABS",
        0x40 to "CAS",
        0x60 to "Combiné",
        0x01 to "Airbags",
        0x00 to "JBBF",
        0x72 to "FRM / éclairage",
        0x78 to "Climatisation",
        0x17 to "Pompe carburant",
        0x64 to "Radar de recul",
        0x63 to "iDrive"
    )
}

class BmwKdcSession(private val transport: ByteTransport) {
    private val mutex = Mutex()
    private val readJobs = mapOf(
        ReadJob.IDENT to listOf(0x1A, 0x80),
        ReadJob.FAULTS to listOf(0x18, 0x02, 0xFF, 0xFF)
    )
    private enum class ReadJob { IDENT, FAULTS }
    private data class Reply(val payload: List<Int>?, val message: String)

    private suspend fun request(address: Int, name: String, job: ReadJob): Reply = mutex.withLock {
        require(BmwE90Addresses.targets.any { it.first == address && it.second == name }) {
            "Calculateur inconnu"
        }
        val data = checkNotNull(readJobs[job])
        val service = data[0]
        val request = BmwFastCodec.encode(BmwFastPacket(address, 0xF1, data))
        val accumulator = mutableListOf<Int>()
        try {
            transport.send(request)
            val result = withTimeout(5000) {
                while (true) {
                    val fragment = transport.receive()
                    if (fragment.isEmpty()) { delay(10); continue }
                    fragment.forEach { accumulator.add(it.toInt() and 0xFF) }
                    check(accumulator.size <= 16384) { "Trame hors limite" }
                    while (accumulator.isNotEmpty()) {
                        if (accumulator[0] and 0xC0 != 0x80) {
                            accumulator.removeAt(0)
                            continue
                        }
                        val expected = BmwFastCodec.expectedLength(accumulator) ?: break
                        if (accumulator.size < expected) break
                        val packet = BmwFastCodec.parse(
                            accumulator.take(expected).map { it.toByte() }.toByteArray()
                        )
                        repeat(expected) { accumulator.removeAt(0) }
                        if (packet == null || packet.target != 0xF1 ||
                            packet.source != address) continue
                        if (packet.payload.size >= 3 && packet.payload[0] == 0x7F &&
                            packet.payload[1] == service && packet.payload[2] == 0x78
                        ) continue
                        return@withTimeout packet.payload
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                emptyList<Int>()
            }
            Reply(result, "Réponse BMW-FAST reçue")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Reply(null, "Pas de réponse valide : ${error.message ?: "délai dépassé"}")
        }
    }

    suspend fun identify(address: Int, name: String): BmwEcuIdentity {
        val response = request(address, name, ReadJob.IDENT)
        val data = response.payload
        val positive = data != null && data.size >= 2 && data[0] == 0x5A && data[1] == 0x80
        val negative = data != null && data.size >= 3 && data[0] == 0x7F && data[1] == 0x1A
        return BmwEcuIdentity(
            address, name,
            when {
                data == null -> BmwResponseState.NO_REPLY
                positive -> BmwResponseState.POSITIVE
                negative -> BmwResponseState.NEGATIVE
                else -> BmwResponseState.INVALID
            },
            data?.joinToString(" ") { "%02X".format(it) }.orEmpty(),
            when {
                negative -> "Code de rejet : %02X".format(data!![2])
                positive -> "Identification reçue (données brutes)"
                else -> response.message
            }
        )
    }

    suspend fun readFaults(address: Int, name: String): BmwFaultReport {
        val response = request(address, name, ReadJob.FAULTS)
        val data = response.payload
        val decoded = if (data == null) null else BmwFaultCodec.decode(data)
        val negative = data != null && data.size >= 3 && data[0] == 0x7F && data[1] == 0x18
        val state = when {
            data == null -> BmwResponseState.NO_REPLY
            decoded != null -> BmwResponseState.POSITIVE
            negative -> BmwResponseState.NEGATIVE
            else -> BmwResponseState.INVALID
        }
        return BmwFaultReport(
            address, name, state, decoded.orEmpty(),
            data?.joinToString(" ") { "%02X".format(it) }.orEmpty(),
            when {
                decoded != null -> "${decoded.size} défaut(s) renvoyé(s)"
                negative -> "Rejet ECU : NRC %02X".format(data!![2])
                else -> response.message
            }
        )
    }

    suspend fun close() = mutex.withLock { transport.close() }
}
