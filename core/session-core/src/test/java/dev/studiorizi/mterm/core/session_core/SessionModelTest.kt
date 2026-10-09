package dev.studiorizi.mterm.core.session_core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelTest {

    private fun validSpec() = SessionSpec(
        id = "s1",
        mode = SessionMode.ANDROID_SHELL,
        title = "Shell",
        cwd = "/data/data/dev.studiorizi.mterm/files",
        env = mapOf("TERM" to "xterm-256color"),
        command = listOf("/system/bin/sh", "-i"),
    )

    @Test
    fun `valid spec passes`() {
        assertTrue(SessionValidator.validate(validSpec()).isSuccess)
    }

    @Test
    fun `empty id fails`() {
        assertTrue(SessionValidator.validate(validSpec().copy(id = "")).isFailure)
    }

    @Test
    fun `blank title fails`() {
        assertTrue(SessionValidator.validate(validSpec().copy(title = "  ")).isFailure)
    }

    @Test
    fun `empty command fails`() {
        assertTrue(SessionValidator.validate(validSpec().copy(command = emptyList())).isFailure)
    }

    @Test
    fun `env key with equals fails`() {
        val spec = validSpec().copy(env = mapOf("A=B" to "x"))
        assertTrue(SessionValidator.validate(spec).isFailure)
    }

    @Test
    fun `empty env key fails`() {
        val spec = validSpec().copy(env = mapOf("" to "x"))
        assertTrue(SessionValidator.validate(spec).isFailure)
    }

    @Test
    fun `env value with null byte fails`() {
        val spec = validSpec().copy(env = mapOf("A" to "x\u0000y"))
        assertTrue(SessionValidator.validate(spec).isFailure)
    }

    @Test
    fun `cwd traversal fails`() {
        val spec = validSpec().copy(cwd = "/a/../../etc")
        assertTrue(SessionValidator.validate(spec).isFailure)
    }

    @Test
    fun `cwd null byte fails`() {
        val spec = validSpec().copy(cwd = "/a\u0000/b")
        assertTrue(SessionValidator.validate(spec).isFailure)
    }

    @Test
    fun `command arg with null byte fails`() {
        val spec = validSpec().copy(command = listOf("/system/bin/sh\u0000", "-i"))
        assertTrue(SessionValidator.validate(spec).isFailure)
    }

    // --- SessionManager smoke tests with fakes ---

    private class FakePty(override val id: Long = 1L) : PtyHandle {
        var closed = false
        override fun resize(rows: Int, cols: Int) = Unit
        override fun close() {
            closed = true
        }
    }

    private class FakeProcess(
        override val pid: Int = 42,
        override val pgid: Int = 42,
    ) : ProcessHandle {
        var signaled: UnixSignal? = null
        override suspend fun wait(): Int = 0
        override fun signal(sig: UnixSignal) {
            signaled = sig
        }
    }

    private class FakeBackend : ExecutionBackend {
        override val mode = SessionMode.ANDROID_SHELL
        val proc = FakeProcess()
        val pty = FakePty(id = 7L)
        var stopped: UnixSignal? = null

        override suspend fun prepare(spec: SessionSpec): PreparedSession =
            PreparedSession(
                spec = spec,
                argv = spec.command.toList(),
                env = spec.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
                cwd = spec.cwd,
            )

        override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess =
            SpawnedProcess(pty = pty, process = proc)

        override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
            stopped = signal
        }
    }

    @Test
    fun `manager create start stop lifecycle`() = runBlocking {
        val backend = FakeBackend()
        val manager = SessionManager(mapOf(SessionMode.ANDROID_SHELL to backend))
        val spec = validSpec()

        assertTrue(manager.create(spec).isSuccess)
        // Duplicate id must fail.
        assertTrue(manager.create(spec).isFailure)

        assertTrue(manager.start("s1", 24, 80).isSuccess)
        assertEquals(SessionState.RUNNING, manager.get("s1")!!.state.value)

        assertTrue(manager.stop("s1").isSuccess)
        assertEquals(SessionState.EXITED, manager.get("s1")!!.state.value)
        assertEquals(UnixSignal.SIGTERM, backend.stopped)
        assertTrue(backend.pty.closed)
    }

    @Test
    fun `manager start unknown session fails`() = runBlocking {
        val manager = SessionManager(emptyMap())
        assertTrue(manager.start("missing", 24, 80).isFailure)
    }

    @Test
    fun `withPty rejects session without pty`() = runBlocking {
        val manager = SessionManager(mapOf(SessionMode.ANDROID_SHELL to FakeBackend()))
        manager.create(validSpec())
        assertTrue(manager.withPty("s1") { 1 }.isFailure)
    }

    @Test
    fun `withPty runs block after start`() = runBlocking {
        val manager = SessionManager(mapOf(SessionMode.ANDROID_SHELL to FakeBackend()))
        manager.create(validSpec())
        manager.start("s1", 24, 80)
        val result = manager.withPty("s1") { handle -> handle.id }
        assertTrue(result.isSuccess)
        assertEquals(7L, result.getOrNull())
    }

    @Test
    fun `remove drops the session from the live map`() = runBlocking {
        val manager = SessionManager(mapOf(SessionMode.ANDROID_SHELL to FakeBackend()))
        manager.create(validSpec())
        manager.remove("s1")
        assertEquals(null, manager.get("s1"))
        assertTrue(manager.sessions.value.isEmpty())
    }
}
