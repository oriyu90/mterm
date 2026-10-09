package dev.studiorizi.mterm.full.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.core.linux_proot.GuestProbe
import dev.studiorizi.mterm.core.linux_proot.ProotInstaller
import dev.studiorizi.mterm.core.rootfs_manager.InstallState
import dev.studiorizi.mterm.core.rootfs_manager.RootfsChannel
import dev.studiorizi.mterm.core.rootfs_manager.RootfsInstaller
import dev.studiorizi.mterm.core.rootfs_manager.RootfsKeys
import dev.studiorizi.mterm.full.R
import dev.studiorizi.mterm.full.backend.LinuxPaths
import java.io.File
import kotlinx.coroutines.launch

/**
 * Linux runtime setup: PRoot install (bundled), Debian download/verify/
 * install (signed release), doctor checks and developer presets.
 *
 * Everything runs through typed results; failures show messages, never
 * crashes. Long steps (apt/npm) run in a coroutine with the buttons
 * disabled while busy.
 */
@Composable
fun LinuxSetupScreen(onOpenTerminal: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember { MTermPrefs(appContext) }
    val scope = rememberCoroutineScope()
    val wifiOnly by prefs.wifiOnlyDownload.collectAsState(initial = true)

    var busy by remember { mutableStateOf(false) }
    var prootVersion by remember { mutableStateOf<String?>(null) }
    var prootChecked by remember { mutableStateOf(false) }
    var debianState by remember { mutableStateOf("—") }
    var debianVersion by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf("") }

    fun filesDir(): File = appContext.filesDir

    suspend fun refreshProot(): String? {
        val bin = LinuxPaths.prootBin(filesDir())
        val version = ProotInstaller.probeVersion(bin, LinuxPaths.binDir(filesDir()))
        prootVersion = version
        prootChecked = true
        return version
    }

    suspend fun refreshDebian() {
        val installer = RootfsInstaller(filesDir(), RootfsKeys.publicKeyRaw32() ?: ByteArray(0))
        val current = installer.currentInstall()
        if (current == null) {
            debianState = InstallState.NOT_INSTALLED.name
            debianVersion = ""
        } else {
            debianState = current.state.name
            debianVersion = current.version
        }
    }

    LaunchedEffect(Unit) {
        refreshProot()
        refreshDebian()
    }

    fun appendLog(line: String) {
        log = (log + "\n$ " + line.take(4000)).takeLast(8000)
    }

    fun runGuarded(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    suspend fun requireDebianReady(): Boolean {
        refreshProot()
        refreshDebian()
        if (prootVersion == null) {
            message = context.getString(R.string.linux_proot_missing)
            return false
        }
        val installer = RootfsInstaller(filesDir(), RootfsKeys.publicKeyRaw32() ?: ByteArray(0))
        val current = installer.currentInstall()
        if (current?.state != InstallState.READY) {
            message = context.getString(R.string.linux_debian_state, debianState, debianVersion)
            return false
        }
        return true
    }

    suspend fun guest(script: String, timeoutMs: Long): GuestProbe.ProbeResult? {
        val result = GuestProbe.run(
            LinuxPaths.prootBin(filesDir()),
            LinuxPaths.rootfsDir(filesDir()),
            LinuxPaths.bridgeDir(filesDir()),
            LinuxPaths.mirrorDir(filesDir()),
            script,
            timeoutMs,
        )
        return result.fold(
            onSuccess = { it },
            onFailure = {
                message = it.message ?: it.toString()
                null
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.linux_setup), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        // ---- PRoot ----
        Text(stringResource(R.string.linux_proot_title), style = MaterialTheme.typography.titleMedium)
        Text(
            if (!prootChecked) {
                stringResource(R.string.linux_running)
            } else if (prootVersion != null) {
                stringResource(R.string.linux_proot_installed, prootVersion!!)
            } else {
                stringResource(R.string.linux_proot_missing)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        TButton(
            onClick = {
                runGuarded {
                    val assets = try {
                        appContext.assets.list("bin")?.associateWith { name ->
                            appContext.assets.open("bin/$name").use { it.readBytes() }
                        } ?: emptyMap()
                    } catch (e: Exception) {
                        message = e.message
                        return@runGuarded
                    }
                    val installed = ProotInstaller.install(LinuxPaths.binDir(filesDir()), assets)
                    installed.fold(
                        onSuccess = { refreshProot() },
                        onFailure = {
                            message = context.getString(R.string.linux_proot_failed, "${it.message}")
                        },
                    )
                }
            },
        ) {
            Text(stringResource(R.string.linux_proot_install))
        }
        Spacer(Modifier.height(16.dp))

        // ---- Debian ----
        Text(stringResource(R.string.linux_debian_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.linux_debian_state, debianState, debianVersion.ifEmpty { "-" }),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
        progressText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }
        TButton(
            onClick = {
                runGuarded {
                    val caps = collectCaps(appContext)
                    if (wifiOnly && caps.networkMetered) {
                        message = context.getString(R.string.linux_wifi_required)
                        return@runGuarded
                    }
                    val installer = RootfsInstaller(
                        filesDir(),
                        RootfsKeys.publicKeyRaw32() ?: ByteArray(0),
                    )
                    // Manifest first (also yields the versioned archive URL).
                    val manifestUrl = RootfsInstaller.manifestUrl()
                    progress = 0f
                    val result = installer.install(
                        RootfsChannel.STABLE,
                        manifestUrl,
                        onState = { state, done, total ->
                            progressText = when (state) {
                                InstallState.DOWNLOADING -> if (total > 0) {
                                    context.getString(
                                        R.string.linux_progress,
                                        humanBytes(context, done),
                                        humanBytes(context, total),
                                    )
                                } else {
                                    null
                                }
                                InstallState.VERIFYING ->
                                    context.getString(R.string.linux_verifying)
                                InstallState.EXTRACTING ->
                                    context.getString(R.string.linux_extracting)
                                InstallState.INITIALIZING ->
                                    context.getString(R.string.linux_initializing)
                                else -> null
                            }
                            progress = if (total > 0) {
                                (done.toFloat() / total).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                        },
                    )
                    progress = null
                    progressText = null
                    result.fold(
                        onSuccess = {
                            message = context.getString(R.string.linux_ready, it.version)
                            refreshDebian()
                        },
                        onFailure = {
                            message = context.getString(R.string.linux_install_failed, "${it.message}")
                            refreshDebian()
                        },
                    )
                }
            },
        ) {
            Text(stringResource(R.string.linux_download_install))
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        TButton(
            onClick = {
                runGuarded {
                    if (requireDebianReady()) onOpenTerminal()
                }
            },
        ) {
            Text(stringResource(R.string.linux_open_terminal))
        }
        Spacer(Modifier.height(16.dp))

        // ---- Doctor ----
        Text(stringResource(R.string.linux_doctor_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        DoctorButton(stringResource(R.string.linux_doctor_shell), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest("printf 'doctor-shell-ok '\n", 30_000)?.let {
                    appendLog("shell exit=${it.exitCode}\n${it.output.trim().take(500)}")
                    true
                } == true
        }
        DoctorButton(stringResource(R.string.linux_doctor_apt), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest("apt-get update -o Acquire::AllowInsecureRepositories=false", 180_000)?.let {
                    appendLog("apt exit=${it.exitCode}\n${it.output.trim().takeLast(800)}")
                    true
                } == true
        }
        DoctorButton(stringResource(R.string.linux_doctor_python), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest("python3 --version", 30_000)?.let {
                    appendLog("python exit=${it.exitCode}\n${it.output.trim().take(200)}")
                    true
                } == true
        }
        DoctorButton(stringResource(R.string.linux_doctor_node), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest("node --version && npm --version", 30_000)?.let {
                    appendLog("node exit=${it.exitCode}\n${it.output.trim().take(200)}")
                    true
                } == true
        }
        DoctorButton(stringResource(R.string.linux_doctor_localhost), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest(
                    "nohup python3 -m http.server 18080 >/tmp/http.log 2>&1 & " +
                        "sleep 1; curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/",
                    60_000,
                )?.let {
                    appendLog("localhost http=${it.output.trim().take(10)} exit=${it.exitCode}")
                    true
                } == true
        }
        Spacer(Modifier.height(16.dp))

        // ---- Presets ----
        Text(stringResource(R.string.linux_preset_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        DoctorButton(stringResource(R.string.linux_preset_node), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest(
                    "apt-get update && apt-get install -y nodejs npm && node --version && npm --version",
                    600_000,
                )?.let {
                    appendLog("preset-node exit=${it.exitCode}\n${it.output.trim().takeLast(800)}")
                    true
                } == true
        }
        DoctorButton(stringResource(R.string.linux_preset_claude), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest(
                    "npm install -g @anthropic-ai/claude-code && claude --version",
                    600_000,
                )?.let {
                    appendLog("preset-claude exit=${it.exitCode}\n${it.output.trim().takeLast(800)}")
                    true
                } == true
        }
        Text(
            stringResource(R.string.linux_note_claude_auth),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        DoctorButton(stringResource(R.string.linux_preset_opencode), busy, ::appendLog, scope) {
            requireDebianReady() &&
                guest(
                    "export PATH=\"${'$'}HOME/.opencode/bin:${'$'}HOME/.local/bin:${'$'}PATH\"; " +
                        "curl -fsSL https://opencode.ai/install.sh | bash && opencode --version",
                    300_000,
                )?.let {
                    appendLog("preset-opencode exit=${it.exitCode}\n${it.output.trim().takeLast(800)}")
                    true
                } == true
        }
        Spacer(Modifier.height(8.dp))
        if (log.isNotEmpty()) {
            Text(stringResource(R.string.linux_output), style = MaterialTheme.typography.titleMedium)
            Text(
                log.trim().takeLast(3000),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun humanBytes(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes)

@Composable
private fun DoctorButton(
    label: String,
    busy: Boolean,
    appendLog: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    action: suspend () -> Boolean,
) {
    TButton(
        onClick = {
            if (!busy) {
                scope.launch {
                    appendLog("--- " + label + " ---")
                    action()
                }
            }
        },
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        Text(
            if (busy) {
                stringResource(R.string.linux_running)
            } else {
                label
            },
        )
    }
}
