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
import dev.bezh.zamer.domain.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zamer")

/** The rangefinder this phone is paired with. [tokenHex] is the secret from the pairing handshake. */
data class PairedDevice(val address: String, val name: String, val tokenHex: String)

data class Settings(
    val units: UnitSystem = UnitSystem.METRIC,
    val decimals: Int = 3,
    val keepScreenOn: Boolean = true,
    val haptics: Boolean = true,
    val dynamicColor: Boolean = true,
)

@Serializable
data class HistoryEntry(
    val id: Long,
    val timestamp: Long,
    val mode: MeasureMode,
    val valuesMm: List<Int>,
)

class AppStore(private val context: Context) {
    private val store get() = context.dataStore
    private val json = Json { ignoreUnknownKeys = true }

    // ---- pairing ----

    val pairedDevice: Flow<PairedDevice?> = store.data.map { p ->
        val address = p[ADDRESS]
        val token = p[TOKEN]
        if (address != null && token != null) PairedDevice(address, p[NAME] ?: "", token) else null
    }

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

    val settings: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(
            units = p[UNITS]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: d.units,
            decimals = (p[DECIMALS] ?: d.decimals).coerceIn(1, 3),
            keepScreenOn = p[KEEP_ON] ?: d.keepScreenOn,
            haptics = p[HAPTICS] ?: d.haptics,
            dynamicColor = p[DYNAMIC] ?: d.dynamicColor,
        )
    }

    suspend fun updateSettings(transform: (Settings) -> Settings) {
        store.edit { p ->
            val current = Settings(
                units = p[UNITS]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC,
                decimals = p[DECIMALS] ?: 3,
                keepScreenOn = p[KEEP_ON] ?: true,
                haptics = p[HAPTICS] ?: true,
                dynamicColor = p[DYNAMIC] ?: true,
            )
            val next = transform(current)
            p[UNITS] = next.units.name
            p[DECIMALS] = next.decimals
            p[KEEP_ON] = next.keepScreenOn
            p[HAPTICS] = next.haptics
            p[DYNAMIC] = next.dynamicColor
        }
    }

    // ---- history ----

    val history: Flow<List<HistoryEntry>> = store.data.map { decodeHistory(it[HISTORY]) }

    suspend fun addHistory(entry: HistoryEntry) {
        store.edit { p ->
            val list = listOf(entry) + decodeHistory(p[HISTORY])
            p[HISTORY] = json.encodeToString(list.take(MAX_HISTORY))
        }
    }

    suspend fun deleteHistory(id: Long) {
        store.edit { p -> p[HISTORY] = json.encodeToString(decodeHistory(p[HISTORY]).filterNot { it.id == id }) }
    }

    suspend fun clearHistory() {
        store.edit { it.remove(HISTORY) }
    }

    private fun decodeHistory(raw: String?): List<HistoryEntry> =
        raw?.let { runCatching { json.decodeFromString<List<HistoryEntry>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_HISTORY = 500
        val ADDRESS = stringPreferencesKey("device_address")
        val NAME = stringPreferencesKey("device_name")
        val TOKEN = stringPreferencesKey("device_token")
        val UNITS = stringPreferencesKey("units")
        val DECIMALS = intPreferencesKey("decimals")
        val KEEP_ON = booleanPreferencesKey("keep_screen_on")
        val HAPTICS = booleanPreferencesKey("haptics")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val HISTORY = stringPreferencesKey("history_json")
    }
}
