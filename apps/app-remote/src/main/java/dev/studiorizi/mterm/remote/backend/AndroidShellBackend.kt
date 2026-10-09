package dev.studiorizi.mterm.remote.backend

import dev.studiorizi.mterm.core.linux_core.ShellArgv
import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.SpawnedProcess
import dev.studiorizi.mterm.core.session_core.UnixSignal

/**
 * Android shell backend (Play/remote edition): fixed argv for /system/bin/sh,
 * never a shell string. Spawn is gated until this edition wires the shared
 * terminal runtime; it never fetches external executable code.
 */
class AndroidShellBackend : ExecutionBackend {

    override val mode: SessionMode = SessionMode.ANDROID_SHELL

    override suspend fun prepare(spec: SessionSpec): PreparedSession {
        require(spec.id.isNotBlank()) { "spec.id must not be blank" }
        val argv = if (spec.command.isEmpty()) ShellArgv.androidShell() else spec.command
        return PreparedSession(
            spec = spec,
            argv = argv,
            env = emptyArray(),
            cwd = spec.cwd,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess {
        throw SpawnException(SpawnFailure.BACKEND_UNSUPPORTED, "remote edition spawn not wired")
    }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        throw SpawnException(SpawnFailure.BACKEND_UNSUPPORTED, "remote edition spawn not wired")
    }
}
