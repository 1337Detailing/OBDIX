package com.obdix.app.bmw

import com.obdix.app.transport.ByteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * BMW-FAST telegram framing on the UART of a compatible K+DCAN interface.
 * This is NOT EDIABAS/.PRG interpretation or ISO-TP over an ELM327.
 */
data class BmwFastPacket(val target: Int, val source: Int, val payload: List<Int>) {
    init {
        require(target in 0..255 && source in 0..255)
        require(payload.isNotEmpty() && payload.size <= 4095)
        require(payload.all { it in 0..255 })
    }
}

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
        var headSize = 3
        var payloadSize = u[0] and 0x3F
        if (payloadSize == 0) {
            if (u.size < 6) return null
            headSize = 4
            payloadSize = u[3]
            if (payloadSize == 0) {
                if (u.size < 8) return null
                headSize = 6
                payloadSize = (u[4] shl 8) or u[5]
            }
        }
        if (payloadSize !in 1..4095 || u.size != headSize + payloadSize + 1) return null
        if (checksum(u.dropLast(1)) != u.last()) return null
        return BmwFastPacket(u[1], u[2], u.subList(headSize, u.lastIndex))
    }

    /** Length with checksum, null if header is incomplete or malformed. */
    fun expectedLength(bytes: List<Int>): Int? {
        if (bytes.size < 3 || bytes[0] and 0xC0 != 0x80) return null
        val headerSize: Int
        val length: Int
        if (bytes[0] and 0x3F != 0) {
            headerSize = 3
            length = bytes[0] and 0x3F
        } else {
            if (bytes.size < 4) return null
            if (bytes[3] != 0) {
                headerSize = 4
                length = bytes[3]
            } else {
                if (bytes.size < 6) return null
                headerSize = 6
                length = bytes[4] * 256 + bytes[5]
            }
        }
        return (headerSize + length + 1).takeIf { length in 1..4095 }
    }
}

enum class BmwResponseState { POSITIVE, NEGATIVE, NO_REPLY, INVALID }

data class BmwEcuIdentity(
    val address: Int,
    val name: String,
    val status: BmwResponseState,
    val responseHex: String,
    val detail: String
)

object BmwE90Addresses {
    // Physical ECU diagnostic addresses used by BMW FAST on E-series.
    // Actual availability depends on equipment, gateway and vehicle generation.
    val targets = listOf(
        0x12 to "DDE / moteur",
        0x18 to "EGS / boîte",
        0x40 to "CAS",
        0x60 to "Combiné",
        0x72 to "FRM / éclairage"
    )
}

class BmwKdcSession(private val transport: ByteTransport) {
    private val mutex = Mutex()

    /**
     * Only 0x1A ReadECUIdentification is allowed.
     * The user-supplied ECU address must match the E90 allowlist.
     */
    suspend fun identify(address: Int, name: String): BmwEcuIdentity = mutex.withLock {
        require(BmwE90Addresses.targets.any { it.first == address && it.second == name }) {
            "Calculateur inconnu"
        }
        val request = BmwFastCodec.encode(BmwFastPacket(address, 0xF1, listOf(0x1A, 0x90)))
        val buffer = mutableListOf<Int>()
        try {
            transport.send(request)
            val response = withTimeout(4500) {
                while (true) {
                    val chunk = transport.receive()
                    chunk.forEach { buffer.add(it.toInt() and 0xFF) }
                    check(buffer.size <= 16384) { "Trame BMW trop longue" }
                    while (buffer.isNotEmpty()) {
                        if (buffer[0] and 0xC0 != 0x80) {
                            buffer.removeAt(0)
                            continue
                        }
                        val expected = BmwFastCodec.expectedLength(buffer) ?: break
                        if (buffer.size < expected) break
                        val packet = BmwFastCodec.parse(
                            buffer.take(expected).map { it.toByte() }.toByteArray()
                        )
                        repeat(expected) { buffer.removeAt(0) }
                        if (packet == null) continue
                        // K+DCAN adapters can locally echo the TX packet; ignore it.
                        if (packet.target != 0xF1 || packet.source != address) continue
                        if (packet.payload.size >= 3 &&
                            packet.payload[0] == 0x7F &&
                            packet.payload[1] == 0x1A &&
                            packet.payload[2] == 0x78
                        ) continue // responsePending, within the same overall deadline
                        return@withTimeout packet
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Inaccessible")
            }
            val hex = response.payload.joinToString(" ") { "%02X".format(it) }
            val negative = response.payload.size >= 3 &&
                response.payload[0] == 0x7F && response.payload[1] == 0x1A
            val positive = response.payload.firstOrNull() == 0x5A
            BmwEcuIdentity(
                address, name,
                when {
                    negative -> BmwResponseState.NEGATIVE
                    positive -> BmwResponseState.POSITIVE
                    else -> BmwResponseState.INVALID
                },
                hex,
                when {
                    negative -> "Réponse négative : NRC %02X".format(response.payload[2])
                    positive -> "Réponse positive d'identification"
                    else -> "Réponse non reconnue, sans interprétation"
                }
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            BmwEcuIdentity(
                address, name, BmwResponseState.NO_REPLY, "",
                "Aucune identification valide : ${error.message ?: "délai dépassé"}"
            )
        }
    }

    suspend fun close() = transport.close()
}
