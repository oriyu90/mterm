package dev.studiorizi.mterm.full.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import dev.studiorizi.mterm.full.R
import kotlinx.coroutines.launch

/**
 * Theme / font / scrollback / extra-keys editor backed by DataStore.
 * All labels come from string resources (no hardcoded JA/EN).
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { MTermPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val theme by prefs.theme.collectAsState(initial = "system")
    val fontSize by prefs.fontSize.collectAsState(initial = 14f)
    val scrollback by prefs.scrollback.collectAsState(initial = 10_000)
    val extraKeys by prefs.extraKeys.collectAsState(initial = MTermPrefs.DEFAULT_EXTRA_KEYS)

    var fontDraft by remember(fontSize) { mutableFloatStateOf(fontSize) }
    var extraDraft by remember(extraKeys) { mutableStateOf(extraKeys) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(
                "system" to stringResource(R.string.theme_system),
                "light" to stringResource(R.string.theme_light),
                "dark" to stringResource(R.string.theme_dark),
            ).forEach { (value, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = theme == value,
                        onCheckedChange = { scope.launch { prefs.setTheme(value) } },
                    )
                    Text(label, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
                }
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
        )
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.scrollback) + ": $scrollback",
            style = MaterialTheme.typography.titleMedium,
        )
        Row {
            listOf(1_000, 10_000, 100_000).forEach { option ->
                Button(
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
        Button(onClick = { scope.launch { prefs.setExtraKeys(extraDraft) } }) {
            Text(stringResource(R.string.save))
        }
    }
}
