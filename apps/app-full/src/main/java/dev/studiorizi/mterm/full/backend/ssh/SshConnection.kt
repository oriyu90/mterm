package dev.studiorizi.mterm.full.backend.ssh

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts

/**
 * A live SSH interactive shell over sshj.
 *
 * Pure transport (no Android imports): JVM-testable against an in-process
 * server. All blocking sshj calls run on [Dispatchers.IO] from the backend;
 * the session reader loop only calls the blocking [read].
 */
class SshConnection private constructor(
    private val client: SSHClient,
    private val session: Session,
    private val shell: Session.Shell,
    private val remoteOut: InputStream,
    private val remoteIn: OutputStream,
) {
    private val writeLock = Any()

    /**
     * Blocking read of remote stdout; returns -1 on EOF. Called by the
     * session reader loop (already off the main thread).
     */
    fun read(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        return try {
            remoteOut.read(buf, off, len)
        } catch (_: Exception) {
            -1
        }
    }

    /** Writes stdin bytes to the remote shell. Never throws. */
    fun write(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (len <= 0) return
        synchronized(writeLock) {
            try {
                remoteIn.write(data, off, len)
                remoteIn.flush()
            } catch (_: Exception) {
            }
        }
    }

    /** Forwards terminal size; best-effort (dead sessions must not crash). */
    fun resize(cols: Int, rows: Int) {
        try {
            shell.changeWindowDimensions(cols, rows, 0, 0)
        } catch (_: Exception) {
        }
    }

    /** Blocks until the remote shell closes; returns its exit status (0 when unknown). */
    fun awaitExit(): Int {
        return try {
            shell.join()
            (session as? net.schmizz.sshj.connection.channel.direct.SessionChannel)?.exitStatus ?: 0
        } catch (_: Exception) {
            -1
        }
    }

    fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
        try {
            client.disconnect()
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val TRUST_TIMEOUT_MS = 120_000L

        /**
         * Connects, verifies the host key (known_hosts or interactive trust),
         * authenticates, and opens a PTY shell.
         *
         * @throws SshFailure with a human message on any step (the backend
         *   forwards it to [SshEvents.errors]).
         */
        suspend fun connect(
            host: String,
            port: Int,
            user: String,
            auth: ResolvedAuth,
            knownHostsFile: File,
            rows: Int,
            cols: Int,
        ): SshConnection = withContext(Dispatchers.IO) {
            val client = SSHClient()
            try {
                client.connectTimeout = CONNECT_TIMEOUT_MS
                client.timeout = CONNECT_TIMEOUT_MS
                client.addHostKeyVerifier(verifier(host, port, knownHostsFile))
                try {
                    client.connect(host, port)
                } catch (e: Exception) {
                    throw SshFailure("connect $host:$port: ${short(e)}", e)
                }
                try {
                    when (auth) {
                        is ResolvedAuth.Password -> client.authPassword(user, auth.password)
                        is ResolvedAuth.Key -> {
                            val provider = client.loadKeys(auth.pemText, auth.passphrase)
                            try {
                                client.authPublickey(user, provider)
                            } finally {
                                // Key material was copied into the provider;
                                // the caller wipes its own buffers.
                            }
                        }
                    }
                } catch (e: Exception) {
                    throw SshFailure("auth $user@$host: ${short(e)}", e)
                } catch (t: Throwable) {
                    // E.g. NoClassDefFoundError if the server insists on an
                    // auth method whose classes don't exist on Android (GSSAPI).
                    throw SshFailure("auth $user@$host unsupported here: ${t.javaClass.simpleName}", t)
                }
                val session = try {
                    client.startSession()
                } catch (e: Exception) {
                    throw SshFailure("open session: ${short(e)}", e)
                }
                val shell = try {
                    session.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                    session.startShell()
                } catch (e: Exception) {
                    try {
                        session.close()
                    } catch (_: Exception) {
                    }
                    throw SshFailure("open shell: ${short(e)}", e)
                }
                SshConnection(client, session, shell, shell.inputStream, shell.outputStream)
            } catch (e: Exception) {
                try {
                    client.disconnect()
                } catch (_: Exception) {
                }
                try {
                    client.close()
                } catch (_: Exception) {
                }
                throw if (e is SshFailure) e else SshFailure("ssh $host:$port: ${short(e)}", e)
            }
        }

        private fun verifier(
            host: String,
            port: Int,
            knownHostsFile: File,
        ): net.schmizz.sshj.transport.verification.HostKeyVerifier =
            object : net.schmizz.sshj.transport.verification.HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: java.security.PublicKey): Boolean {
                    if (verifyKnown(knownHostsFile, host, port, key)) {
                        return true
                    }
                    val fp = try {
                        "${KeyType.fromKey(key)} ${SecurityUtils.getFingerprint(key)}"
                    } catch (_: Exception) {
                        "unprintable key"
                    }
                    return when (SshTrust.awaitDecision(host, port, fp, TRUST_TIMEOUT_MS)) {
                        SshTrust.Decision.DENY -> false
                        SshTrust.Decision.ONCE -> true
                        SshTrust.Decision.ALWAYS -> {
                            appendKnownHost(knownHostsFile, host, port, key)
                            true
                        }
                    }
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                    emptyList()
            }

        private fun verifyKnown(file: File, host: String, port: Int, key: java.security.PublicKey): Boolean {
            if (!file.isFile) return false
            return try {
                OpenSSHKnownHosts(file).verify(host, port, key)
            } catch (_: Exception) {
                false
            }
        }

        private fun appendKnownHost(file: File, host: String, port: Int, key: java.security.PublicKey) {
            try {
                val type = KeyType.fromKey(key).toString()
                val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
                val b64 = java.util.Base64.getEncoder().encodeToString(blob)
                // OpenSSH format: bare host for port 22, [host]:port otherwise.
                // (OpenSSHKnownHosts.verify only matches the bracketed form
                // for non-standard ports — a bare entry never matches there.)
                val name = if (port == 22) host else "[$host]:$port"
                file.parentFile?.mkdirs()
                file.appendText("$name $type $b64\n")
            } catch (_: Exception) {
                // Trust-once still applied; persistence is best-effort.
            }
        }

        private fun short(e: Exception): String =
            (e.message?.take(160) ?: e.javaClass.simpleName).replace('\n', ' ')
    }
}

/** Auth material resolved from the credential store (wiped by the caller). */
sealed interface ResolvedAuth {
    data class Password(val password: CharArray) : ResolvedAuth
    data class Key(val pemText: String, val passphrase: CharArray?) : ResolvedAuth
}

/** Human-readable SSH step failure (keeps the causal chain for logs). */
class SshFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
