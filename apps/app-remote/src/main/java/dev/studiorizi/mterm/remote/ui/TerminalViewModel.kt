package dev.studiorizi.mterm.remote.ui

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
import dev.studiorizi.mterm.core.session_core.SessionManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionRuntime
import dev.studiorizi.mterm.core.session_core.SessionSpec
import dev.studiorizi.mterm.remote.MTermApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class RemoteUiState(
    val statusMessage: String = "",
    val diagnosticsExport: String = "",
)

class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val mterm = app as MTermApp

    val manager: SessionManager get() = mterm.sessionManager
    val supervisor: ProcessSupervisor get() = mterm.supervisor
    val prefs = MTermPrefs(app)

    private val _ui = MutableStateFlow(RemoteUiState())
    val ui: StateFlow<RemoteUiState> = _ui.asStateFlow()

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

    /** SSH placeholder: records intent and reports the backend as pending. */
    fun newSsh() {
        _ui.update { it.copy(statusMessage = "SSH backend (later)") }
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

    /** Explicit user action only: builds a redacted report. */
    fun collectDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
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
                buildVariant = "remote",
                pty = if (PtyNative.isAvailable()) "PASS" else "FAIL",
                appDataExec = "SKIP",
                proot = "SKIP",
                debian = "SKIP",
                nestedExec = "SKIP",
                nodeVersion = null,
                processCount = supervisor.childCount(),
                rootSu = false,
                storageGrants = 0,
                bridge = "SKIP",
            )
            val raw = buildString {
                appendLine("variant=remote")
                appendLine("androidApi=" + report.androidApi)
                appendLine("abi=" + report.abi)
                appendLine("pageSize=" + report.pageSize)
                appendLine("pty=" + report.pty)
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
