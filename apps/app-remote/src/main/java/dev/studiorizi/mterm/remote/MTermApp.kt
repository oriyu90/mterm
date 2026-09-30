package dev.studiorizi.mterm.remote

import android.app.Application
import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.remote.backend.AndroidShellBackend
import dev.studiorizi.mterm.remote.backend.SshBackend
import java.io.File

/**
 * Play-compatible edition: Android shell and SSH sessions only.
 * No guest userland installer lives in this module.
 */
class MTermApp : Application() {

    val supervisor = ProcessSupervisor()

    val sessionManager: SessionManager by lazy {
        val backends: Map<SessionMode, ExecutionBackend> = mapOf(
            SessionMode.ANDROID_SHELL to AndroidShellBackend(),
            SessionMode.SSH to SshBackend(),
        )
        SessionManager(backends)
    }

    val mirrorDir: File get() = File(filesDir, "shared/mirror")

    val sshWorkspaceDir: File get() = File(filesDir, "ssh-workspace")
}
