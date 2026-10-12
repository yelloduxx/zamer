package dev.bezh.zamer.domain

import kotlinx.serialization.Serializable

@Serializable
data class Draft(val values: List<Int?>, val next: Int?, val savedId: Long? = null) {
    init {
        require(values.isNotEmpty())
        require(next == null || next in values.indices)
        require(next != null || values.all { it != null }) { "a complete set has every value" }
        require(savedId == null || next == null) { "only a complete set is saved" }
    }

    val isComplete get() = next == null

    fun put(mm: Int): Draft {
        val filled = (if (next == null) List(values.size) { null } else values).toMutableList()
        filled[next ?: 0] = mm
        return Draft(filled, filled.indexOfFirst { it == null }.takeIf { it >= 0 })
    }

    fun select(index: Int) = Draft(values, index)

    fun undo(): Draft {
        val last = values.indexOfLast { it != null }
        return if (last < 0) this else Draft(values.toMutableList().also { it[last] = null }, last)
    }

    companion object {
        fun empty(mode: MeasureMode) = Draft(List(mode.sides.size) { null }, 0)
    }
}

@Serializable
data class Workspace(
    val mode: MeasureMode = MeasureMode.AREA,
    val drafts: Map<MeasureMode, Draft> = emptyMap(),
) {
    fun draft(of: MeasureMode = mode): Draft =
        drafts[of]?.takeIf { it.values.size == of.sides.size } ?: Draft.empty(of)

    fun result(of: MeasureMode = mode): MeasureResult? =
        draft(of).takeIf { it.isComplete }?.let { MeasureResult.of(of, it.values.filterNotNull()) }

    fun put(mm: Int, entryId: Long): Pair<Workspace, MeasureResult?> {
        val next = withDraft(draft().put(mm))
        val result = next.result()
        return (if (result != null) next.withDraft(next.draft().copy(savedId = entryId)) else next) to result
    }

    fun select(index: Int) = withDraft(draft().select(index))

    fun undo() = withDraft(draft().undo())

    fun reset() = withDraft(Draft.empty(mode))

    private fun withDraft(d: Draft) = copy(drafts = drafts + (mode to d))
}
