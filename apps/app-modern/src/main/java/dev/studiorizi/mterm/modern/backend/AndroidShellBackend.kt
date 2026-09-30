package dev.studiorizi.mterm.modern.backend

import dev.studiorizi.mterm.core.linux_core.ShellArgv
import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.UnixSignal

/** Android shell backend: fixed argv for /system/bin/sh, never a shell string. */
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

    override suspend fun spawn(prepared: PreparedSession, pty: PtyHandle): ProcessHandle {
        throw UnsupportedOperationException("PTY spawn requires device runtime")
    }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        throw UnsupportedOperationException("PTY spawn requires device runtime")
    }
}
