package dev.bezh.zamer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.ReadingTarget
import dev.bezh.zamer.ble.FoundDevice
import dev.bezh.zamer.ble.LinkState
import dev.bezh.zamer.ble.PairOutcome
import dev.bezh.zamer.data.HistoryEntry
import dev.bezh.zamer.data.Settings
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Reading
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import dev.bezh.zamer.domain.SketchDoc
import dev.bezh.zamer.domain.Workspace
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
        SketchViewModel::class.java -> SketchViewModel(container)
        else -> error("Unknown ViewModel $modelClass")
    } as T
}

// ---------------------------------------------------------------- measuring

data class MeasureUiState(
    /** Null until the stored workspace has been read. */
    val workspace: Workspace? = null,
    /** The last value put into the current mode's draft during this session. */
    val lastMm: Int? = null,
    /** Increments with every device reading so the UI can react (haptics, animation). */
    val readingCount: Int = 0,
    val link: LinkState = LinkState.Searching,
    val settings: Settings = Settings(),
    /** Recent distinct values from the readings log, newest first. */
    val recent: List<Int> = emptyList(),
    /** The history entries that completed drafts were saved as, by id; gone once deleted from history. */
    val saved: Map<Long, HistoryEntry> = emptyMap(),
)

private data class Session(val lastMm: Int? = null, val readingCount: Int = 0)

class MeasureViewModel(private val c: AppContainer) : ViewModel() {
    private val session = MutableStateFlow(Session())

    private val stored = combine(c.store.workspace, c.store.history, c.store.readings) { ws, history, log ->
        val ids = ws.drafts.values.mapNotNullTo(HashSet()) { it.savedId }
        MeasureUiState(
            workspace = ws,
            recent = log.recentValues(),
            saved = history.filter { it.id in ids }.associateBy { it.id },
        )
    }

    val ui: StateFlow<MeasureUiState> =
        combine(stored, session, c.repository.state, c.store.settings) { st, s, link, settings ->
            st.copy(lastMm = s.lastMm, readingCount = s.readingCount, link = link, settings = settings)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeasureUiState())

    init {
        viewModelScope.launch { c.readings(ReadingTarget.MEASURE).collect { put(it.mm, fromDevice = true) } }
    }

    /** Treats [mm] as the next reading; [fromDevice] readings also buzz. */
    fun put(mm: Int, fromDevice: Boolean = false) {
        session.update { it.copy(lastMm = mm, readingCount = it.readingCount + if (fromDevice) 1 else 0) }
        viewModelScope.launch {
            var completed: HistoryEntry? = null
            c.store.updateWorkspace { ws ->
                val id = System.nanoTime()
                val (next, result) = ws.put(mm, id)
                completed = result?.let { HistoryEntry(id, System.currentTimeMillis(), it.mode, it.valuesMm) }
                next
            }
            completed?.let { c.store.addHistory(it) }
        }
    }

    fun rename(entryId: Long, title: String) {
        viewModelScope.launch { c.store.renameHistory(entryId, title) }
    }

    fun setMode(mode: MeasureMode) {
        session.update { it.copy(lastMm = null) }
        edit { it.copy(mode = mode) }
    }

    /** Re-measure a specific side; the next reading replaces it. */
    fun selectSlot(index: Int) = edit { it.select(index) }

    fun undo() = edit { it.undo() }

    fun reset() {
        session.update { it.copy(lastMm = null) }
        edit { it.reset() }
    }

    private fun edit(transform: (Workspace) -> Workspace) {
        viewModelScope.launch { c.store.updateWorkspace(transform) }
    }
}

/** Distinct values from the readings log, newest first, offered for reuse. */
private fun List<Reading>.recentValues() = map { it.mm }.distinct().take(RECENT_CHIPS)

private const val RECENT_CHIPS = 10

// ------------------------------------------------------------------- sketch

enum class SketchTool { LINE, RECT }

data class SketchUiState(
    /** Null until the stored sketches have been read; newest first. */
    val sketches: List<SketchDoc>? = null,
    val openId: Long? = null,
    /** Increments with every device reading that measured a side, so the UI can buzz. */
    val assignedCount: Int = 0,
    val link: LinkState = LinkState.Searching,
    val settings: Settings = Settings(),
    val recent: List<Int> = emptyList(),
) {
    /** The sketch being drawn, once it has been read. */
    val open: SketchDoc? get() = sketches?.firstOrNull { it.id == openId }
}

/** The list of sketches and the one open in the sketch screen, which device readings measure. */
class SketchViewModel(private val c: AppContainer) : ViewModel() {
    private val assigned = MutableStateFlow(0)
    private val openId = MutableStateFlow<Long?>(null)

    val ui: StateFlow<SketchUiState> =
        combine(c.store.sketches, openId, assigned, c.repository.state, c.store.settings) { sketches, open, n, link, settings ->
            SketchUiState(sketches, open, n, link, settings)
        }.combine(c.store.readings) { ui, log -> ui.copy(recent = log.recentValues()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SketchUiState())

    init {
        viewModelScope.launch { c.readings(ReadingTarget.SKETCH).collect { put(it.mm, fromDevice = true) } }
    }

    /** Opens sketch [id] for drawing and measuring, or with null leaves it. */
    fun open(id: Long?) {
        openId.value = id
    }

    /** Starts an empty floor plan and gives its id. */
    fun newPlan(): Long {
        val now = System.currentTimeMillis()
        viewModelScope.launch { c.store.addSketch(SketchDoc(id = now, created = now)) }
        return now
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch { c.store.updateSketch(id) { it.renamed(title) } }
    }

    fun delete(id: Long) {
        viewModelScope.launch { c.store.deleteSketch(id) }
    }

    /** Measures the selected side with [mm]; [fromDevice] readings also buzz. */
    fun put(mm: Int, fromDevice: Boolean = false) {
        val id = openId.value ?: return
        viewModelScope.launch {
            var measured = false
            c.store.updateSketch(id) { doc ->
                measured = doc.sketch.target != null
                doc.put(mm)
            }
            if (measured && fromDevice) assigned.update { it + 1 }
        }
    }

    fun draw(tool: SketchTool, a: Pt, b: Pt) {
        val id = System.nanoTime()
        edit { it.add(if (tool == SketchTool.LINE) Segment(id, a, b) else Rect(id, a, b)) }
    }

    fun select(ref: SideRef) = edit { it.select(ref) }

    fun move(id: Long, d: Pt) = edit { it.move(id, d) }

    /** Deletes the shape whose side is selected. */
    fun deleteSelected() = edit { s -> s.target?.let { s.delete(it.shapeId) } ?: s }

    fun undo() = edit { it.undo() }

    fun clear() = edit { it.clear() }

    private fun edit(transform: (Sketch) -> Sketch) {
        val id = openId.value ?: return
        viewModelScope.launch { c.store.updateSketch(id) { it.copy(sketch = transform(it.sketch)) } }
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
