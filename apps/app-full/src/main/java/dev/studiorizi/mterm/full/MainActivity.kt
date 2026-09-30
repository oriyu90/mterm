package dev.studiorizi.mterm.full

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.studiorizi.mterm.full.service.TerminalService
import dev.studiorizi.mterm.full.ui.DiagnosticsScreen
import dev.studiorizi.mterm.full.ui.SettingsScreen
import dev.studiorizi.mterm.full.ui.StorageScreen
import dev.studiorizi.mterm.full.ui.TerminalViewModel

/**
 * Full Sideload MVP entry point.
 *
 * API 28 launches into the bilingual unsupported screen; API 29+ shows the
 * adaptive Compose tree (1-pane on compact, 2-pane on expanded). All labels
 * come from string resources so the system locale (JA/EN) applies
 * automatically. Sessions are created via [TerminalViewModel] use-cases and
 * served by [TerminalService]; the UI never calls su/mount/proot directly.
 */
class MainActivity : ComponentActivity() {

    private val terminalViewModel: TerminalViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    UnsupportedScreen(api = Build.VERSION.SDK_INT)
                } else {
                    val windowSize = calculateWindowSizeClass(this)
                    val expanded = windowSize.widthSizeClass == WindowWidthSizeClass.Expanded
                    MTermRoot(expanded = expanded, vm = terminalViewModel)
                }
            }
        }
    }
}

@Composable
private fun UnsupportedScreen(api: Int) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.unsupported_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.unsupported_message, api))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MTermRoot(expanded: Boolean, vm: TerminalViewModel = viewModel()) {
    val nav = rememberNavController()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { nav.navigate("diagnostics") }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.diagnostics))
                    }
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("home") }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_session))
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                if (expanded) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) { SessionListPane(vm) }
                        Box(modifier = Modifier.weight(1f)) { SessionDetailPane(vm) }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SessionListPane(vm)
                        SessionDetailPane(vm)
                    }
                }
            }
            composable("settings") { SettingsScreen() }
            composable("storage") { StorageScreen() }
            composable("diagnostics") { DiagnosticsScreen(vm) }
        }
    }
}

@Composable
private fun SessionListPane(vm: TerminalViewModel) {
    val sessions by vm.sessions.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.sessions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.session_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions.values.toList(), key = { it.spec.id }) { runtime ->
                    FilterChip(
                        selected = selected == runtime.spec.id,
                        onClick = { selected = runtime.spec.id },
                        label = { Text(runtime.spec.title) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.session_state) + ": " +
                (selected?.let { sessions[it]?.state?.collectAsState()?.value } ?: "-"),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.newAndroidShell() }) {
                Text(stringResource(R.string.new_android_shell))
            }
            Button(onClick = { vm.newDebianProot() }) {
                Text(stringResource(R.string.new_debian_proot))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.newRootChroot() },
                enabled = vm.rootChrootSupported.collectAsState().value == true,
            ) {
                Text(stringResource(R.string.new_root_chroot))
            }
            Button(onClick = { vm.stopAll() }) {
                Text(stringResource(R.string.stop_all))
            }
        }
        vm.lastError.collectAsState().value?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SessionDetailPane(vm: TerminalViewModel) {
    val sessions by vm.sessions.collectAsState()
    var input by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            // MVP terminal surface placeholder: the real surface binds the
            // TerminalEmulator buffer via TerminalService on device.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.terminal), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.diag_status_ready),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.terminal)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            ExtraKeysRow()
        }
        item {
            StatusCards(sessions.size)
        }
    }
}

@Composable
private fun ExtraKeysRow() {
    val keys = listOf("ESC", "TAB", "CTRL", "ALT", "←", "↑", "↓", "→")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(keys) { key ->
            FilterChip(selected = false, onClick = {}, label = { Text(key) })
        }
    }
}

@Composable
private fun StatusCards(sessionCount: Int) {
    val cards = listOf(
        stringResource(R.string.android_shell) to stringResource(R.string.status_ready),
        stringResource(R.string.debian) to stringResource(R.string.diag_status_runtime_required),
        stringResource(R.string.root_chroot) to stringResource(R.string.diag_status_unsupported),
        stringResource(R.string.diagnostics) to "$sessionCount",
    )
    cards.forEach { (title, status) ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
