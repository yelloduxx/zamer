package dev.bezh.zamer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.Reading
import dev.bezh.zamer.domain.UnitSystem
import dev.bezh.zamer.domain.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zamer")

/** How stored JSON is read; unknown keys are tolerated so older app versions can read newer data. */
internal val StoreJson = Json { ignoreUnknownKeys = true }

/** The rangefinder this phone is paired with. [tokenHex] is the secret from the pairing handshake. */
data class PairedDevice(val address: String, val name: String, val tokenHex: String)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val units: UnitSystem = UnitSystem.METRIC,
    val decimals: Int = 3,
    val keepScreenOn: Boolean = true,
    val haptics: Boolean = true,
    val dynamicColor: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

@Serializable
data class HistoryEntry(
    val id: Long,
    val timestamp: Long,
    val mode: MeasureMode,
    val valuesMm: List<Int>,
    /** What the user says was measured, e.g. "Kitchen floor". */
    val title: String? = null,
)

class AppStore(private val context: Context) {
    private val store get() = context.dataStore
    private val json = StoreJson

    // ---- pairing ----

    val pairedDevice: Flow<PairedDevice?> = store.data.map { p ->
        val address = p[ADDRESS]
        val token = p[TOKEN]
        if (address != null && token != null) PairedDevice(address, p[NAME] ?: "", token) else null
    }.distinctUntilChanged() // store.data fires on every write; a repeat here would restart the connection

    suspend fun savePairedDevice(d: PairedDevice) {
        store.edit {
            it[ADDRESS] = d.address
            it[NAME] = d.name
            it[TOKEN] = d.tokenHex
        }
    }

    suspend fun clearPairedDevice() {
        store.edit {
            it.remove(ADDRESS)
            it.remove(NAME)
            it.remove(TOKEN)
        }
    }

    // ---- settings ----

    val settings: Flow<Settings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        store.edit { p ->
            val next = transform(p.toSettings())
            p[UNITS] = next.units.name
            p[DECIMALS] = next.decimals
            p[KEEP_ON] = next.keepScreenOn
            p[HAPTICS] = next.haptics
            p[DYNAMIC] = next.dynamicColor
            p[THEME] = next.theme.name
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            units = this[UNITS]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: d.units,
            decimals = (this[DECIMALS] ?: d.decimals).coerceIn(1, 3),
            keepScreenOn = this[KEEP_ON] ?: d.keepScreenOn,
            haptics = this[HAPTICS] ?: d.haptics,
            dynamicColor = this[DYNAMIC] ?: d.dynamicColor,
            theme = this[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
        )
    }

    // ---- history ----

    val history: Flow<List<HistoryEntry>> = store.data.map { decodeHistory(it[HISTORY]) }.distinctUntilChanged()

    suspend fun addHistory(entry: HistoryEntry) {
        store.edit { p ->
            val list = listOf(entry) + decodeHistory(p[HISTORY])
            p[HISTORY] = json.encodeToString(list.take(MAX_HISTORY))
        }
    }

    /** A blank [title] removes the name. */
    suspend fun renameHistory(id: Long, title: String) {
        val name = title.trim().ifEmpty { null }
        store.edit { p ->
            p[HISTORY] = json.encodeToString(decodeHistory(p[HISTORY]).map { if (it.id == id) it.copy(title = name) else it })
        }
    }

    suspend fun deleteHistory(id: Long) {
        store.edit { p -> p[HISTORY] = json.encodeToString(decodeHistory(p[HISTORY]).filterNot { it.id == id }) }
    }

    suspend fun clearHistory() {
        store.edit { it.remove(HISTORY) }
    }

    // ---- measuring ----

    val workspace: Flow<Workspace> = store.data.map { decodeWorkspace(it[WORKSPACE]) }.distinctUntilChanged()

    suspend fun updateWorkspace(transform: (Workspace) -> Workspace) {
        store.edit { p -> p[WORKSPACE] = json.encodeToString(transform(decodeWorkspace(p[WORKSPACE]))) }
    }

    private fun decodeWorkspace(raw: String?): Workspace =
        raw?.let { runCatching { json.decodeFromString<Workspace>(it) }.getOrNull() } ?: Workspace()

    private fun decodeHistory(raw: String?): List<HistoryEntry> = decodeList(raw)

    // ---- readings log, newest first ----

    val readings: Flow<List<Reading>> = store.data.map { decodeList<Reading>(it[READINGS]) }.distinctUntilChanged()

    suspend fun addReading(reading: Reading) {
        store.edit { p ->
            val list = decodeList<Reading>(p[READINGS])
            // An identical reading can't be told apart from the one already logged.
            if (reading !in list) p[READINGS] = json.encodeToString((listOf(reading) + list).take(MAX_READINGS))
        }
    }

    suspend fun deleteReading(reading: Reading) {
        store.edit { p -> p[READINGS] = json.encodeToString(decodeList<Reading>(p[READINGS]) - reading) }
    }

    suspend fun clearReadings() {
        store.edit { it.remove(READINGS) }
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> =
        raw?.let { runCatching { json.decodeFromString<List<T>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_HISTORY = 500
        const val MAX_READINGS = 200
        val ADDRESS = stringPreferencesKey("device_address")
        val NAME = stringPreferencesKey("device_name")
        val TOKEN = stringPreferencesKey("device_token")
        val UNITS = stringPreferencesKey("units")
        val DECIMALS = intPreferencesKey("decimals")
        val KEEP_ON = booleanPreferencesKey("keep_screen_on")
        val HAPTICS = booleanPreferencesKey("haptics")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val THEME = stringPreferencesKey("theme")
        val HISTORY = stringPreferencesKey("history_json")
        val WORKSPACE = stringPreferencesKey("workspace_json")
        val READINGS = stringPreferencesKey("readings_json")
    }
}
