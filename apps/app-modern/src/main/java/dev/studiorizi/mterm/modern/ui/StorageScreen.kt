package dev.studiorizi.mterm.modern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.linux_core.RootfsPathMapper
import dev.studiorizi.mterm.core.storage_mirror.StorageMirrorManager
import dev.studiorizi.mterm.core.storage_mirror.SyncExcludes
import dev.studiorizi.mterm.modern.MTermApp
import dev.studiorizi.mterm.modern.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** SAF mirror status plus the Debian guest bind mapping. */
@Composable
fun StorageScreen(app: MTermApp) {
    val mirrorManager = remember { StorageMirrorManager(File(app.filesDir, "shared")) }
    val pathMapper = remember { RootfsPathMapper(app.rootfsDir, app.bridgeDir) }
    var journalCount by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        journalCount = withContext(Dispatchers.IO) {
            try {
                mirrorManager.listJournal("shared").size
            } catch (_: Exception) {
                -1
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.storage_mirror))
                Text("journal=" + journalCount)
                Text("excluded=" + SyncExcludes.DEFAULT.joinToString(", "))
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.storage_debian_bind))
                val guest = pathMapper.hostToGuest(File(app.rootfsDir, "home/user")) ?: "/"
                Text(guest)
            }
        }
    }
}
