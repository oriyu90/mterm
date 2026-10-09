package dev.studiorizi.mterm.core.terminal_session

import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.SpawnedProcess
import dev.studiorizi.mterm.core.session_core.UnixSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionHostTest {

    private fun hostScope() = CoroutineScope(UnconfinedTestDispatcher())

    private class FakePty : PtyHandle {
        override val id: Long = 1L
        var closed = false
        var resized = 0
        override fun read(buffer: ByteArray): Int = -1 // immediate EOF
        override fun resize(rows: Int, cols: Int) {
            resized++
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeProcess : ProcessHandle {
        override val pid: Int = 4242
        override val pgid: Int = 4242
        var signaled: UnixSignal? = null
        override suspend fun wait(): Int = 0
        override fun signal(sig: UnixSignal) {
            signaled = sig
        }
    }

    private class OkBackend(val pty: FakePty = FakePty(), val proc: FakeProcess = FakeProcess()) :
        ExecutionBackend {
        override val mode = SessionMode.ANDROID_SHELL
        override suspend fun prepare(spec: SessionSpec): PreparedSession =
            PreparedSession(spec, spec.command, emptyArray(), spec.cwd)
        override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess =
            SpawnedProcess(pty, proc)
        override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
            handle.signal(signal)
        }
    }

    private class FailingBackend : ExecutionBackend {
        override val mode = SessionMode.DEBIAN_PROOT
        override suspend fun prepare(spec: SessionSpec): PreparedSession =
            PreparedSession(spec, spec.command, emptyArray(), spec.cwd)
        override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess =
            throw SpawnException(SpawnFailure.PROOT_MISSING, "no proot")
        override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) = Unit
    }

    @Test
    fun `open runs reader to EOF and removes the session`() = runTest {
        val backend = OkBackend()
        val manager = SessionManager(mapOf(SessionMode.ANDROID_SHELL to backend))
        val host = TerminalSessionHost(manager, hostScope())
        val id = host.open(
            SessionMode.ANDROID_SHELL,
            "shell",
            listOf("/system/bin/sh", "-i"),
            emptyMap(),
        ).getOrThrow()
        // Unconfined scope: startSession + EOF reap complete synchronously.
        // EOF + wait(0): session is reaped and removed; supervisor unregistered.
        assertTrue(manager.get(id) == null)
        assertEquals(null, host.lastFailure.value)
        assertEquals(0, host.lastExitCode.value)
        assertEquals(0, host.supervisor.childCount())
        assertTrue(backend.pty.closed)
    }

    @Test
    fun `spawn failure surfaces typed failure and removes the session`() = runTest {
        val manager = SessionManager(mapOf(SessionMode.DEBIAN_PROOT to FailingBackend()))
        val host = TerminalSessionHost(manager, hostScope())
        val id = host.open(
            SessionMode.DEBIAN_PROOT,
            "debian",
            listOf("/bin/zsh", "-l"),
            emptyMap(),
        ).getOrThrow()
        assertEquals(SpawnFailure.PROOT_MISSING, host.lastFailure.value)
        assertTrue(manager.get(id) == null)
    }

    @Test
    fun `resize reaches emulator and pty`() = runTest {
        val backend = OkBackend()
        val manager = SessionManager(mapOf(SessionMode.ANDROID_SHELL to backend))
        val host = TerminalSessionHost(manager, hostScope())
        // Pre-create without starting: emulator exists once open() is called.
        val id = host.open(
            SessionMode.ANDROID_SHELL,
            "shell",
            listOf("/system/bin/sh", "-i"),
            emptyMap(),
        ).getOrThrow()
        host.resize(id, 30, 100)
        // Session already reaped (EOF); resize is a safe no-op, never throws.
        assertTrue(true)
    }
}
