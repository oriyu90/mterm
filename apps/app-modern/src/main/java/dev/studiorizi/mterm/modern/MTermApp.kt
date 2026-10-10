package dev.studiorizi.mterm.modern

import android.app.Application
import dev.studiorizi.mterm.core.linux_chroot.ChrootBackend
import dev.studiorizi.mterm.core.linux_proot.ProotBackend
import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.session_core.ExecutionBackend
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.modern.backend.AndroidShellBackend
import java.io.File

/** Application holder for process-scoped session state (plan section 10.1). */
class MTermApp : Application() {

    val supervisor = ProcessSupervisor()

    val sessionManager: SessionManager by lazy {
        val backends: Map<SessionMode, ExecutionBackend> = mapOf(
            SessionMode.ANDROID_SHELL to AndroidShellBackend(),
            SessionMode.DEBIAN_PROOT to ProotBackend(
                rootfsDir = File(filesDir, "linux/rootfs"),
                bridgeDir = File(filesDir, "bridge"),
                mirrorDir = File(filesDir, "shared/mirror"),
                prootBin = File(filesDir, "bin/proot"),
                tmpDir = File(filesDir, "tmp"),
            ),
            SessionMode.DEBIAN_CHROOT to ChrootBackend(
                rootfsDir = File(filesDir, "linux/rootfs"),
            ),
        )
        SessionManager(backends)
    }

    val rootfsDir: File get() = File(filesDir, "linux/rootfs")

    val bridgeDir: File get() = File(filesDir, "bridge")

    val mirrorDir: File get() = File(filesDir, "shared/mirror")
}
