package com.candidstickers.ui

/**
 * Immutable view-state for multi-select on the Stickers grid.
 *
 * [ids] is insertion-ordered (Kotlin set operators keep LinkedHashSet order), so the
 * stickers in a created pack keep the order the user tapped them in.
 */
data class SelectionState(
    val active: Boolean = false,
    val ids: Set<Long> = emptySet(),
) {
    val count: Int get() = ids.size

    /** WhatsApp packs must hold [MIN_PACK_STICKERS]..[MAX_PACK_STICKERS] stickers. */
    val canCreatePack: Boolean get() = count in MIN_PACK_STICKERS..MAX_PACK_STICKERS

    /** Long-press on a crop outside selection mode. */
    fun start(id: Long): SelectionState = SelectionState(active = true, ids = setOf(id))

    /** Tap while selecting. Deselecting the last crop exits selection mode. */
    fun toggle(id: Long): SelectionState {
        if (!active) return this
        val next = if (id in ids) ids - id else ids + id
        return if (next.isEmpty()) SelectionState() else copy(ids = next)
    }

    fun clear(): SelectionState = SelectionState()

    companion object {
        const val MIN_PACK_STICKERS = 3
        const val MAX_PACK_STICKERS = 30
    }
}
