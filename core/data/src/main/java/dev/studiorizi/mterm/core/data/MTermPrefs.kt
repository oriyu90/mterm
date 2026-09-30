package dev.studiorizi.mterm.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mtermDataStore by preferencesDataStore(name = "mterm_prefs")

/** UI / terminal settings and feature flags backed by DataStore preferences. */
class MTermPrefs(private val context: Context) {

    val theme: Flow<String> = context.mtermDataStore.data.map { it[Keys.THEME] ?: "system" }
    val fontSize: Flow<Float> = context.mtermDataStore.data.map { it[Keys.FONT_SIZE] ?: 14f }
    val scrollback: Flow<Int> = context.mtermDataStore.data.map { it[Keys.SCROLLBACK] ?: 10_000 }
    val extraKeys: Flow<String> =
        context.mtermDataStore.data.map { it[Keys.EXTRA_KEYS] ?: DEFAULT_EXTRA_KEYS }
    val bridgeSensitive: Flow<Boolean> =
        context.mtermDataStore.data.map { it[Keys.BRIDGE_SENSITIVE] ?: false }

    suspend fun setTheme(value: String) {
        context.mtermDataStore.edit { it[Keys.THEME] = value }
    }

    suspend fun setFontSize(value: Float) {
        context.mtermDataStore.edit { it[Keys.FONT_SIZE] = value }
    }

    suspend fun setScrollback(value: Int) {
        context.mtermDataStore.edit { it[Keys.SCROLLBACK] = value }
    }

    suspend fun setExtraKeys(value: String) {
        context.mtermDataStore.edit { it[Keys.EXTRA_KEYS] = value }
    }

    suspend fun setBridgeSensitive(value: Boolean) {
        context.mtermDataStore.edit { it[Keys.BRIDGE_SENSITIVE] = value }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val SCROLLBACK = intPreferencesKey("scrollback")
        val EXTRA_KEYS = stringPreferencesKey("extra_keys")
        val BRIDGE_SENSITIVE = booleanPreferencesKey("bridge_sensitive")
    }

    companion object {
        const val DEFAULT_EXTRA_KEYS = "ESC|TAB|CTRL|ALT|LEFT|UP|DOWN|RIGHT"
    }
}
