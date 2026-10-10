package dev.studiorizi.mterm.full.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.diagnostics.DiagnosticsCollector
import dev.studiorizi.mterm.core.diagnostics.Redactor
import dev.studiorizi.mterm.core.pty_runtime.PtyRuntime
import dev.studiorizi.mterm.core.root_core.RootManager
import dev.studiorizi.mterm.full.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compatibility report viewer (plan section 18.4).
 *
 * PTY / app-data exec / nested exec are real on-device probes (not
 * library-presence flags); PRoot / Debian reflect the installer state;
 * page size comes from [android.system.Os]. Export uses [Redactor] so
 * paths, usernames and tokens never leave the device unmasked. 16 KB
 * readiness is checked via [DiagnosticsCollector.pageSizeOk].
 */
@Composable
fun DiagnosticsScreen(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    var exported by remember { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<String?>(null) }
    var ptyResult by remember { mutableStateOf<String?>(null) }
    var appDataExecResult by remember { mutableStateOf<String?>(null) }
    var nestedExecResult by remember { mutableStateOf<String?>(null) }
    var netValue by remember { mutableStateOf<String?>(null) }
    var freeValue by remember { mutableStateOf<String?>(null) }
    var bridgeResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        bridgeResult = viewModel.bridgePing()
    }

    val pass = stringResource(R.string.pass)
    val fail = stringResource(R.string.fail)
    val unverified = stringResource(R.string.diag_status_runtime_required)
    val unknown = stringResource(R.string.unknown)

    LaunchedEffect(Unit) {
        ptyResult = if (PtyRuntime.probe()) pass else fail
        appDataExecResult = if (probeAppDataExec(context.filesDir)) pass else fail
        nestedExecResult = if (probeNestedExec()) pass else fail
        try {
            val caps = collectCaps(context.applicationContext)
            netValue = if (caps.networkTransport == "NONE") {
                context.getString(R.string.diag_network_none)
            } else {
                context.getString(
                    R.string.diag_network_value,
                    caps.networkTransport,
                    context.getString(
                        if (caps.networkMetered) {
                            R.string.diag_network_metered
                        } else {
                            R.string.diag_network_unmetered
                        },
                    ),
                ) + if (caps.networkValidated) {
                    ""
                } else {
                    " · " + context.getString(R.string.diag_network_unvalidated)
                }
            }
            freeValue = android.text.format.Formatter.formatShortFileSize(context, caps.freeBytes)
        } catch (_: Throwable) {
            // Network/storage rows stay blank rather than crash the screen.
        }
    }

    val pageSize = remember {
        try {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE).toInt()
        } catch (_: Exception) {
            4096
        }
    }
    val rootManager = remember { RootManager() }
    val caps = remember {
        try {
            rootManager.capabilities()
        } catch (_: Exception) {
            null
        }
    }
    val prootBin = File(context.filesDir, "bin/proot")
    val rootfsDir = File(context.filesDir, "linux/distributions/debian/current/rootfs")
    val prootStatus = when {
        prootBin.canExecute() -> pass
        prootBin.exists() -> fail
        else -> unverified
    }
    val debianStatus = if (rootfsDir.isDirectory) pass else unverified
    val report = remember(ptyResult, appDataExecResult, nestedExecResult, bridgeResult) {
        DiagnosticsCollector.collect(
            androidApi = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: context.getString(R.string.unknown),
            model = Build.MODEL ?: context.getString(R.string.unknown),
            abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: context.getString(R.string.unknown),
            pageSize = pageSize,
            appTargetSdk = context.applicationInfo.targetSdkVersion,
            buildVariant = "full",
            pty = ptyResult ?: unverified,
            appDataExec = appDataExecResult ?: unverified,
            proot = prootStatus,
            debian = debianStatus,
            nestedExec = nestedExecResult ?: unverified,
            nodeVersion = null,
            processCount = viewModel.processCount(),
            rootSu = caps?.suAvailable == true,
            storageGrants = context.contentResolver.persistedUriPermissions.size,
            bridge = bridgeResult ?: unverified,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        DiagRow(stringResource(R.string.diag_device), "${report.manufacturer} ${report.model} (API ${report.androidApi}, ${report.abi})")
        DiagRow(
            stringResource(R.string.diag_page_size_ok),
            if (DiagnosticsCollector.pageSizeOk(report.pageSize)) {
                "${report.pageSize}"
            } else {
                stringResource(R.string.diag_page_size_warn) + ": ${report.pageSize}"
            },
        )
        DiagRow(stringResource(R.string.diag_pty), report.pty)
        DiagRow(stringResource(R.string.diag_app_data_exec), report.appDataExec)
        DiagRow(stringResource(R.string.diag_proot), report.proot)
        DiagRow(stringResource(R.string.diag_debian), report.debian)
        DiagRow(stringResource(R.string.diag_nested_exec), report.nestedExec)
        DiagRow(stringResource(R.string.diag_node), report.nodeVersion ?: unknown)
        DiagRow(stringResource(R.string.diag_network), netValue ?: unverified)
        DiagRow(stringResource(R.string.diag_storage_free), freeValue ?: unverified)
        DiagRow(stringResource(R.string.diag_process_count), "${report.processCount}")
        DiagRow(
            stringResource(R.string.diag_root),
            "su=${caps?.suAvailable} mntns=${caps?.mountNamespace} bind=${caps?.bindMount} chroot=${caps?.chroot}",
        )
        DiagRow(
            stringResource(R.string.diag_storage),
            stringResource(R.string.saf_grants) + ": ${report.storageGrants}",
        )
        DiagRow(stringResource(R.string.diag_bridge), report.bridge)
        val batteryExempt = try {
            val pm = context.applicationContext.getSystemService(android.os.PowerManager::class.java)
            pm?.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            null
        }
        DiagRow(
            stringResource(R.string.battery_title),
            when (batteryExempt) {
                true -> stringResource(R.string.battery_unrestricted)
                false -> stringResource(R.string.battery_restricted)
                null -> stringResource(R.string.battery_unknown)
            },
        )

        Spacer(Modifier.height(16.dp))
        TButton(
            onClick = {
                val json = Json.encodeToString(report)
                exportPreview = Redactor.redact(json)
                exported = true
            },
        ) {
            Text(stringResource(R.string.diag_export))
        }
        if (exported) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.diag_exported),
                style = MaterialTheme.typography.bodyMedium,
            )
            exportPreview?.let {
                Spacer(Modifier.height(4.dp))
                Text(it.take(2000), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * App-data exec probe: writes a tiny script under app-private filesDir,
 * marks it executable and runs it directly. True exec (not via `sh file`)
 * so the target-28 W^X exemption is actually exercised.
 */
private suspend fun probeAppDataExec(filesDir: File): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(filesDir, "diagnostics")
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
        val script = File(dir, "exec-probe.sh")
        script.writeText("#!/system/bin/sh\nprintf mterm-exec-ok\n")
        if (!script.setExecutable(true)) return@withContext false
        val process = ProcessBuilder(script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        output.contains("mterm-exec-ok") && exit == 0
    } catch (_: Throwable) {
        false
    }
}

/** Nested-exec probe: PTY child spawning a grandchild through two shells. */
private suspend fun probeNestedExec(): Boolean = withContext(Dispatchers.IO) {
    try {
        if (!PtyRuntime.isAvailable()) return@withContext false
        val proc = PtyRuntime.spawn(
            argv = listOf("/system/bin/sh", "-c", "/system/bin/sh -c 'printf mterm-nested-ok'"),
            rows = 24,
            cols = 80,
        )
        try {
            val buf = ByteArray(256)
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < deadline) {
                val n = proc.read(buf)
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (sb.contains("mterm-nested-ok")) break
            }
            val exit = proc.wait()
            sb.contains("mterm-nested-ok") && exit == 0
        } finally {
            try {
                proc.close()
            } catch (_: Throwable) {
                // best effort
            }
        }
    } catch (_: Throwable) {
        false
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
