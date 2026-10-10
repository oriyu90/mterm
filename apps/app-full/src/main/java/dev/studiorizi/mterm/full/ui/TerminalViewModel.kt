package dev.studiorizi.mterm.full.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.studiorizi.mterm.core.linux_core.LinuxEnv
import dev.studiorizi.mterm.core.linux_core.ShellArgv
import dev.studiorizi.mterm.core.process_supervisor.RiskLevel
import dev.studiorizi.mterm.core.root_core.RootManager
import dev.studiorizi.mterm.core.session_core.SessionMode
import dev.studiorizi.mterm.core.session_core.SessionRuntime
import dev.studiorizi.mterm.core.session_core.SpawnFailure
import dev.studiorizi.mterm.core.terminal_emulator.TerminalEmulator
import dev.studiorizi.mterm.core.terminal_session.TerminalSessionHost
import dev.studiorizi.mterm.full.R
import dev.studiorizi.mterm.full.service.TerminalService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compose-facing holder for session use-cases.
 *
 * The running [TerminalSessionHost] lives in [TerminalService] so PTYs
 * survive Activity recreation; this model binds to the service and mirrors
 * its flows for the UI. Privileged operations go through [RootManager]
 * capability checks first; failures surface as status text, never as crashes.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    private val _host = MutableStateFlow<TerminalSessionHost?>(null)
    val host: StateFlow<TerminalSessionHost?> = _host.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, SessionRuntime>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionRuntime>> = _sessions.asStateFlow()

    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastExit = MutableStateFlow<String?>(null)
    val lastExit: StateFlow<String?> = _lastExit.asStateFlow()

    private val _rootChrootSupported = MutableStateFlow<Boolean?>(null)
    val rootChrootSupported: StateFlow<Boolean?> = _rootChrootSupported.asStateFlow()

    private var bound = false
    private var bridgeServer: dev.studiorizi.mterm.full.backend.BridgeServer? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val host = (binder as? TerminalService.LocalBinder)?.getHost() ?: return
            bridgeServer = (binder as? TerminalService.LocalBinder)?.getBridgeServer()
            _host.value = host
            _sessions.value = host.sessions.value
            _tick.value = host.renderTick.value
            _rootChrootSupported.value = checkRootChrootSupport()
            // Mirror the service flows so the UI recomposes on every change.
            viewModelScope.launch {
                host.sessions.collect { _sessions.value = it }
            }
            viewModelScope.launch {
                host.renderTick.collect { _tick.value = it }
            }
            viewModelScope.launch {
                host.lastFailure.collect { failure ->
                    if (failure != null) {
                        _lastError.value = failureMessage(failure)
                        host.clearLastFailure()
                    }
                }
            }
            viewModelScope.launch {
                host.lastExitCode.collect { code ->
                    if (code != null) {
                        if (code != 0) {
                            _lastExit.value = getApplication<Application>().getString(
                                R.string.session_exited_code,
                                code,
                            )
                        }
                        host.clearLastExit()
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _host.value = null
        }
    }

    init {
        _rootChrootSupported.value = null
    }

    suspend fun bridgePing(): String? = bridgeServer?.ping()

    fun postError(text: String) {
        _lastError.value = text
    }

    fun bind(context: Context) {
        if (bound) return
        bound = true
        val intent = Intent(context, TerminalService::class.java)
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: SecurityException) {
            bound = false
            _lastError.value = context.getString(R.string.error_service_bind)
        }
    }

    fun unbind(context: Context) {
        if (!bound) return
        bound = false
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Already unbound; ignore.
        }
        _host.value = null
    }

    /** Syncs one-shot UI state; live flows are collected in onServiceConnected. */
    fun refresh(host: TerminalSessionHost) {
        _sessions.value = host.sessions.value
        _tick.value = host.renderTick.value
    }

    fun emulator(sessionId: String): TerminalEmulator? = _host.value?.emulator(sessionId)

    fun processRisk(): RiskLevel = _host.value?.supervisor?.riskLevel() ?: RiskLevel.NORMAL

    fun processCount(): Int = _host.value?.supervisor?.childCount() ?: 0

    fun newAndroidShell(context: Context) {
        TerminalService.start(context)
        // The host may not be bound yet on first launch; queue via bind retry.
        val host = _host.value
        if (host == null) {
            _lastError.value = context.getString(R.string.error_starting_retry)
            bind(context)
            return
        }
        val result = host.open(
            mode = SessionMode.ANDROID_SHELL,
            title = context.getString(R.string.android_shell),
            command = ShellArgv.androidShell(),
            env = emptyMap(),
        )
        result.exceptionOrNull()?.let { _lastError.value = it.message }
    }

    fun newDebianProot(context: Context) {
        TerminalService.start(context)
        val host = _host.value
        if (host == null) {
            _lastError.value = context.getString(R.string.error_starting_retry)
            bind(context)
            return
        }
        val result = host.open(
            mode = SessionMode.DEBIAN_PROOT,
            title = context.getString(R.string.debian),
            command = ShellArgv.debianShell(),
            env = LinuxEnv.baseEnv("/home/user"),
        )
        result.exceptionOrNull()?.let { _lastError.value = it.message }
    }

    fun newRootChroot(context: Context) {
        if (_rootChrootSupported.value != true) {
            _lastError.value = context.getString(R.string.error_root_unsupported)
            return
        }
        TerminalService.start(context)
        val host = _host.value ?: run {
            _lastError.value = context.getString(R.string.error_starting_retry)
            return
        }
        val result = host.open(
            mode = SessionMode.DEBIAN_CHROOT,
            title = context.getString(R.string.root_chroot),
            command = ShellArgv.debianShell(),
            env = LinuxEnv.baseEnv("/home/user"),
        )
        result.exceptionOrNull()?.let { _lastError.value = it.message }
    }

    fun write(sessionId: String, data: ByteArray) {
        _host.value?.write(sessionId, data)
    }

    fun resize(sessionId: String, rows: Int, cols: Int) {
        _host.value?.resize(sessionId, rows, cols)
    }

    fun stopSession(id: String) {
        _host.value?.stop(id)
    }

    fun stopAll(context: Context) {
        val host = _host.value
        if (host == null) {
            TerminalService.stopAll(context)
        } else {
            host.stopAll()
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun clearExit() {
        _lastExit.value = null
    }

    private fun failureMessage(failure: SpawnFailure): String {
        val context = getApplication<Application>()
        return when (failure) {
            SpawnFailure.PTY_UNAVAILABLE -> context.getString(R.string.error_pty_unavailable)
            SpawnFailure.PROOT_MISSING -> context.getString(R.string.error_proot_missing)
            SpawnFailure.ROOTFS_MISSING -> context.getString(R.string.error_rootfs_missing)
            SpawnFailure.ROOT_UNSUPPORTED -> context.getString(R.string.error_root_unsupported)
            SpawnFailure.BACKEND_UNSUPPORTED -> context.getString(R.string.error_backend_unsupported)
        }
    }

    private fun checkRootChrootSupport(): Boolean {
        val manager = RootManager()
        if (!manager.detectSu()) return false
        return RootManager.isPrivateNamespaceSupported()
    }
}
