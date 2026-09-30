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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.data.MTermPrefs
import dev.studiorizi.mterm.modern.R
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(prefs: MTermPrefs) {
    val scope = rememberCoroutineScope()
    val theme by prefs.theme.collectAsState(initial = "system")
    val fontSize by prefs.fontSize.collectAsState(initial = 14f)
    val scrollback by prefs.scrollback.collectAsState(initial = 10_000)
    val extraKeys by prefs.extraKeys.collectAsState(initial = MTermPrefs.DEFAULT_EXTRA_KEYS)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.pref_theme) + ": " + theme)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in listOf("system", "light", "dark")) {
                        Button(onClick = { scope.launch { prefs.setTheme(choice) } }) {
                            Text(choice)
                        }
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.pref_font_size) + ": " + fontSize)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { prefs.setFontSize((fontSize - 1f).coerceAtLeast(8f)) } }) {
                        Text("-")
                    }
                    Button(onClick = { scope.launch { prefs.setFontSize((fontSize + 1f).coerceAtMost(32f)) } }) {
                        Text("+")
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.pref_scrollback) + ": " + scrollback)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in listOf(10_000, 50_000, 100_000)) {
                        Button(onClick = { scope.launch { prefs.setScrollback(choice) } }) {
                            Text(choice.toString())
                        }
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(R.string.pref_extra_keys))
                Text(extraKeys)
            }
        }
    }
}
