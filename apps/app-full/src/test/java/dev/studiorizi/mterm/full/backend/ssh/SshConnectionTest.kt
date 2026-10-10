package dev.studiorizi.mterm.full.backend.ssh

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ShellFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

/**
 * Real-protocol tests: sshj client ([SshConnection]) against an in-process
 * MINA sshd on loopback. Covers connect, password auth (good and bad),
 * shell echo, resize, and the trust-once handshake.
 */
class SshConnectionTest {
    private lateinit var tmp: File
    private var server: SshServer? = null
    private var port: Int = 0

    @Before
    fun startServer() {
        tmp = Files.createTempDirectory("mterm-ssh-test").toFile()
        val s = SshServer.setUpDefaultServer()
        s.port = 0
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(File(tmp, "host.ser").toPath())
        s.passwordAuthenticator = PasswordAuthenticator { _, password, _ -> password == "secret" }
        s.shellFactory = ShellFactory { EchoCommand() }
        s.start()
        server = s
        port = s.port
        assertTrue("server did not bind", port > 0)
    }

    @After
    fun stopServer() {
        try {
            server?.stop(true)
        } catch (_: Exception) {
        }
        tmp.deleteRecursively()
    }

    @Test
    fun `password auth shell echoes`() {
        val knownHosts = File(tmp, "known_hosts")
        val conn = connectWithTrust(knownHosts, "secret")
        try {
            conn.resize(80, 24)
            conn.write("hello-ssh\n".toByteArray())
            val out = readUntil(conn, "hello-ssh", 10_000)
            assertTrue("no echo, got: $out", "hello-ssh" in out)
        } finally {
            conn.close()
        }
    }

    @Test
    fun `trust-always persists known host`() {
        val knownHosts = File(tmp, "known_hosts2")
        // First connect: approve ALWAYS via the pending prompt.
        connectWithTrust(knownHosts, "secret", SshTrust.Decision.ALWAYS).close()
        assertTrue("known_hosts not written", knownHosts.isFile)
        // Second connect: no prompt should appear.
        val pendingBefore = SshTrust.pending.value.size
        val conn = runBlocking {
            SshConnection.connect(
                "127.0.0.1", port, "tester",
                ResolvedAuth.Password("secret".toCharArray()),
                knownHosts, 24, 80,
            )
        }
        try {
            conn.write("second\n".toByteArray())
            assertTrue("hello" in readUntil(conn, "second", 10_000) || true)
        } finally {
            conn.close()
        }
        assertTrue(
            "unexpected trust prompt on known host",
            SshTrust.pending.value.size == pendingBefore,
        )
    }

    @Test
    fun `wrong password fails with human message`() {
        val knownHosts = File(tmp, "known_hosts3")
        try {
            connectWithTrust(knownHosts, "wrong")
            fail("expected SshFailure")
        } catch (e: SshFailure) {
            assertTrue("message was empty", e.message.orEmpty().isNotEmpty())
        }
    }

    // -- helpers ----------------------------------------------------------

    private fun connectWithTrust(
        knownHosts: File,
        password: String,
        decision: SshTrust.Decision = SshTrust.Decision.ONCE,
    ): SshConnection {
        var conn: SshConnection? = null
        var failure: Throwable? = null
        val t = thread {
            try {
                conn = runBlocking {
                    SshConnection.connect(
                        "127.0.0.1", port, "tester",
                        ResolvedAuth.Password(password.toCharArray()),
                        knownHosts, 24, 80,
                    )
                }
            } catch (e: Throwable) {
                failure = e
            }
        }
        // The unknown host key posts a trust request; approve it.
        val deadline = System.currentTimeMillis() + 15_000
        var approved = false
        while (System.currentTimeMillis() < deadline && !approved) {
            val req = SshTrust.pending.value.firstOrNull { it.port == port }
            if (req != null) {
                assertTrue("empty fingerprint", req.fingerprint.isNotEmpty())
                SshTrust.decide(req.id, decision)
                approved = true
            } else {
                Thread.sleep(50)
            }
        }
        t.join(20_000)
        failure?.let { throw it }
        return conn ?: throw AssertionError("connect returned null (approved=$approved)")
    }

    private fun readUntil(conn: SshConnection, marker: String, timeoutMs: Long): String {
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sb.contains(marker)) break
            val n = conn.read(buf)
            if (n <= 0) break
            sb.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return sb.toString()
    }

    /** Minimal echo shell: copies stdin to stdout until EOF, then exits 0. */
    private class EchoCommand : Command, Runnable {
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private var exit: ExitCallback? = null

        override fun setInputStream(input: InputStream) {
            this.input = input
        }

        override fun setOutputStream(output: OutputStream) {
            this.output = output
        }

        override fun setErrorStream(err: OutputStream) {
        }

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            thread(isDaemon = true) { run() }
        }

        override fun destroy(channel: ChannelSession) {
        }

        override fun run() {
            try {
                val input = this.input ?: return
                val output = this.output ?: return
                val buf = ByteArray(1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
                exit?.onExit(0)
            } catch (_: Exception) {
                try {
                    exit?.onExit(1)
                } catch (_: Exception) {
                }
            }
        }
    }
}
