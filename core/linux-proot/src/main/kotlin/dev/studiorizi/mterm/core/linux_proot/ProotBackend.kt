package dev.studiorizi.mterm.core.linux_proot

import dev.studiorizi.mterm.core.pty_runtime.PtyRuntime
import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.SpawnException
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.session_core.SpawnedProcess
import dev.studiorizi.mterm.core.session_core.UnixSignal
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // Guest env travels inside argv (/usr/bin/env -i); the host env
        // carries only the loader path. See ProotArgv (single source of
        // truth shared with one-shot GuestProbe commands).
        val argv = ProotArgv.build(prootBin, rootfsDir, bridgeDir, mirrorDir, tail)
        return PreparedSession(
            spec = spec,
            argv = argv,
            env = ProotArgv.hostEnv(prootBin),
            // The native host chdir must stay on a real Android path; the guest
            // working directory is set by proot's --cwd.
            cwd = null,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess =
        withContext(Dispatchers.IO) {
            if (!prootBin.isFile || !prootBin.canExecute()) {
                throw SpawnException(
                    SpawnFailure.PROOT_MISSING,
                    "proot binary not installed at ${prootBin.absolutePath}",
                )
            }
            if (!rootfsDir.isDirectory) {
                throw SpawnException(
                    SpawnFailure.ROOTFS_MISSING,
                    "Debian rootfs not installed at ${rootfsDir.absolutePath}",
                )
            }
            // proot --bind fails on missing host dirs; create the shared dirs.
            runCatching { bridgeDir.mkdirs() }
            runCatching { mirrorDir.mkdirs() }
            val process = PtyRuntime.spawn(
                argv = prepared.argv,
                env = prepared.env.toList(),
                cwd = prepared.cwd,
                rows = rows,
                cols = cols,
            )
            SpawnedProcess(pty = process, process = process)
        }

    override suspend fun stop(handle: ProcessHandle, signal: UnixSignal) {
        withContext(Dispatchers.IO) { handle.signal(signal) }
    }

    companion object {
        const val GUEST_HOME = ProotArgv.GUEST_HOME
        const val GUEST_USER = ProotArgv.GUEST_USER
        val DEFAULT_SHELL: List<String> = ProotArgv.DEFAULT_SHELL
    }
}
