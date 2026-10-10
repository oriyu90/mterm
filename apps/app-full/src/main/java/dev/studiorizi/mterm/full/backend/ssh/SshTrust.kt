package dev.studiorizi.mterm.full.backend.ssh

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Async SSH side-channel: backend auth errors and host-key trust prompts.
 *
 * [dev.studiorizi.mterm.core.session_core.SessionManager] only surfaces a
 * typed [dev.studiorizi.mterm.core.session_core.SpawnFailure], so human
 * detail ("auth failed for bob@host") travels here; the ViewModel collects
 * [errors] and shows them as status text.
 */
object SshEvents {
    val errors = Channel<String>(Channel.BUFFERED)
}

/**
 * In-memory secret vault. Passwords and key bytes live here (never in
 * SavedState, logs, or DataStore) and are wiped once consumed.
 */
object SshCredentialStore {
    private val passwords = ConcurrentHashMap<String, CharArray>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val passphrases = ConcurrentHashMap<String, CharArray>()

    fun putPassword(password: CharArray): String {
        val token = UUID.randomUUID().toString()
        passwords[token] = password
        return token
    }

    fun putKey(pem: ByteArray): String {
        val token = UUID.randomUUID().toString()
        keys[token] = pem
        return token
    }

    fun putPassphrase(passphrase: CharArray): String {
        val token = UUID.randomUUID().toString()
        passphrases[token] = passphrase
        return token
    }

    fun takePassword(token: String): CharArray? = passwords.remove(token)?.also {
        // Caller copies/wipes; drop our reference immediately.
    }

    fun takeKey(token: String): ByteArray? = keys.remove(token)

    fun takePassphrase(token: String): CharArray? = passphrases.remove(token)

    fun wipe(token: String) {
        passwords.remove(token)?.fill('\u0000')
        keys.remove(token)?.fill(0)
        passphrases.remove(token)?.fill('\u0000')
    }
}

/**
 * Host-key trust handshake between the service-side verifier (sshj thread)
 * and the UI dialog. Unknown keys suspend the connection (bounded wait);
 * DENY (or timeout) aborts the connect.
 */
object SshTrust {
    data class Request(
        val id: String,
        val host: String,
        val port: Int,
        val fingerprint: String,
    )

    enum class Decision {
        ONCE,
        ALWAYS,
        DENY,
    }

    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending.asStateFlow()

    private val gates = ConcurrentHashMap<String, java.util.concurrent.LinkedBlockingQueue<Decision>>()

    /**
     * Posts a trust prompt and blocks (interruptibly) for [timeoutMs}.
     * Runs on sshj transport threads — never call from a coroutine that
     * must stay responsive.
     */
    fun awaitDecision(host: String, port: Int, fingerprint: String, timeoutMs: Long): Decision {
        val req = Request(UUID.randomUUID().toString(), host, port, fingerprint)
        val gate = java.util.concurrent.LinkedBlockingQueue<Decision>(1)
        gates[req.id] = gate
        _pending.update { it + req }
        return try {
            gate.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: Decision.DENY
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Decision.DENY
        } finally {
            gates.remove(req.id)
            _pending.update { list -> list.filter { it.id != req.id } }
        }
    }

    fun decide(id: String, decision: Decision) {
        gates[id]?.offer(decision)
    }
}
