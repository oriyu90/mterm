package dev.studiorizi.mterm.full.backend

import dev.studiorizi.mterm.core.linux_core.ShellArgv
import dev.studiorizi.mterm.core.pty_runtime.PtyRuntime
import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.PreparedSession
import dev.studiorizi.mterm.core.session_core.ProcessHandle
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.core.session_core.SpawnedProcess
import dev.studiorizi.mterm.core.session_core.UnixSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android shell backend: spawns `/system/bin/sh -i` on a real PTY via
 * [PtyRuntime]. This is the MVP's first-priority path (plan section 19.2 #01).
 *
 * No shell string concatenation: [ShellArgv.androidShell] is a fixed argv list
 * and [SessionSpec.command], when non-empty, replaces the default shell argv.
 * The environment is passed wholesale to exec (the native layer clears the
 * inherited environment first), so the shell behaviour does not depend on the
 * parent process environment.
 */
class AndroidShellBackend : ExecutionBackend {
    override val mode: SessionMode = SessionMode.ANDROID_SHELL

    override suspend fun prepare(spec: SessionSpec): PreparedSession {
        require(spec.id.isNotBlank()) { "spec.id must not be blank" }
        val tail = if (spec.command.isEmpty()) ShellArgv.androidShell() else spec.command

        val env = LinkedHashMap<String, String>()
        env["TERM"] = "xterm-256color"
        env["PATH"] = ANDROID_PATH
        env["LANG"] = "C.UTF-8"
        env["SHELL"] = "/system/bin/sh"
        spec.cwd?.let { env["HOME"] = it }
        // Caller-supplied entries win.
        env.putAll(spec.env)

        return PreparedSession(
            spec = spec,
            argv = tail,
            env = env.map { (k, v) -> "$k=$v" }.toTypedArray(),
            cwd = spec.cwd,
        )
    }

    override suspend fun spawn(prepared: PreparedSession, rows: Int, cols: Int): SpawnedProcess =
        withContext(Dispatchers.IO) {
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
        const val ANDROID_PATH = "/system/bin:/system/xbin:/vendor/bin:/product/bin:/apex/com.android.runtime/bin"
    }
}
