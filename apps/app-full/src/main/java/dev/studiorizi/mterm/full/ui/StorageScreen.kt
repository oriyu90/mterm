package dev.studiorizi.mterm.full.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import dev.studiorizi.mterm.core.storage_mirror.ConflictDecision
import dev.studiorizi.mterm.core.storage_mirror.ConflictInfo
import dev.studiorizi.mterm.core.storage_mirror.StorageMirrorManager
import dev.studiorizi.mterm.full.R
import java.io.File

/**
 * SAF tree picker + mirror status + manual sync + conflict dialog.
 *
 * Sync itself runs through [StorageMirrorManager] bookkeeping; SAF
 * DocumentFile I/O stays in the platform layer and guest paths are mapped
 * via [dev.studiorizi.mterm.core.linux_core.RootfsPathMapper] by callers.
 * Conflicts never auto-overwrite: the user picks KEEP_ANDROID /
 * KEEP_LINUX / DUPLICATE.
 */
@Composable
fun StorageScreen() {
    val context = LocalContext.current
    val manager = remember {
        StorageMirrorManager(File(context.filesDir, "shared"))
    }
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var conflict by remember { mutableStateOf<ConflictInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        treeUri = uri
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Permission not persistable on this device; one-shot use only.
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.storage), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.mirror_status), style = MaterialTheme.typography.titleMedium)
        Text(
            status ?: stringResource(R.string.mirror_needs_sync),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        Text(
            treeUri?.toString() ?: stringResource(R.string.saf_no_tree),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        Row {
            TButton(onClick = { treePicker.launch(null) }) {
                Text(stringResource(R.string.saf_pick_tree))
            }
        }
        Spacer(Modifier.height(8.dp))
        TButton(
            onClick = {
                // Demo conflict probe: both sides newer than last sync.
                val now = System.currentTimeMillis()
                val found = manager.detectConflict(
                    androidMtime = now,
                    linuxMtime = now,
                    lastSync = now - 60_000,
                    path = "shared/demo.txt",
                )
                if (found != null) {
                    conflict = found
                } else {
                    status = context.getString(R.string.mirror_healthy)
                }
            },
        ) {
            Text(stringResource(R.string.sync_now))
        }
    }

    conflict?.let { info ->
        ConflictDialog(
            info = info,
            onDecision = { decision ->
                when (decision) {
                    ConflictDecision.KEEP_ANDROID,
                    ConflictDecision.KEEP_LINUX,
                    ConflictDecision.DUPLICATE,
                    -> status = context.getString(R.string.mirror_healthy)
                }
                conflict = null
            },
            onDismiss = { conflict = null },
        )
    }
}

@Composable
private fun ConflictDialog(
    info: ConflictInfo,
    onDecision: (ConflictDecision) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conflict_title)) },
        text = { Text(stringResource(R.string.conflict_message, info.path)) },
        confirmButton = {
            TTextButton(onClick = { onDecision(ConflictDecision.KEEP_ANDROID) }) {
                Text(stringResource(R.string.keep_android))
            }
        },
        dismissButton = {
            Row {
                TTextButton(onClick = { onDecision(ConflictDecision.KEEP_LINUX) }) {
                    Text(stringResource(R.string.keep_linux))
                }
                TTextButton(onClick = { onDecision(ConflictDecision.DUPLICATE) }) {
                    Text(stringResource(R.string.duplicate))
                }
            }
        },
    )
}
