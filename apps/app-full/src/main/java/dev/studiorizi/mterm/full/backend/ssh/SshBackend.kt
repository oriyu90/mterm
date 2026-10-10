package dev.studiorizi.mterm.full.backend.ssh

import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.SpawnedProcess
import dev.studiorizi.mterm.core.session_core.UnixSignal
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SSH backend: an interactive remote shell surfaced as a local session.
 *
 * The session layer speaks only [PtyHandle]/[ProcessHandle]; the remote
 * byte stream is bridged through an in-memory pipe (reader loop blocks on
 * [SshPipePty.read] exactly like a native PTY read). No local process
 * exists, so [pid] is -1 and the supervisor skips registration.
 *
 * Connection parameters come from spec env ([SshParams]); secrets come from
 * [SshCredentialStore] via one-shot tokens and are wiped after auth.
 */
class SshBackend(
    private val knownHostsFile: File,
) : ExecutionBackend {
    override val mode: SessionMode = SessionMode.SSH

    override suspend fun prepare(spec: SessionSpec): PreparedSession {
        require(spec.id.isNotBlank()) { "spec.id must not be blank" }
        // Validates eagerly so typos fail before a session row is created.
        SshParams.parse(spec.env)
        val tail = if (spec.command.isEmpty()) {
            listOf("ssh", "${spec.env[SshParams.ENV_USER]}@${spec.env[SshParams.ENV_HOST]}")
        } else {
            spec.command
        }
        return PreparedSession(
            spec = spec,
            argv = tail,
            env = emptyArray(),
            cwd = null,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess =
        withContext(Dispatchers.IO) {
            val params = try {
                SshParams.parse(prepared.spec.env)
            } catch (e: IllegalArgumentException) {
                fail("bad SSH parameters: ${e.message}")
            }
            val auth = resolveAuth(params)
            val conn = try {
                SshConnection.connect(
                    host = params.host,
                    port = params.port,
                    user = params.user,
                    auth = auth,
                    knownHostsFile = knownHostsFile,
                    rows = rows,
                    cols = cols,
                )
            } catch (e: SshFailure) {
                SshEvents.errors.trySend(e.message ?: "ssh failed")
                throw SpawnException(SpawnFailure.BACKEND_UNSUPPORTED, e.message)
            } finally {
                wipeAuth(auth)
                // Tokens are single-use: drop them whether auth ran or not.
                when (val a = params.auth) {
                    is SshParams.Auth.Password -> SshCredentialStore.wipe(a.credentialToken)
                    is SshParams.Auth.Key -> {
                        SshCredentialStore.wipe(a.credentialToken)
                        a.passphraseToken?.let { SshCredentialStore.wipe(it) }
                    }
                }
            }
            val pty = SshPipePty(conn)
            SpawnedProcess(pty = pty, process = SshProcessHandle(conn))
        }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        withContext(Dispatchers.IO) { handle.signal(signal) }
    }

    private fun resolveAuth(params: SshParams): ResolvedAuth {
        return when (val a = params.auth) {
            is SshParams.Auth.Password -> {
                val pw = SshCredentialStore.takePassword(a.credentialToken)
                    ?: fail("SSH credential expired; reconnect from the dialog")
                ResolvedAuth.Password(pw)
            }
            is SshParams.Auth.Key -> {
                val pem = SshCredentialStore.takeKey(a.credentialToken)
                    ?: fail("SSH key expired; reconnect from the dialog")
                val pass = a.passphraseToken?.let { SshCredentialStore.takePassphrase(it) }
                try {
                    ResolvedAuth.Key(String(pem, Charsets.UTF_8), pass)
                } finally {
                    pem.fill(0)
                }
            }
        }
    }

    private fun wipeAuth(auth: ResolvedAuth) {
        when (auth) {
            is ResolvedAuth.Password -> auth.password.fill('\u0000')
            is ResolvedAuth.Key -> auth.passphrase?.fill('\u0000')
            // PEM string copy is GC'd; the store bytes were already zeroed.
        }
    }

    private fun fail(message: String): Nothing {
        SshEvents.errors.trySend(message)
        throw SpawnException(SpawnFailure.BACKEND_UNSUPPORTED, message)
    }
}

/**
 * PTY facade over an [SshConnection]: the reader loop blocks in [read]
 * (pipe fed by [pump]); writes go straight to the remote shell.
 */
private class SshPipePty(
    private val conn: SshConnection,
) : PtyHandle {
    override val id: Long = System.nanoTime()

    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut, PIPE_SIZE)

    @Volatile
    private var closed = false

    init {
        // Pump on a daemon thread (not a coroutine child): spawn() must
        // return while the pump lives as long as the session.
        Thread({ pump() }, "mterm-ssh-pump-$id").apply {
            isDaemon = true
            start()
        }
    }

    /** Copies remote stdout into the pipe; EOF/error closes it (read → -1). */
    fun pump() {
        val buf = ByteArray(8192)
        try {
            while (!closed) {
                val n = conn.read(buf)
                if (n <= 0) break
                try {
                    pipeOut.write(buf, 0, n)
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            close()
        }
    }

    override fun read(buffer: ByteArray): Int {
        return try {
            pipeIn.read(buffer)
        } catch (_: Exception) {
            -1
        }
    }

    override fun write(data: ByteArray, off: Int, len: Int): Int {
        if (closed) return -1
        return try {
            conn.write(data, off, len)
            len
        } catch (_: Exception) {
            -1
        }
    }

    override fun resize(rows: Int, cols: Int) {
        // sshj order is (cols, rows).
        conn.resize(cols, rows)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            pipeOut.close()
        } catch (_: Exception) {
        }
        try {
            pipeIn.close()
        } catch (_: Exception) {
        }
        conn.close()
    }

    companion object {
        const val PIPE_SIZE = 65536
    }
}

/** No local process: signals close the channel, wait joins the shell. */
private class SshProcessHandle(
    private val conn: SshConnection,
) : ProcessHandle {
    override val pid: Int = -1
    override val pgid: Int = -1

    override suspend fun wait(): Int = withContext(Dispatchers.IO) {
        conn.awaitExit()
    }

    override fun signal(sig: UnixSignal) {
        // No remote signal mapping for interactive shells; any termination
        // request (TERM/KILL from stop/shutdown) closes the connection and
        // the reader reaps the session.
        conn.close()
    }
}
