package dev.studiorizi.mterm.remote.backend

import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.UnixSignal

/**
 * Placeholder SSH backend for the Play-compatible edition.
 * Validates the session spec into an argv array; actual transport
 * arrives later. Spawning reports device runtime as required.
 */
class SshBackend : ExecutionBackend {

    override val mode: SessionMode = SessionMode.SSH

    override suspend fun prepare(spec: SessionSpec): PreparedSession {
        require(spec.id.isNotBlank()) { "spec.id must not be blank" }
        val argv = if (spec.command.isEmpty()) listOf("ssh") else spec.command
        return PreparedSession(
            spec = spec,
            argv = argv,
            env = emptyArray(),
            cwd = spec.cwd,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, pty: PtyHandle): ProcessHandle {
        throw UnsupportedOperationException("SSH backend (later): device runtime required")
    }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        throw UnsupportedOperationException("SSH backend (later): device runtime required")
    }
}
