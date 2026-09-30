package dev.studiorizi.mterm.modern.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.studiorizi.mterm.modern.R

/** Gate C qualification UI: shows appDataExec / nestedExec PASS/FAIL. */
@Composable
fun DiagnosticsScreen(vm: TerminalViewModel) {
    val uiState by vm.ui.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.exec_broker) + " / " + stringResource(R.string.qualification))
                Text(stringResource(R.string.diag_app_data_exec) + ": " + (uiState.qualification?.appDataExec ?: "SKIP"))
                Text(stringResource(R.string.diag_nested_exec) + ": " + (uiState.qualification?.nestedExec ?: "SKIP"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runExecBrokerTest() }) {
                Text(stringResource(R.string.btn_run_qualification))
            }
            Button(onClick = { vm.collectDiagnostics() }) {
                Text(stringResource(R.string.btn_export))
            }
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
