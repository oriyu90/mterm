package dev.studiorizi.mterm.modern.ui

import android.app.Application
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.core.diagnostics.DiagnosticsCollector
import dev.studiorizi.mterm.core.diagnostics.Redactor
import dev.studiorizi.mterm.core.linux_core.ShellArgv
import dev.studiorizi.mterm.core.process_supervisor.ProcessSupervisor
import dev.studiorizi.mterm.core.pty_native.PtyNative
import dev.studiorizi.mterm.core.root_core.RootManager
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionRuntime
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.modern.MTermApp
import dev.studiorizi.mterm.modern.backend.ExecBrokerQualification
import dev.studiorizi.mterm.modern.backend.ExecBrokerQualifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ModernUiState(
    val statusMessage: String = "",
    val qualification: ExecBrokerQualification? = null,
    val diagnosticsExport: String = "",
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val mterm = app as MTermApp

    val manager: SessionManager get() = mterm.sessionManager
    val supervisor: ProcessSupervisor get() = mterm.supervisor
    val prefs = MTermPrefs(app)

    private val _ui = MutableStateFlow(ModernUiState())
    val ui: StateFlow<ModernUiState> = _ui.asStateFlow()

    val sessions: StateFlow<Map<String, SessionRuntime>> = manager.sessions

    fun newAndroidShell() {
        val spec = SessionSpec(
            id = UUID.randomUUID().toString(),
            mode = SessionMode.ANDROID_SHELL,
            title = "Android shell",
            cwd = null,
            env = emptyMap(),
            command = ShellArgv.androidShell(),
        )
        createOnly(spec)
    }

    fun newDebianProot() {
        val spec = SessionSpec(
            id = UUID.randomUUID().toString(),
            mode = SessionMode.DEBIAN_PROOT,
            title = "Debian (PRoot)",
            cwd = null,
            env = emptyMap(),
            command = ShellArgv.debianShell(),
        )
        createOnly(spec)
    }

    fun newRootChroot(): Boolean {
        if (!RootManager.isPrivateNamespaceSupported()) return false
        val spec = SessionSpec(
            id = UUID.randomUUID().toString(),
            mode = SessionMode.DEBIAN_CHROOT,
            title = "Debian (chroot)",
            cwd = null,
            env = emptyMap(),
            command = ShellArgv.debianShell(),
        )
        createOnly(spec)
        return true
    }

    private fun createOnly(spec: SessionSpec) {
        val result = manager.create(spec)
        val message = if (result.isSuccess) {
            "device runtime required"
        } else {
            result.exceptionOrNull()?.message ?: "create failed"
        }
        _ui.update { it.copy(statusMessage = message) }
    }

    fun runExecBrokerTest() {
        viewModelScope.launch(Dispatchers.IO) {
            val qualification = ExecBrokerQualifier.qualify(mterm.filesDir)
            _ui.update { it.copy(qualification = qualification) }
        }
    }

    /** Explicit user action only: probes root and builds a redacted report. */
    fun collectDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val qualification = _ui.value.qualification
            val pageSize = try {
                Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
            } catch (_: Exception) {
                -1
            }
            val report = DiagnosticsCollector.collect(
                androidApi = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown",
                pageSize = pageSize,
                appTargetSdk = context.applicationInfo.targetSdkVersion,
                buildVariant = "modern",
                pty = if (PtyNative.isAvailable()) "PASS" else "FAIL",
                appDataExec = qualification?.appDataExec ?: "SKIP",
                proot = "SKIP",
                debian = "SKIP",
                nestedExec = qualification?.nestedExec ?: "SKIP",
                nodeVersion = null,
                processCount = supervisor.childCount(),
                rootSu = try {
                    RootManager().detectSu()
                } catch (_: Exception) {
                    false
                },
                storageGrants = 0,
                bridge = "SKIP",
            )
            val raw = buildString {
                appendLine("variant=modern")
                appendLine("androidApi=" + report.androidApi)
                appendLine("abi=" + report.abi)
                appendLine("pageSize=" + report.pageSize)
                appendLine("pty=" + report.pty)
                appendLine("appDataExec=" + report.appDataExec)
                appendLine("nestedExec=" + report.nestedExec)
                appendLine("processCount=" + report.processCount)
            }
            _ui.update { it.copy(diagnosticsExport = Redactor.redact(raw)) }
        }
    }
}

class TerminalViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TerminalViewModel(app) as T
    }
}
