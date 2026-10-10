package dev.studiorizi.mterm.full.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.core.storage_mirror.ConflictDecision
import dev.studiorizi.mterm.core.storage_mirror.ConflictInfo
import dev.studiorizi.mterm.core.storage_mirror.StorageMirrorManager
import dev.studiorizi.mterm.full.R
import dev.studiorizi.mterm.full.backend.SafSyncEngine
import java.io.File
import kotlinx.coroutines.launch

/**
 * SAF tree picker + real mirror sync + conflict resolution.
 *
 * The picked tree URI and derived mount id persist in [MTermPrefs]; Sync now
 * copies SAF -> `shared/<mountId>/mirror/` (visible in the guest at
 * `/mnt/shared`), journaling every copy. Both-sides-changed files surface
 * as conflicts resolved by real copies (never silent overwrites).
 */
@Composable
fun StorageScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember { MTermPrefs(appContext) }
    val scope = rememberCoroutineScope()
    val manager = remember {
        StorageMirrorManager(File(appContext.filesDir, "shared"))
    }
    val savedUri by prefs.safTreeUri.collectAsState(initial = "")
    val savedMount by prefs.safMountId.collectAsState(initial = "")

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var conflicts by remember { mutableStateOf(listOf<ConflictInfo>()) }
    var conflict by remember { mutableStateOf<ConflictInfo?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            status = context.getString(R.string.saf_no_tree)
            return@rememberLauncherForActivityResult
        }
        val mountId = SafSyncEngine.mountIdFor(uri)
        scope.launch {
            prefs.setSafTree(uri.toString(), mountId)
            status = context.getString(R.string.mirror_needs_sync)
            conflicts = emptyList()
        }
    }

    fun runSync() {
        if (busy || savedUri.isEmpty() || savedMount.isEmpty()) return
        busy = true
        progress = null
        status = null
        scope.launch {
            try {
                val result = SafSyncEngine.importTree(
                    appContext,
                    Uri.parse(savedUri),
                    manager,
                    savedMount,
                    onProgress = { files, bytes ->
                        progress = context.getString(R.string.sync_progress, files, bytes / 1024)
                    },
                ).getOrElse {
                    status = context.getString(R.string.sync_failed, "${it.message}")
                    return@launch
                }
                progress = null
                conflicts = result.conflicts
                status = context.getString(
                    R.string.sync_done,
                    result.copied,
                    result.skippedUpToDate,
                    result.conflicts.size,
                )
            } finally {
                busy = false
            }
        }
    }

    fun resolve(info: ConflictInfo, decision: ConflictDecision) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                SafSyncEngine.resolveConflict(
                    appContext,
                    Uri.parse(savedUri),
                    manager,
                    savedMount,
                    info,
                    decision,
                ).getOrElse {
                    status = context.getString(R.string.sync_failed, "${it.message}")
                    return@launch
                }
                conflicts = conflicts.filterNot { it.path == info.path }
                status = context.getString(R.string.mirror_healthy)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(savedUri) {
        if (savedUri.isEmpty()) conflicts = emptyList()
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
            if (savedUri.isEmpty()) {
                stringResource(R.string.saf_no_tree)
            } else {
                savedMount.ifEmpty { savedUri }
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        Row {
            TButton(onClick = { treePicker.launch(null) }) {
                Text(stringResource(R.string.saf_pick_tree))
            }
        }
        Spacer(Modifier.height(8.dp))
        TButton(onClick = ::runSync) {
            Text(
                if (busy) {
                    stringResource(R.string.sync_running)
                } else {
                    stringResource(R.string.sync_now)
                },
            )
        }
        progress?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (conflicts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.conflict_list, conflicts.size),
                style = MaterialTheme.typography.titleMedium,
            )
            conflicts.take(20).forEach { info ->
                TButton(
                    onClick = { conflict = info },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(info.path.take(48))
                }
            }
        }
    }

    conflict?.let { info ->
        ConflictDialog(
            info = info,
            onDecision = { decision ->
                conflict = null
                resolve(info, decision)
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
