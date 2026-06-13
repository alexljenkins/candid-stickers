package com.candidstickers.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM test (no Robolectric) for the Stickers-grid selection reducer. */
class SelectionStateTest {

    @Test
    fun initialStateIsInactiveAndEmpty() {
        val s = SelectionState()
        assertFalse(s.active)
        assertEquals(0, s.count)
        assertTrue(s.ids.isEmpty())
        assertFalse(s.canCreatePack)
    }

    @Test
    fun startActivatesWithSingleId() {
        val s = SelectionState().start(7L)
        assertTrue(s.active)
        assertEquals(setOf(7L), s.ids)
        assertEquals(1, s.count)
        assertFalse(s.canCreatePack)
    }

    @Test
    fun toggleAddsAndRemovesWhileActive() {
        var s = SelectionState().start(1L).toggle(2L)
        assertEquals(setOf(1L, 2L), s.ids)
        s = s.toggle(1L)
        assertEquals(setOf(2L), s.ids)
        assertTrue(s.active)
    }

    @Test
    fun togglingLastIdExitsSelectionMode() {
        val s = SelectionState().start(7L).toggle(7L)
        assertFalse(s.active)
        assertTrue(s.ids.isEmpty())
    }

    @Test
    fun toggleIsNoOpWhenInactive() {
        val s = SelectionState()
        assertEquals(s, s.toggle(42L))
    }

    @Test
    fun selectionOrderIsPreserved() {
        var s = SelectionState().start(3L).toggle(1L).toggle(2L)
        assertEquals(listOf(3L, 1L, 2L), s.ids.toList())
        s = s.toggle(1L)
        assertEquals(listOf(3L, 2L), s.ids.toList())
    }

    @Test
    fun canCreatePackOnlyBetweenThreeAndThirty() {
        var s = SelectionState().start(0L).toggle(1L)
        assertEquals(2, s.count)
        assertFalse(s.canCreatePack)

        s = s.toggle(2L)
        assertEquals(3, s.count)
        assertTrue(s.canCreatePack)

        for (id in 3L..29L) s = s.toggle(id)
        assertEquals(30, s.count)
        assertTrue(s.canCreatePack)

        s = s.toggle(30L)
        assertEquals(31, s.count)
        assertFalse(s.canCreatePack)
    }

    @Test
    fun clearResetsEverything() {
        val s = SelectionState().start(1L).toggle(2L).toggle(3L).clear()
        assertEquals(SelectionState(), s)
        assertFalse(s.active)
    }

    @Test
    fun boundsMatchWhatsAppContract() {
        assertEquals(3, SelectionState.MIN_PACK_STICKERS)
        assertEquals(30, SelectionState.MAX_PACK_STICKERS)
    }
}
