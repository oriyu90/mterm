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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.core.session_core.SessionState
import dev.studiorizi.mterm.core.terminal_session.TerminalKeyEncoder
import dev.studiorizi.mterm.full.ui.DiagnosticsScreen
import dev.studiorizi.mterm.full.ui.SettingsScreen
import dev.studiorizi.mterm.full.ui.StorageScreen
import dev.studiorizi.mterm.full.ui.TerminalView
import dev.studiorizi.mterm.full.ui.TerminalViewModel

/**
 * Full Sideload MVP entry point.
 *
 * API 28 launches into the bilingual unsupported screen; API 29+ shows the
 * adaptive Compose tree (1-pane on compact, 2-pane on expanded). All labels
 * come from string resources so the system locale (JA/EN) applies
 * automatically. Sessions run in [dev.studiorizi.mterm.full.service.TerminalService]
 * (bound here so PTYs survive rotation); the UI never calls su/mount/proot
 * directly.
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

    override fun onDestroy() {
        super.onDestroy()
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
    val context = LocalContext.current
    DisposableEffect(context) {
        vm.bind(context)
        onDispose { vm.unbind(context) }
    }
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
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    // Keep selection valid as sessions come and go.
    val ids = sessions.keys
    if (selected != null && selected !in ids) {
        selected = ids.firstOrNull()
    }
    if (selected == null && ids.isNotEmpty()) {
        selected = ids.firstOrNull()
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.sessions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.session_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions.values.toList(), key = { it.spec.id }) { runtime ->
                    val state by runtime.state.collectAsState()
                    FilterChip(
                        selected = selected == runtime.spec.id,
                        onClick = { selected = runtime.spec.id },
                        label = { Text("${runtime.spec.title} · ${state.name}") },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val selectedState = selected?.let { sessions[it]?.state?.collectAsState()?.value }
        Text(
            stringResource(R.string.session_state) + ": " +
                (selectedState?.name ?: "-"),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.newAndroidShell(context) }) {
                Text(stringResource(R.string.new_android_shell))
            }
            Button(onClick = { vm.newDebianProot(context) }) {
                Text(stringResource(R.string.new_debian_proot))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.newRootChroot(context) },
                enabled = vm.rootChrootSupported.collectAsState().value == true,
            ) {
                Text(stringResource(R.string.new_root_chroot))
            }
            Button(onClick = { vm.stopAll(context) }) {
                Text(stringResource(R.string.stop_all))
            }
        }
        vm.lastError.collectAsState().value?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        vm.lastExit.collectAsState().value?.let { exit ->
            Spacer(Modifier.height(8.dp))
            Text(exit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SessionDetailPane(vm: TerminalViewModel) {
    val sessions by vm.sessions.collectAsState()
    val tick by vm.tick.collectAsState()
    val context = LocalContext.current
    val prefs = remember { MTermPrefs(context.applicationContext) }
    val fontSize by prefs.fontSize.collectAsState(initial = 14f)
    val extraKeys by prefs.extraKeys.collectAsState(initial = MTermPrefs.DEFAULT_EXTRA_KEYS)

    var selected by remember { mutableStateOf<String?>(null) }
    val ids = sessions.keys
    if (selected != null && selected !in ids) selected = ids.firstOrNull()
    if (selected == null && ids.isNotEmpty()) selected = ids.firstOrNull()
    val sessionId = selected

    var ctrlLatch by remember { mutableStateOf(false) }
    var altLatch by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.terminal), style = MaterialTheme.typography.titleMedium)
                if (sessionId == null) {
                    Text(
                        stringResource(R.string.diag_status_runtime_required),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val emu = vm.emulator(sessionId)
                    if (emu == null) {
                        Text(
                            stringResource(R.string.session_finished),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        TerminalView(
                            emulator = emu,
                            tick = tick,
                            fontSizeSp = fontSize,
                            focused = true,
                            onInput = { bytes ->
                                val out = applyLatches(bytes, ctrlLatch, altLatch)
                                if (ctrlLatch) ctrlLatch = false
                                if (altLatch) altLatch = false
                                vm.write(sessionId, out)
                            },
                            onResize = { rows, cols -> vm.resize(sessionId, rows, cols) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.tap_terminal_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        ExtraKeysRow(
            extraKeys = extraKeys,
            ctrlActive = ctrlLatch,
            altActive = altLatch,
            onToken = { token ->
                val id = sessionId ?: return@ExtraKeysRow
                when (token.uppercase()) {
                    "CTRL" -> ctrlLatch = !ctrlLatch
                    "ALT" -> altLatch = !altLatch
                    else -> {
                        val bytes = TerminalKeyEncoder.token(token) ?: return@ExtraKeysRow
                        val out = applyLatches(bytes, ctrlLatch, altLatch)
                        ctrlLatch = false
                        altLatch = false
                        vm.write(id, out)
                    }
                }
            },
        )
        StatusCards(sessions.size, vm.processCount())
    }
}

/** Applies latched CTRL/ALT modifiers to raw input bytes. */
private fun applyLatches(bytes: ByteArray, ctrl: Boolean, alt: Boolean): ByteArray {
    var out = bytes
    if (ctrl && out.size == 1) {
        val mapped = TerminalKeyEncoder.control(out[0].toInt().toChar())
        if (mapped != null) out = mapped
    }
    if (alt) {
        out = byteArrayOf(0x1B) + out
    }
    return out
}

@Composable
private fun ExtraKeysRow(
    extraKeys: String,
    ctrlActive: Boolean,
    altActive: Boolean,
    onToken: (String) -> Unit,
) {
    val keys = remember(extraKeys) { TerminalKeyEncoder.parseList(extraKeys) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(keys) { key ->
            val active = (key.equals("CTRL", true) && ctrlActive) ||
                (key.equals("ALT", true) && altActive)
            FilterChip(
                selected = active,
                onClick = { onToken(key) },
                label = { Text(key) },
            )
        }
    }
}

@Composable
private fun StatusCards(sessionCount: Int, processCount: Int) {
    val cards = listOf(
        stringResource(R.string.android_shell) to stringResource(R.string.status_ready),
        stringResource(R.string.debian) to stringResource(R.string.diag_status_runtime_required),
        stringResource(R.string.root_chroot) to stringResource(R.string.diag_status_unsupported),
        stringResource(R.string.diagnostics) to "$sessionCount",
        stringResource(R.string.diag_process_count) to "$processCount",
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
