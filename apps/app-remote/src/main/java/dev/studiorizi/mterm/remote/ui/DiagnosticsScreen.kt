package dev.studiorizi.mterm.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.remote.R

/** Diagnostics without any guest userland checks: SSH edition only. */
@Composable
fun DiagnosticsScreen(vm: TerminalViewModel) {
    val uiState by vm.ui.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.ssh_only) + " / " + stringResource(R.string.play_compatible))
                Text(stringResource(R.string.no_debian))
            }
        }
        Button(onClick = { vm.collectDiagnostics() }) {
            Text(stringResource(R.string.btn_export))
        }
        if (uiState.diagnosticsExport.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = uiState.diagnosticsExport,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
