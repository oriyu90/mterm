package dev.studiorizi.mterm.full.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.studiorizi.mterm.core.linux_chroot.ChrootBackend
import dev.studiorizi.mterm.core.linux_core.LinuxEnv
import dev.studiorizi.mterm.core.linux_core.ShellArgv
import dev.studiorizi.mterm.core.linux_proot.ProotBackend
import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.process_supervisor.RiskLevel
import dev.studiorizi.mterm.core.root_core.RootManager
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionRuntime
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.full.backend.AndroidShellBackend
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Compose-facing holder for session use-cases.
 *
 * The ViewModel owns a [SessionManager] built from argv-only backends; the
 * running [service-side manager][dev.studiorizi.mterm.full.service.TerminalService]
 * is authoritative on device, and this model mirrors its metadata for the MVP
 * placeholder UI. Privileged operations go through [RootManager] capability
 * checks first; failures surface as status text, never as crashes.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val filesDir: File = application.filesDir

    val sessionManager: SessionManager
    val supervisor = ProcessSupervisor()
    private val rootManager = RootManager()

    val sessions: StateFlow<Map<String, SessionRuntime>>

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _rootChrootSupported = MutableStateFlow<Boolean?>(null)
    val rootChrootSupported: StateFlow<Boolean?> = _rootChrootSupported.asStateFlow()

    init {
        val debianDir = File(filesDir, "linux/distributions/debian/current/rootfs")
        val bridgeDir = File(filesDir, "shared/bridge")
        val mirrorDir = File(filesDir, "shared/mirror")
        val prootBin = File(filesDir, "bin/proot")
        sessionManager = SessionManager(
            mapOf(
                SessionMode.ANDROID_SHELL to AndroidShellBackend(),
                SessionMode.DEBIAN_PROOT to ProotBackend(
                    rootfsDir = debianDir,
                    bridgeDir = bridgeDir,
                    mirrorDir = mirrorDir,
                    prootBin = prootBin,
                ),
                SessionMode.DEBIAN_CHROOT to ChrootBackend(rootfsDir = debianDir),
            ),
        )
        sessions = sessionManager.sessions
        viewModelScope.launch {
            _rootChrootSupported.value = checkRootChrootSupport()
        }
    }

    fun processRisk(): RiskLevel = supervisor.riskLevel()

    fun newAndroidShell() {
        create(
            mode = SessionMode.ANDROID_SHELL,
            title = "Android shell",
            command = ShellArgv.androidShell(),
            env = emptyMap(),
        )
    }

    fun newDebianProot() {
        create(
            mode = SessionMode.DEBIAN_PROOT,
            title = "Debian",
            command = ShellArgv.debianShell(),
            env = LinuxEnv.baseEnv("/home/user"),
        )
    }

    fun newRootChroot() {
        if (_rootChrootSupported.value != true) {
            _lastError.value = "root-chroot-unsupported"
            return
        }
        create(
            mode = SessionMode.DEBIAN_CHROOT,
            title = "Root",
            command = ShellArgv.debianShell(),
            env = LinuxEnv.baseEnv("/home/user"),
        )
    }

    fun stopSession(id: String) {
        viewModelScope.launch {
            val result = sessionManager.stop(id)
            result.exceptionOrNull()?.let { _lastError.value = it.message }
        }
    }

    fun stopAll() {
        viewModelScope.launch { sessionManager.stopAll() }
    }

    fun clearError() {
        _lastError.value = null
    }

    private fun create(
        mode: SessionMode,
        title: String,
        command: List<String>,
        env: Map<String, String>,
    ) {
        val spec = SessionSpec(
            id = UUID.randomUUID().toString(),
            mode = mode,
            title = title,
            cwd = null,
            env = env,
            command = command,
        )
        val result = sessionManager.create(spec)
        result.exceptionOrNull()?.let { _lastError.value = it.message }
        // Device runtime spawn is wired through TerminalService; until the
        // PTY layer is attached the session stays in CREATED state.
    }

    private fun checkRootChrootSupport(): Boolean {
        if (!rootManager.detectSu()) return false
        return RootManager.isPrivateNamespaceSupported()
    }
}
