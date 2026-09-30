package dev.studiorizi.mterm.modern

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.studiorizi.mterm.modern.service.TerminalService
import dev.studiorizi.mterm.modern.ui.DiagnosticsScreen
import dev.studiorizi.mterm.modern.ui.SettingsScreen
import dev.studiorizi.mterm.modern.ui.StorageScreen
import dev.studiorizi.mterm.modern.ui.TerminalViewModel
import dev.studiorizi.mterm.modern.ui.TerminalViewModelFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MTermApp
        setContent {
            MaterialTheme {
                val vm: TerminalViewModel = viewModel(factory = TerminalViewModelFactory(app))
                ModernRoot(app = app, vm = vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernRoot(app: MTermApp, vm: TerminalViewModel) {
    var screen by remember { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for ((index, label) in listOf(
                    0 to stringResource(R.string.nav_home),
                    1 to stringResource(R.string.nav_settings),
                    2 to stringResource(R.string.nav_diagnostics),
                    3 to stringResource(R.string.nav_storage),
                )) {
                    Button(onClick = { screen = index }) { Text(label) }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                0 -> ModernHome(vm = vm)
                1 -> SettingsScreen(prefs = vm.prefs)
                2 -> DiagnosticsScreen(vm = vm)
                else -> StorageScreen(app = app)
            }
        }
    }
}

@Composable
private fun ModernHome(vm: TerminalViewModel) {
    val context = LocalContext.current
    val uiState by vm.ui.collectAsState()
    val sessions by vm.sessions.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.banner_experimental),
                modifier = Modifier.padding(12.dp),
            )
        }
        Button(onClick = {
            vm.newAndroidShell()
            TerminalService.start(context)
        }) {
            Text(stringResource(R.string.btn_android_shell))
        }
        Button(onClick = {
            vm.newDebianProot()
            TerminalService.start(context)
        }) {
            Text(stringResource(R.string.btn_debian_proot))
        }
        Button(onClick = { vm.runExecBrokerTest() }) {
            Text(stringResource(R.string.btn_exec_broker_test))
        }
        val qualification = uiState.qualification
        if (qualification != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.diag_app_data_exec) + ": " + qualification.appDataExec)
                    Text(stringResource(R.string.diag_nested_exec) + ": " + qualification.nestedExec)
                }
            }
        }
        Button(onClick = {
            val started = vm.newRootChroot()
            if (started) {
                TerminalService.start(context)
            } else {
                // Capability gate: private mount namespace unavailable.
            }
        }) {
            Text(stringResource(R.string.btn_root_chroot))
        }
        if (uiState.statusMessage.isNotEmpty()) {
            Text(uiState.statusMessage)
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.title_sessions) + ": " + sessions.size)
                if (sessions.isEmpty()) {
                    Text(stringResource(R.string.sessions_empty))
                } else {
                    for (session in sessions.values) {
                        Text(session.spec.title + " / " + session.state.value.name)
                    }
                }
            }
        }
    }
}
