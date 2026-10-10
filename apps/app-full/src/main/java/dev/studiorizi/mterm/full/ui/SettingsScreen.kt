package dev.studiorizi.mterm.full.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.core.diagnostics.AutoTune
import dev.studiorizi.mterm.full.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Theme / display scale / font / scrollback / extra-keys / auto-tune editor.
 * All labels come from string resources (no hardcoded JA/EN).
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { MTermPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val themeKey by prefs.theme.collectAsState(initial = "system")
    val theme = AppTheme.of(themeKey)
    val displayScale by prefs.displayScale.collectAsState(initial = 1.0f)
    val fontSize by prefs.fontSize.collectAsState(initial = 14f)
    val scrollback by prefs.scrollback.collectAsState(initial = 10_000)
    val extraKeys by prefs.extraKeys.collectAsState(initial = MTermPrefs.DEFAULT_EXTRA_KEYS)
    val wifiOnly by prefs.wifiOnlyDownload.collectAsState(initial = true)
    val autoMirror by prefs.autoMirrorSync.collectAsState(initial = true)
    val lastTune by prefs.lastAutoTune.collectAsState(initial = "")

    var fontDraft by remember(fontSize) { mutableFloatStateOf(fontSize) }
    var extraDraft by remember(extraKeys) { mutableStateOf(extraKeys) }
    var tuning by remember { mutableStateOf(false) }
    var tuneReport by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppTheme.values().toList()) { option ->
                TFilterChip(
                    selected = theme == option,
                    onClick = { scope.launch { prefs.setTheme(option.key) } },
                    label = { Text(themeLabel(option)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.display_scale) + ": ${(displayScale * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            MTermPrefs.DISPLAY_SCALE_CHOICES.forEach { choice ->
                TFilterChip(
                    selected = displayScale == choice,
                    onClick = { scope.launch { prefs.setDisplayScale(choice) } },
                    modifier = Modifier.padding(end = 8.dp),
                    label = { Text("${(choice * 100).toInt()}%") },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.font_size) + ": ${fontDraft.toInt()}",
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = fontDraft,
            onValueChange = { fontDraft = it },
            onValueChangeFinished = { scope.launch { prefs.setFontSize(fontDraft) } },
            valueRange = 8f..32f,
            colors = retroAwareSliderColors(),
        )
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.scrollback) + ": $scrollback",
            style = MaterialTheme.typography.titleMedium,
        )
        Row {
            listOf(1_000, 10_000, 100_000).forEach { option ->
                TButton(
                    onClick = { scope.launch { prefs.setScrollback(option) } },
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text("$option")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.extra_keys), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.extra_keys_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = extraDraft,
            onValueChange = { extraDraft = it },
            label = { Text(stringResource(R.string.extra_keys_edit_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        TButton(onClick = { scope.launch { prefs.setExtraKeys(extraDraft) } }) {
            Text(stringResource(R.string.save))
        }
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.tune_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.tune_desc), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                TSwitch(
                    checked = wifiOnly,
                    onCheckedChange = { scope.launch { prefs.setWifiOnlyDownload(it) } },
                    label = { Text(stringResource(R.string.tune_wifi_only)) },
                )
                TSwitch(
                    checked = autoMirror,
                    onCheckedChange = { scope.launch { prefs.setAutoMirrorSync(it) } },
                    label = { Text(stringResource(R.string.tune_auto_mirror)) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TButton(
            onClick = {
                if (!tuning) {
                    tuning = true
                    scope.launch {
                        try {
                            val caps = collectCaps(context.applicationContext)
                            val decision = AutoTune.decide(caps)
                            prefs.setWifiOnlyDownload(decision.wifiOnlyDownload)
                            prefs.setAutoMirrorSync(decision.autoMirrorSync)
                            prefs.setScrollback(decision.scrollbackLines)
                            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                                .format(Date())
                            prefs.setLastAutoTune(stamp)
                            tuneReport = decision.noteKeys.joinToString("\n") { key ->
                                tuneNote(context, key, decision.scrollbackLines)
                            }
                        } finally {
                            tuning = false
                        }
                    }
                }
            },
        ) {
            Text(
                if (tuning) {
                    stringResource(R.string.tune_running)
                } else {
                    stringResource(R.string.tune_run)
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.battery_title),
            style = MaterialTheme.typography.titleMedium,
        )
        val applied = lastTune.ifEmpty { null }
        tuneReport?.let { report ->
            Spacer(Modifier.height(8.dp))
            if (applied != null) {
                Text(
                    stringResource(R.string.tune_applied, applied),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(report, style = MaterialTheme.typography.bodySmall)
        } ?: run {
            if (applied != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tune_applied, applied),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Battery-optimization exemption for long-running sessions/servers.
 * Requesting is optional and refusal changes nothing: the foreground
 * service keeps working, only Doze may idle background network sooner.
 */
@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val pm = remember {
        context.applicationContext.getSystemService(android.os.PowerManager::class.java)
    }
    var exempt by remember { mutableStateOf<Boolean?>(null) }
    fun refresh() {
        exempt = try {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            null
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }
    val requester = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }
    Text(
        stringResource(R.string.battery_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        when (exempt) {
            true -> stringResource(R.string.battery_unrestricted)
            false -> stringResource(R.string.battery_restricted)
            null -> stringResource(R.string.battery_unknown)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    TButton(
        onClick = {
            val intent = try {
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + context.packageName),
                )
            } catch (_: Exception) {
                null
            } ?: android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            )
            try {
                requester.launch(intent)
            } catch (_: Exception) {
            }
            refresh()
        },
    ) {
        Text(stringResource(R.string.battery_request))
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.battery_note),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun themeLabel(theme: AppTheme): String = stringResource(
    when (theme) {
        AppTheme.SYSTEM -> R.string.theme_system
        AppTheme.LIGHT -> R.string.theme_light
        AppTheme.DARK -> R.string.theme_dark
        AppTheme.RETRO -> R.string.theme_retro
    },
)

private fun tuneNote(context: android.content.Context, key: String, scrollback: Int): String = when (key) {
    "offline" -> context.getString(R.string.tune_note_offline)
    "net_ok" -> context.getString(R.string.tune_note_net_ok)
    "net_unvalidated" -> context.getString(R.string.tune_note_net_unvalidated)
    "metered" -> context.getString(R.string.tune_note_metered)
    "no_saf_tree" -> context.getString(R.string.tune_note_no_saf_tree)
    "low_ram" -> context.getString(R.string.tune_note_low_ram, scrollback)
    "low_storage" -> context.getString(R.string.tune_note_low_storage)
    "page_size_warn" -> context.getString(R.string.tune_note_page_size_warn)
    else -> key
}
