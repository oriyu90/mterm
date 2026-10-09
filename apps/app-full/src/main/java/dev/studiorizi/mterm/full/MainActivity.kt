package dev.studiorizi.mterm.full

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.core.terminal_session.TerminalKeyEncoder
import dev.studiorizi.mterm.full.ui.AppTheme
import dev.studiorizi.mterm.full.ui.DiagnosticsScreen
import dev.studiorizi.mterm.full.ui.LinuxSetupScreen
import dev.studiorizi.mterm.full.ui.LocalRetro
import dev.studiorizi.mterm.full.ui.SettingsScreen
import dev.studiorizi.mterm.full.ui.StorageScreen
import dev.studiorizi.mterm.full.ui.TButton
import dev.studiorizi.mterm.full.ui.TCard
import dev.studiorizi.mterm.full.ui.TFilterChip
import dev.studiorizi.mterm.full.ui.TTopBar
import dev.studiorizi.mterm.full.ui.TerminalPalette
import dev.studiorizi.mterm.full.ui.TerminalView
import dev.studiorizi.mterm.full.ui.TerminalViewModel
import dev.studiorizi.mterm.full.ui.appColorScheme
import dev.studiorizi.mterm.full.ui.appShapes
import dev.studiorizi.mterm.full.ui.terminalPaletteFor

/**
 * Full Sideload MVP entry point.
 *
 * API 28 launches into the bilingual unsupported screen; API 29+ shows the
 * adaptive Compose tree. Layout adapts to width class *and* orientation
 * (landscape always splits so the terminal keeps its columns); the terminal
 * height follows the height class so phones, landscape bars, and tablets
 * each get a usable surface. Theme (system/light/dark/retro) and display
 * scale come from DataStore prefs. All labels come from string resources so
 * the system locale (JA/EN) applies automatically. Sessions run in
 * [dev.studiorizi.mterm.full.service.TerminalService] (bound here so PTYs
 * survive rotation); the UI never calls su/mount/proot directly.
 */
class MainActivity : ComponentActivity() {

    private val terminalViewModel: TerminalViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { MTermPrefs(applicationContext) }
            val themeKey by prefs.theme.collectAsState(initial = "system")
            val displayScale by prefs.displayScale.collectAsState(initial = 1.0f)
            val theme = AppTheme.of(themeKey)
            val systemDark = isSystemInDarkTheme()
            val dark = theme == AppTheme.DARK || (theme == AppTheme.SYSTEM && systemDark)
            val density = LocalDensity.current
            MaterialTheme(
                colorScheme = appColorScheme(theme, dark),
                shapes = appShapes(theme),
            ) {
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale * displayScale),
                    LocalRetro provides (theme == AppTheme.RETRO),
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        UnsupportedScreen(api = Build.VERSION.SDK_INT)
                    } else {
                        val windowSize = calculateWindowSizeClass(this)
                        MTermRoot(
                            windowSize = windowSize,
                            palette = terminalPaletteFor(theme),
                            vm = terminalViewModel,
                        )
                    }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun MTermRoot(
    windowSize: WindowSizeClass,
    palette: TerminalPalette,
    vm: TerminalViewModel = viewModel(),
) {
    val context = LocalContext.current
    DisposableEffect(context) {
        vm.bind(context)
        onDispose { vm.unbind(context) }
    }
    val nav = rememberNavController()
    val orientation = LocalConfiguration.current.orientation
    val sideBySide = windowSize.widthSizeClass == WindowWidthSizeClass.Expanded ||
        orientation == Configuration.ORIENTATION_LANDSCAPE
    // Terminal height follows the height class: compact landscape bars stay
    // usable, tablets get a tall surface. PTY rows track it via onResize.
    val termHeight = when (windowSize.heightSizeClass) {
        WindowHeightSizeClass.Compact -> 240.dp
        WindowHeightSizeClass.Medium -> 320.dp
        else -> 480.dp
    }
    // Adaptive gutters: wider insets on expanded widths/landscape.
    val gutter = if (windowSize.widthSizeClass == WindowWidthSizeClass.Expanded) 24.dp else 16.dp
    Scaffold(
        topBar = {
            TTopBar(
                title = stringResource(R.string.app_name),
                onDiagnostics = { nav.navigate("diagnostics") },
                onSettings = { nav.navigate("settings") },
            )
        },
        floatingActionButton = {
            FabHome(onClick = { nav.navigate("home") })
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            SessionListPane(vm, gutter) { nav.navigate("linux") }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SessionDetailPane(vm, termHeight, palette, gutter)
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SessionListPane(vm, gutter) { nav.navigate("linux") }
                        SessionDetailPane(vm, termHeight, palette, gutter)
                    }
                }
            }
            composable("settings") { SettingsScreen() }
            composable("storage") { StorageScreen() }
            composable("diagnostics") { DiagnosticsScreen(vm) }
            composable("linux") {
                LinuxSetupScreen(
                    onOpenTerminal = {
                        vm.newDebianProot(context)
                        nav.navigate("home")
                    },
                )
            }
        }
    }
}

@Composable
private fun FabHome(onClick: () -> Unit) {
    if (LocalRetro.current) {
        TButton(onClick = onClick) { Text("+") }
    } else {
        FloatingActionButton(onClick = onClick) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_session))
        }
    }
}

@Composable
private fun SessionListPane(
    vm: TerminalViewModel,
    gutter: Dp,
    onLinuxSetup: () -> Unit,
) {
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

    Column(
        modifier = Modifier
            .padding(gutter)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.sessions), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text(stringResource(R.string.session_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions.values.toList(), key = { it.spec.id }) { runtime ->
                    val state by runtime.state.collectAsState()
                    TFilterChip(
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
            TButton(onClick = { vm.newAndroidShell(context) }) {
                Text(stringResource(R.string.new_android_shell))
            }
            TButton(onClick = { vm.newDebianProot(context) }) {
                Text(stringResource(R.string.new_debian_proot))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TButton(
                onClick = { vm.newRootChroot(context) },
                enabled = vm.rootChrootSupported.collectAsState().value == true,
            ) {
                Text(stringResource(R.string.new_root_chroot))
            }
            TButton(onClick = { vm.stopAll(context) }) {
                Text(stringResource(R.string.stop_all))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TButton(onClick = onLinuxSetup) {
                Text(stringResource(R.string.linux_setup))
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
private fun SessionDetailPane(
    vm: TerminalViewModel,
    termHeight: Dp,
    palette: TerminalPalette,
    gutter: Dp,
) {
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
            .padding(gutter)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TCard(modifier = Modifier.fillMaxWidth()) {
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
                                .height(termHeight),
                            palette = palette,
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
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(keys) { key ->
            val active = (key.equals("CTRL", true) && ctrlActive) ||
                (key.equals("ALT", true) && altActive)
            TFilterChip(
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
        TCard(modifier = Modifier.fillMaxWidth()) {
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
