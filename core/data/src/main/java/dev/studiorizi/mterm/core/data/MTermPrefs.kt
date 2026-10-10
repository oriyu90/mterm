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
    val displayScale: Flow<Float> =
        context.mtermDataStore.data.map { it[Keys.DISPLAY_SCALE] ?: 1.0f }
    val wifiOnlyDownload: Flow<Boolean> =
        context.mtermDataStore.data.map { it[Keys.WIFI_ONLY_DOWNLOAD] ?: true }
    val autoMirrorSync: Flow<Boolean> =
        context.mtermDataStore.data.map { it[Keys.AUTO_MIRROR_SYNC] ?: true }
    val lastAutoTune: Flow<String> =
        context.mtermDataStore.data.map { it[Keys.LAST_AUTO_TUNE] ?: "" }
    /** Persisted SAF tree URI for the shared-folder mirror (empty = none). */
    val safTreeUri: Flow<String> =
        context.mtermDataStore.data.map { it[Keys.SAF_TREE_URI] ?: "" }
    /** Mount id derived from [safTreeUri] (empty = none). */
    val safMountId: Flow<String> =
        context.mtermDataStore.data.map { it[Keys.SAF_MOUNT_ID] ?: "" }

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

    suspend fun setDisplayScale(value: Float) {
        context.mtermDataStore.edit { it[Keys.DISPLAY_SCALE] = value.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setWifiOnlyDownload(value: Boolean) {
        context.mtermDataStore.edit { it[Keys.WIFI_ONLY_DOWNLOAD] = value }
    }

    suspend fun setAutoMirrorSync(value: Boolean) {
        context.mtermDataStore.edit { it[Keys.AUTO_MIRROR_SYNC] = value }
    }

    suspend fun setLastAutoTune(value: String) {
        context.mtermDataStore.edit { it[Keys.LAST_AUTO_TUNE] = value.take(2000) }
    }

    suspend fun setSafTree(treeUri: String, mountId: String) {
        context.mtermDataStore.edit {
            it[Keys.SAF_TREE_URI] = treeUri.take(2000)
            it[Keys.SAF_MOUNT_ID] = mountId.take(64)
        }
    }

    suspend fun clearSafTree() {
        context.mtermDataStore.edit {
            it.remove(Keys.SAF_TREE_URI)
            it.remove(Keys.SAF_MOUNT_ID)
        }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val SCROLLBACK = intPreferencesKey("scrollback")
        val EXTRA_KEYS = stringPreferencesKey("extra_keys")
        val BRIDGE_SENSITIVE = booleanPreferencesKey("bridge_sensitive")
        val DISPLAY_SCALE = floatPreferencesKey("display_scale")
        val WIFI_ONLY_DOWNLOAD = booleanPreferencesKey("wifi_only_download")
        val AUTO_MIRROR_SYNC = booleanPreferencesKey("auto_mirror_sync")
        val LAST_AUTO_TUNE = stringPreferencesKey("last_auto_tune")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val SAF_MOUNT_ID = stringPreferencesKey("saf_mount_id")
    }

    companion object {
        const val DEFAULT_EXTRA_KEYS = "ESC|TAB|CTRL|ALT|LEFT|UP|DOWN|RIGHT"

        /** Display-scale choices offered in Settings (persisted value + label). */
        val DISPLAY_SCALE_CHOICES = listOf(0.85f, 1.0f, 1.15f, 1.3f)
    }
}
