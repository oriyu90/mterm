package dev.studiorizi.mterm.full.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.studiorizi.mterm.core.pty_native.PtyNative
import dev.studiorizi.mterm.core.root_core.RootManager
import dev.studiorizi.mterm.full.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compatibility report viewer (plan section 18.4).
 *
 * Collects [DiagnosticsCollector] fields from Android APIs at composition
 * time, shows pty/appDataExec/proot/debian/nestedExec/node/processCount/
 * root/storage/bridge plus the page size, and exports with [Redactor] so
 * paths, usernames and tokens never leave the device unmasked. 16 KB
 * readiness is checked via [DiagnosticsCollector.pageSizeOk].
 */
@Composable
fun DiagnosticsScreen(viewModel: TerminalViewModel) {
    val context = LocalContext.current
    var exported by remember { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<String?>(null) }

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
    val report = remember {
        DiagnosticsCollector.collect(
            androidApi = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: context.getString(R.string.unknown),
            model = Build.MODEL ?: context.getString(R.string.unknown),
            abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: context.getString(R.string.unknown),
            pageSize = pageSize,
            appTargetSdk = context.applicationInfo.targetSdkVersion,
            buildVariant = "full",
            pty = if (PtyNative.isAvailable()) "PASS" else "UNVERIFIED",
            appDataExec = context.getString(R.string.diag_status_runtime_required),
            proot = context.getString(R.string.diag_status_runtime_required),
            debian = context.getString(R.string.diag_status_runtime_required),
            nestedExec = context.getString(R.string.diag_status_runtime_required),
            nodeVersion = null,
            processCount = viewModel.supervisor.childCount(),
            rootSu = caps?.suAvailable == true,
            storageGrants = context.contentResolver.persistedUriPermissions.size,
            bridge = context.getString(R.string.diag_status_runtime_required),
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
        DiagRow(stringResource(R.string.diag_node), report.nodeVersion ?: stringResource(R.string.unknown))
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

        Spacer(Modifier.height(16.dp))
        Button(
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

@Composable
private fun DiagRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
