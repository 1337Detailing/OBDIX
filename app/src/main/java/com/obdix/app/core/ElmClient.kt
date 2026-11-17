package com.obdix.app.core

import com.obdix.app.transport.ByteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class ElmError(message: String) : Exception(message)

class ElmClient(private val openTransport: suspend () -> ByteTransport) {
    private val lock = Mutex()
    private var transport: ByteTransport? = null
    var protocol: String = "Inconnu"
        private set
    var adapter: String = "Inconnu"
        private set

    suspend fun connect() = lock.withLock { connectLocked() }

    private suspend fun connectLocked() {
        transport?.close()
        transport = null
        val candidate = openTransport()
        transport = candidate
        try {
            rawLocked("ATZ", 5000)
            rawLocked("ATE0")
            rawLocked("ATL0")
            rawLocked("ATS0")
            rawLocked("ATH0")
            rawLocked("ATSP0")
            adapter = rawLocked("ATI").replace(">", "").trim().ifEmpty { "ELM327" }
            protocol = rawLocked("ATDP").replace(">", "").trim()
        } catch (t: Throwable) {
            candidate.close()
            transport = null
            throw t
        }
    }

    suspend fun query(command: String): String = lock.withLock {
        require(command.matches(Regex("(?i)(?:01[0-9a-f]{2}|03|07|0A|AT[A-Z0-9]*)"))) {
            "Commande non autorisée"
        }
        var failure: Exception? = null
        repeat(2) { attempt ->
            try {
                if (transport == null) connectLocked()
                return@withLock rawLocked(command).also { reply ->
                    if (reply.contains("NO DATA", ignoreCase = true)) return@also
                    if (reply.contains("ERROR", ignoreCase = true) ||
                        reply.contains("UNABLE TO CONNECT", ignoreCase = true) ||
                        reply.trim().startsWith("?")
                    ) throw ElmError(reply.trim())
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                failure = e
                transport?.close()
                transport = null
                if (attempt == 0) delay(400)
            }
        }
        throw failure ?: ElmError("Connexion impossible")
    }

    /**
     * Clearing emissions DTCs is irreversible and must never be retried automatically.
     * This method is deliberately separate from query() and requires the caller's confirmation.
     */
    suspend fun clearEmissionsConfirmed(): String = lock.withLock {
        check(transport != null) { "Non connecté" }
        rawLocked("04")
    }

    private suspend fun rawLocked(command: String, timeoutMs: Long = 4000): String {
        val t = transport ?: throw ElmError("Non connecté")
        return withTimeout(timeoutMs) {
            t.send((command + "\r").toByteArray(Charsets.US_ASCII))
            val result = StringBuilder()
            while (true) {
                val data = t.receive()
                if (data.isEmpty()) continue
                result.append(data.toString(Charsets.US_ASCII))
                if (result.length > 48_000) throw ElmError("Réponse trop longue")
                if ('>' in result) return@withTimeout result.toString()
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        }
    }

    suspend fun close() = lock.withLock {
        transport?.close()
        transport = null
        protocol = "Inconnu"
    }
}
