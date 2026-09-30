package dev.studiorizi.mterm.core.linux_chroot

import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.PtyHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.UnixSignal
import java.io.File

/**
 * Root Debian backend via chroot (plan section 13).
 *
 * This backend only builds the chroot argv. The privileged setup happens
 * before [spawn] and is owned by root-core's RootManager: acquire root
 * (explicit user action only, never auto-prompted), create a private mount
 * namespace, mark / as rprivate so mounts never leak to the host, bind /dev,
 * mount /proc, bind only workspace/bridge dirs (read-only /sys when needed),
 * then chroot and exec zsh on the PTY. On exit, mounts inside the namespace
 * are cleaned up. If a private mount namespace is unsupported, the chroot
 * backend stays disabled rather than touching global mounts.
 *
 * Like [dev.studiorizi.mterm.core.linux_proot.ProotBackend], no shell-string
 * concatenation is used here: no "su -c '...'" wrapping.
 */
class ChrootBackend(
    private val rootfsDir: File,
) : ExecutionBackend {

    override val mode: SessionMode = SessionMode.DEBIAN_CHROOT

    /**
     * Validates [spec] and builds the chroot argv array. A custom
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
        val argv = listOf("chroot", rootfsDir.absolutePath) + tail
        return PreparedSession(
            spec = spec,
            argv = argv,
            env = arrayOf(
                "HOME=$GUEST_HOME",
                "USER=$GUEST_USER",
                "TERM=xterm-256color",
                "SHELL=/bin/zsh",
            ),
            cwd = GUEST_HOME,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, pty: PtyHandle): ProcessHandle {
        throw UnsupportedOperationException(
            "chroot spawn requires root device runtime with private mount namespace",
        )
    }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        throw UnsupportedOperationException(
            "chroot spawn requires root device runtime with private mount namespace",
        )
    }

    companion object {
        const val GUEST_HOME = "/home/user"
        const val GUEST_USER = "user"
        val DEFAULT_SHELL: List<String> = listOf("/bin/zsh", "-l")
    }
}
