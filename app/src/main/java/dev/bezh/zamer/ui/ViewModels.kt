package dev.bezh.zamer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.ble.FoundDevice
import dev.bezh.zamer.ble.LinkState
import dev.bezh.zamer.ble.PairOutcome
import dev.bezh.zamer.data.HistoryEntry
import dev.bezh.zamer.data.Settings
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.MeasureResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        MeasureViewModel::class.java -> MeasureViewModel(container)
        PairingViewModel::class.java -> PairingViewModel(container)
        else -> error("Unknown ViewModel $modelClass")
    } as T
}

// ---------------------------------------------------------------- measuring

/**
 * A set of readings being collected for the chosen [mode]. Once every slot is filled the set is [complete];
 * [result] is null for a complete set that describes no real shape.
 */
data class MeasureUiState(
    val mode: MeasureMode = MeasureMode.AREA,
    val slots: List<Int?> = List(MeasureMode.AREA.sides.size) { null },
    val active: Int = 0,
    val complete: Boolean = false,
    val result: MeasureResult? = null,
    val lastMm: Int? = null,
    /** Increments with every reading so the UI can react (haptics, animation). */
    val readingCount: Int = 0,
    val link: LinkState = LinkState.Searching,
    val settings: Settings = Settings(),
) {
    val canUndo get() = slots.any { it != null }
}

class MeasureViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(MeasureUiState())

    val ui: StateFlow<MeasureUiState> = combine(local, c.repository.state, c.store.settings) { s, link, settings ->
        s.copy(link = link, settings = settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasureUiState())

    init {
        viewModelScope.launch { c.repository.readings.collect { onReading(it.millimetres) } }
    }

    private fun onReading(mm: Int) {
        var completed: MeasureResult? = null
        local.update { s ->
            // A new reading after a finished set starts the next set.
            val base = if (s.complete) List(s.mode.sides.size) { null } else s.slots
            val start = if (s.complete) 0 else s.active
            val slots = base.toMutableList().also { it[start] = mm }
            val next = slots.indexOfFirst { it == null }
            val result = if (next == -1) MeasureResult.of(s.mode, slots.filterNotNull()) else null
            completed = result
            s.copy(
                slots = slots,
                active = if (next == -1) start else next,
                complete = next == -1,
                result = result,
                lastMm = mm,
                readingCount = s.readingCount + 1,
            )
        }
        completed?.let { r ->
            viewModelScope.launch {
                c.store.addHistory(HistoryEntry(System.nanoTime(), System.currentTimeMillis(), r.mode, r.valuesMm))
            }
        }
    }

    fun setMode(mode: MeasureMode) = local.update {
        it.copy(mode = mode, slots = List(mode.sides.size) { null }, active = 0, complete = false, result = null)
    }

    /** Re-measure a specific side; the next reading replaces it. */
    fun selectSlot(index: Int) = local.update { it.copy(active = index, complete = false, result = null) }

    fun undo() = local.update { s ->
        val last = s.slots.indexOfLast { it != null }
        if (last < 0) s else s.copy(slots = s.slots.toMutableList().also { it[last] = null }, active = last, complete = false, result = null)
    }

    fun reset() = local.update {
        it.copy(slots = List(it.mode.sides.size) { null }, active = 0, complete = false, result = null, lastMm = null)
    }
}

// ------------------------------------------------------------------ pairing

sealed interface PairPhase {
    data object Scanning : PairPhase
    data class Pairing(val device: FoundDevice) : PairPhase
    data class Failed(val message: String) : PairPhase
}

data class PairingUiState(
    val found: List<FoundDevice> = emptyList(),
    val phase: PairPhase = PairPhase.Scanning,
)

class PairingViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(PairingUiState())
    val ui: StateFlow<PairingUiState> = _ui
    private var scanJob: Job? = null

    /** Safe to call repeatedly; requires Bluetooth permission and Bluetooth on. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _ui.update { PairingUiState() }
        scanJob = viewModelScope.launch {
            try {
                c.scanner.scan().collect { d ->
                    _ui.update { s ->
                        s.copy(found = (s.found.filterNot { it.address == d.address } + d).sortedByDescending { it.rssi })
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(phase = PairPhase.Failed(e.message ?: "Scan failed")) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun pair(device: FoundDevice) {
        stopScan()
        _ui.update { it.copy(phase = PairPhase.Pairing(device)) }
        viewModelScope.launch {
            when (val outcome = c.repository.pair(device)) {
                PairOutcome.Success -> Unit // the stored device switches the app to the main screen
                is PairOutcome.Failure -> _ui.update { it.copy(phase = PairPhase.Failed(outcome.message)) }
            }
        }
    }

    fun retry() {
        stopScan()
        startScan()
    }

    override fun onCleared() = stopScan()
}
