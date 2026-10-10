package dev.studiorizi.mterm.full

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
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
import dev.studiorizi.mterm.core.terminal_emulator.TerminalSearch
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
import dev.studiorizi.mterm.full.backend.InboxBridge

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                InboxBridge.pending.trySend(uri)
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
    val activity = context as? ComponentActivity
    // Files shared from other apps (ACTION_VIEW) land in the isolated inbox,
    // then a Debian session opens so the guest can use them at /mnt/shared.
    LaunchedEffect(Unit) {
        activity?.intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { uri ->
            InboxBridge.pending.trySend(uri)
            activity.intent = Intent()
        }
        for (uri in InboxBridge.pending) {
            val imported = InboxBridge.importViewed(context.applicationContext, uri)
            imported.fold(
                onSuccess = {
                    vm.newDebianProot(context)
                    nav.navigate("home")
                },
                onFailure = {
                    vm.postError(it.message ?: it.toString())
                },
            )
        }
    }
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
                // Single source of truth for the selected tab: both panes
                // follow it (previously each kept its own and could diverge).
                val sessions by vm.sessions.collectAsState()
                var selectedTab by remember { mutableStateOf<String?>(null) }
                // Second visible session for split view (null = single pane).
                var splitId by remember { mutableStateOf<String?>(null) }
                // SSH connect dialog.
                var sshOpen by remember { mutableStateOf(false) }
                val ids = sessions.keys
                val selectedId =
                    if (selectedTab != null && selectedTab in ids) selectedTab
                    else ids.firstOrNull()
                // Drop the split when its session closes or equals primary.
                val activeSplit = splitId?.takeIf { it in ids && it != selectedId }
                if (sideBySide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            SessionListPane(
                                vm, gutter, selectedId,
                                onSelect = { selectedTab = it },
                                onClose = { id ->
                                    if (selectedTab == id) selectedTab = null
                                    if (splitId == id) splitId = null
                                    vm.stopSession(id)
                                },
                                onLinuxSetup = { nav.navigate("linux") },
                                onStorage = { nav.navigate("storage") },
                                onSsh = { sshOpen = true },
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SessionDetailPane(
                                vm, termHeight, palette, gutter, selectedId,
                                splitId = activeSplit,
                                onSplitChange = { splitId = it },
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        SessionListPane(
                            vm, gutter, selectedId,
                            onSelect = { selectedTab = it },
                            onClose = { id ->
                                if (selectedTab == id) selectedTab = null
                                if (splitId == id) splitId = null
                                vm.stopSession(id)
                            },
                            onLinuxSetup = { nav.navigate("linux") },
                            onStorage = { nav.navigate("storage") },
                            onSsh = { sshOpen = true },
                        )
                        SessionDetailPane(
                            vm, termHeight, palette, gutter, selectedId,
                            splitId = activeSplit,
                            onSplitChange = { splitId = it },
                        )
                    }
                }
                if (sshOpen) {
                    SshDialog(
                        vm = vm,
                        onDismiss = { sshOpen = false },
                        onConnected = { id ->
                            selectedTab = id
                            sshOpen = false
                        },
                    )
                }
                val trustReqs by vm.sshTrustRequests.collectAsState()
                trustReqs.firstOrNull()?.let { req ->
                    SshTrustDialog(vm = vm, req = req)
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
    selected: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onLinuxSetup: () -> Unit,
    onStorage: () -> Unit,
    onSsh: () -> Unit,
) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsState()

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
                        onClick = { onSelect(runtime.spec.id) },
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
            TButton(
                onClick = { selected?.let { onClose(it) } },
            ) {
                Text(stringResource(R.string.close_selected))
            }
        }
        Spacer(Modifier.height(8.dp))
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
            TButton(onClick = onStorage) {
                Text(stringResource(R.string.storage))
            }
            TButton(onClick = onSsh) {
                Text(stringResource(R.string.new_ssh))
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
    sessionId: String?,
    splitId: String?,
    onSplitChange: (String?) -> Unit,
) {
    val sessions by vm.sessions.collectAsState()
    val context = LocalContext.current
    val prefs = remember { MTermPrefs(context.applicationContext) }
    val fontSize by prefs.fontSize.collectAsState(initial = 14f)
    val extraKeys by prefs.extraKeys.collectAsState(initial = MTermPrefs.DEFAULT_EXTRA_KEYS)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(gutter)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalCard(
            vm = vm,
            sessionId = sessionId,
            termHeight = termHeight,
            palette = palette,
            fontSizeSp = fontSize,
            extraKeys = extraKeys,
            focused = true,
            withExtraKeys = true,
        )
        SplitRow(
            sessions = sessions,
            primaryId = sessionId,
            splitId = splitId,
            onSplitChange = onSplitChange,
        )
        if (splitId != null) {
            TerminalCard(
                vm = vm,
                sessionId = splitId,
                termHeight = termHeight / 2,
                palette = palette,
                fontSizeSp = fontSize,
                extraKeys = extraKeys,
                // The secondary pane focuses on tap through its own IME
                // field; requesting focus here would steal the keyboard.
                focused = false,
                withExtraKeys = false,
            )
        }
        StatusCards(sessions.size, vm.processCount())
    }
}

/** Split toggle plus the second-pane session picker. */
@Composable
private fun SplitRow(
    sessions: Map<String, dev.studiorizi.mterm.core.session_core.SessionRuntime>,
    primaryId: String?,
    splitId: String?,
    onSplitChange: (String?) -> Unit,
) {
    val others = sessions.keys.filter { it != primaryId }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TFilterChip(
            selected = splitId != null,
            onClick = {
                onSplitChange(if (splitId != null) null else others.firstOrNull())
            },
            label = { Text(stringResource(R.string.split)) },
        )
        if (splitId != null) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(others, key = { it }) { id ->
                    val title = sessions[id]?.spec?.title ?: id.take(4)
                    TFilterChip(
                        selected = splitId == id,
                        onClick = { onSplitChange(id) },
                        label = { Text(title) },
                    )
                }
            }
        }
    }
    if (splitId != null && others.isEmpty()) {
        Text(
            stringResource(R.string.split_need_two),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One terminal card: surface, find bar, links sheet, and (optionally) the
 * extra-keys row. Search state is keyed by session so switching tabs never
 * leaks a query into another session.
 */
@Composable
private fun TerminalCard(
    vm: TerminalViewModel,
    sessionId: String?,
    termHeight: Dp,
    palette: TerminalPalette,
    fontSizeSp: Float,
    extraKeys: String,
    focused: Boolean,
    withExtraKeys: Boolean,
) {
    val context = LocalContext.current
    val tick by vm.tick.collectAsState()

    var ctrlLatch by remember(sessionId) { mutableStateOf(false) }
    var altLatch by remember(sessionId) { mutableStateOf(false) }
    var searchOpen by remember(sessionId) { mutableStateOf(false) }
    var query by remember(sessionId) { mutableStateOf("") }
    var currentIdx by remember(sessionId) { mutableStateOf(0) }
    var revealSeq by remember(sessionId) { mutableStateOf(0) }
    var linksOpen by remember(sessionId) { mutableStateOf(false) }

    val emu = sessionId?.let { vm.emulator(it) }
    val hits = remember(tick, emu, query, searchOpen) {
        if (!searchOpen || query.isEmpty() || emu == null) {
            emptyList()
        } else {
            TerminalSearch.searchEmulator(emu, query)
        }
    }
    LaunchedEffect(query, sessionId) { currentIdx = 0 }
    val clampedIdx = if (hits.isEmpty()) 0 else currentIdx.coerceIn(0, hits.size - 1)
    val current = hits.getOrNull(clampedIdx)
    val highlightMap = remember(hits) {
        hits.groupBy({ it.line }, { it.startCol until it.endCol })
    }
    fun go(i: Int) {
        if (hits.isEmpty()) return
        val wrapped = ((i % hits.size) + hits.size) % hits.size
        currentIdx = wrapped
        revealSeq++
    }

    TCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.terminal), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TFilterChip(
                        selected = searchOpen,
                        onClick = { searchOpen = !searchOpen },
                        label = { Text(stringResource(R.string.find)) },
                    )
                    TFilterChip(
                        selected = false,
                        onClick = { linksOpen = true },
                        label = { Text(stringResource(R.string.links)) },
                    )
                }
            }
            if (searchOpen) {
                Spacer(Modifier.height(8.dp))
                FindBar(
                    query = query,
                    onQuery = { query = it },
                    count = if (hits.isEmpty()) "0/0" else "${clampedIdx + 1}/${hits.size}",
                    onPrev = { go(clampedIdx - 1) },
                    onNext = { go(clampedIdx + 1) },
                    onCopyLine = {
                        val line = current?.let { hit ->
                            emu?.let { TerminalSearch.snapshotLines(it).getOrNull(hit.line) }
                        }
                        if (line != null) {
                            copyText(context, line)
                        }
                    },
                    onClose = { searchOpen = false },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (sessionId == null) {
                Text(
                    stringResource(R.string.diag_status_runtime_required),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (emu == null) {
                Text(
                    stringResource(R.string.session_finished),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                TerminalView(
                    emulator = emu,
                    tick = tick,
                    fontSizeSp = fontSizeSp,
                    focused = focused,
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
                    highlights = highlightMap,
                    currentHighlight = current,
                    revealRequest = current?.let { it.line to revealSeq },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.tap_terminal_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (withExtraKeys) {
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
    }
    if (linksOpen && sessionId != null) {
        LinksDialog(
            vm = vm,
            tick = tick,
            onDismiss = { linksOpen = false },
        )
    }
}

@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    count: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCopyLine: () -> Unit,
    onClose: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.find_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(count, style = MaterialTheme.typography.bodySmall)
            TFilterChip(selected = false, onClick = onPrev, label = { Text("↑") })
            TFilterChip(selected = false, onClick = onNext, label = { Text("↓") })
            TFilterChip(selected = false, onClick = onCopyLine, label = { Text(stringResource(R.string.find_copy_line)) })
            TFilterChip(selected = false, onClick = onClose, label = { Text(stringResource(R.string.find_close)) })
        }
    }
}

/** URLs found in the session output; tap opens, ✎ copies. */
@Composable
private fun LinksDialog(
    vm: TerminalViewModel,
    tick: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // tick is read so the list follows output while open.
    @Suppress("UNUSED_EXPRESSION")
    tick.coerceAtLeast(0)
    val urls = remember(tick) {
        // The dialog sits above the card that owns the session; re-resolve
        // the visible session through the single selection is overkill —
        // scan every live emulator and merge (deduped, capped).
        vm.sessions.value.keys.flatMap { id ->
            val emu = vm.emulator(id) ?: return@flatMap emptyList<String>()
            TerminalSearch.urlsInEmulator(emu).map { it.url }
        }.distinct().take(30)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.links_title)) },
        text = {
            if (urls.isEmpty()) {
                Text(stringResource(R.string.links_empty))
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    urls.forEach { url ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                url,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TFilterChip(
                                selected = false,
                                onClick = { copyText(context, url) },
                                label = { Text(stringResource(R.string.copy)) },
                            )
                        }
                        TButton(
                            onClick = {
                                openUrl(context, vm, url)
                                onDismiss()
                            },
                        ) {
                            Text(
                                stringResource(R.string.open),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

private fun copyText(context: android.content.Context, text: String) {
    try {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("mterm", text))
        android.widget.Toast.makeText(context, context.getString(R.string.copy_done), android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
    }
}

private fun openUrl(context: android.content.Context, vm: TerminalViewModel, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        vm.postError(context.getString(R.string.link_open_failed, url))
    }
}

/**
 * SSH connect dialog. Passwords and key bytes stay in local variables and
 * are handed to the ViewModel as copies (the store owns wiping its own
 * buffers after auth; dialog buffers are GC'd, never persisted).
 */
@Composable
private fun SshDialog(
    vm: TerminalViewModel,
    onDismiss: () -> Unit,
    onConnected: (String) -> Unit,
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var keyName by remember { mutableStateOf<String?>(null) }
    var keyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var keyPassphrase by remember { mutableStateOf("") }
    val keyPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_SSH_KEY_BYTES) {
                    vm.postError(context.getString(R.string.ssh_invalid))
                    return@rememberLauncherForActivityResult
                }
                keyBytes = bytes
                keyName = uri.lastPathSegment?.substringAfterLast('/') ?: "key"
            }
        } catch (_: Exception) {
            vm.postError(context.getString(R.string.ssh_invalid))
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.TextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.ssh_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.TextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c in '0'..'9' }.take(5) },
                    label = { Text(stringResource(R.string.ssh_port)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.TextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.ssh_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ssh_password)) },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        keyName ?: stringResource(R.string.ssh_key_none),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TFilterChip(
                        selected = false,
                        onClick = { keyPicker.launch(arrayOf("*/*")) },
                        label = { Text(stringResource(R.string.ssh_pick_key)) },
                    )
                }
                if (keyBytes != null) {
                    androidx.compose.material3.TextField(
                        value = keyPassphrase,
                        onValueChange = { keyPassphrase = it },
                        label = { Text(stringResource(R.string.ssh_key_password)) },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: 22
                    val id = vm.newSsh(
                        context = context,
                        host = host,
                        port = port,
                        user = user,
                        password = password.takeIf { it.isNotEmpty() }?.toCharArray(),
                        keyPem = keyBytes,
                        keyPassphrase = keyPassphrase.takeIf { it.isNotEmpty() }?.toCharArray(),
                    )
                    // Drop dialog-local secret copies immediately.
                    password = ""
                    keyPassphrase = ""
                    keyBytes = null
                    if (id != null) onConnected(id)
                },
            ) {
                Text(stringResource(R.string.ssh_connect))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Host-key trust prompt for an in-progress SSH connect. */
@Composable
private fun SshTrustDialog(
    vm: TerminalViewModel,
    req: dev.studiorizi.mterm.full.backend.ssh.SshTrust.Request,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { /* must choose */ },
        title = { Text(stringResource(R.string.ssh_trust_title)) },
        text = {
            Text(stringResource(R.string.ssh_trust_message, req.host, req.port, req.fingerprint))
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    vm.decideSshTrust(
                        req.id,
                        dev.studiorizi.mterm.full.backend.ssh.SshTrust.Decision.ALWAYS,
                    )
                },
            ) {
                Text(stringResource(R.string.ssh_trust_always))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.decideSshTrust(
                            req.id,
                            dev.studiorizi.mterm.full.backend.ssh.SshTrust.Decision.DENY,
                        )
                    },
                ) {
                    Text(stringResource(R.string.ssh_deny))
                }
                androidx.compose.material3.TextButton(
                    onClick = {
                        vm.decideSshTrust(
                            req.id,
                            dev.studiorizi.mterm.full.backend.ssh.SshTrust.Decision.ONCE,
                        )
                    },
                ) {
                    Text(stringResource(R.string.ssh_trust_once))
                }
            }
        },
    )
}

private const val MAX_SSH_KEY_BYTES = 65536

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
