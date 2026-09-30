package dev.studiorizi.mterm.core.linux_proot

import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.UnixSignal
import java.io.File

/**
 * Non-root Debian backend via PRoot (plan section 9.4).
 *
 * PRoot mediates syscalls/paths with ptrace, so filesystem-heavy workloads
 * run slower than under a native chroot; this is the accepted cost of
 * running Debian without root. Nothing here concatenates shell strings:
 * [prepare] returns an argv array that the PTY spawner passes to exec
 * directly.
 */
class ProotBackend(
    private val rootfsDir: File,
    private val bridgeDir: File,
    private val mirrorDir: File,
    private val prootBin: File,
) : ExecutionBackend {

    override val mode: SessionMode = SessionMode.DEBIAN_PROOT

    /**
     * Validates [spec] and builds the proot argv array. A custom
     * [SessionSpec.command] replaces the default login shell; an empty
     * command means the default shell.
     */
    override suspend fun prepare(spec: SessionSpec): PreparedSession {
        require(spec.id.isNotBlank()) { "spec.id must not be blank" }
        val tail = if (spec.command.isEmpty() || spec.command == DEFAULT_SHELL) {
            DEFAULT_SHELL
        } else {
            spec.command
        }
        val argv = listOf(
            prootBin.absolutePath,
            "--rootfs", rootfsDir.absolutePath,
            "--bind", "${bridgeDir.absolutePath}:/run/android-bridge",
            "--bind", "${mirrorDir.absolutePath}:/mnt/shared",
            "--cwd", GUEST_HOME,
            "/usr/bin/env", "-i",
            "HOME=$GUEST_HOME",
            "USER=$GUEST_USER",
            "TERM=xterm-256color",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "SHELL=/bin/zsh",
        ) + tail
        return PreparedSession(
            spec = spec,
            // Guest env travels inside argv (/usr/bin/env -i); nothing extra here.
            argv = argv,
            env = emptyArray(),
            cwd = GUEST_HOME,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, pty: PtyHandle): ProcessHandle {
        throw UnsupportedOperationException("PTY spawn requires device runtime; argv validated")
    }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        throw UnsupportedOperationException("PTY spawn requires device runtime; argv validated")
    }

    companion object {
        const val GUEST_HOME = "/home/user"
        const val GUEST_USER = "user"
        val DEFAULT_SHELL: List<String> = listOf("/bin/zsh", "-l")
    }
}
