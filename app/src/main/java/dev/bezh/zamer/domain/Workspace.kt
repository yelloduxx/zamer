package dev.bezh.zamer.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = DraftSerializer::class)
sealed interface Draft {
    val values: List<Int?>

    /** Waiting for the side at [next], which may already hold a value that is being re-measured. */
    data class Filling(override val values: List<Int?>, val next: Int) : Draft {
        init {
            require(next in values.indices)
        }
    }

    /** [savedId] is the history entry the set was saved as; an impossible height has none. */
    data class Complete(override val values: List<Int>, val savedId: Long? = null) : Draft {
        init {
            require(values.isNotEmpty())
        }
    }

    fun put(mm: Int): Draft {
        val filled = when (this) {
            is Filling -> values.toMutableList().also { it[next] = mm }
            is Complete -> MutableList<Int?>(values.size) { null }.also { it[0] = mm }
        }
        val gap = filled.indexOf(null)
        return if (gap >= 0) Filling(filled, gap) else Complete(filled.requireNoNulls())
    }

    fun select(index: Int): Draft = Filling(values, index)

    fun undo(): Draft {
        val last = values.indexOfLast { it != null }
        return if (last < 0) this else Filling(values.toMutableList().also { it[last] = null }, last)
    }

    companion object {
        fun empty(mode: MeasureMode) = Filling(List(mode.sides.size) { null }, 0)
    }
}

/** The stored shape. `next` has no default so a complete draft keeps writing `"next":null`, which older versions require. */
@Serializable
private class StoredDraft(val values: List<Int?>, val next: Int?, val savedId: Long? = null)

private object DraftSerializer : KSerializer<Draft> {
    override val descriptor = SerialDescriptor("dev.bezh.zamer.domain.Draft", StoredDraft.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: Draft) = encoder.encodeSerializableValue(
        StoredDraft.serializer(),
        when (value) {
            is Draft.Filling -> StoredDraft(value.values, value.next)
            is Draft.Complete -> StoredDraft(value.values, next = null, savedId = value.savedId)
        },
    )

    override fun deserialize(decoder: Decoder): Draft {
        val stored = decoder.decodeSerializableValue(StoredDraft.serializer())
        if (stored.next == null) return Draft.Complete(stored.values.requireNoNulls(), stored.savedId)
        require(stored.savedId == null) { "only a complete set is saved" }
        return Draft.Filling(stored.values, stored.next)
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
        (draft(of) as? Draft.Complete)?.let { MeasureResult.of(of, it.values) }

    /** [completed] is set only when this put finished a valid set, which is then saved as [entryId]. */
    data class Put(val workspace: Workspace, val completed: MeasureResult?)

    fun put(mm: Int, entryId: Long): Put = when (val draft = draft().put(mm)) {
        is Draft.Filling -> Put(withDraft(draft), null)
        is Draft.Complete -> {
            val result = MeasureResult.of(mode, draft.values)
            Put(withDraft(if (result != null) draft.copy(savedId = entryId) else draft), result)
        }
    }

    fun select(index: Int) = withDraft(draft().select(index))

    fun undo() = withDraft(draft().undo())

    fun reset() = withDraft(Draft.empty(mode))

    private fun withDraft(d: Draft) = copy(drafts = drafts + (mode to d))
}
