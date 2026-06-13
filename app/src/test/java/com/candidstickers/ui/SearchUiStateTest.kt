package com.candidstickers.ui

import com.candidstickers.data.CandidCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM test (no Robolectric) for the search view-state machine. */
class SearchUiStateTest {

    private fun crop(id: Long, tags: List<String> = emptyList()) = CandidCrop(
        id = id,
        mediaId = id * 10,
        contentUri = "content://media/external/images/media/$id",
        faceIndex = 0,
        score = 0.9f,
        reason = "eyes shut",
        cropPath = "/data/crops/c$id.png",
        tags = tags,
    )

    @Test
    fun initialStateIsInactiveAndShowsNormalGrid() {
        val s = SearchUiState()
        assertFalse(s.active)
        assertFalse(s.searching)
        assertFalse(s.clipMissing)
        assertFalse(s.showNoMatches)
        val all = listOf(crop(1), crop(2))
        assertEquals(all, s.gridCrops(all))
    }

    @Test
    fun queryChangedActivatesAndStartsSearching() {
        val s = SearchUiState().queryChanged("crying laughing")
        assertTrue(s.active)
        assertTrue(s.searching)
        assertFalse(s.clipMissing)
        assertEquals("crying laughing", s.query)
    }

    @Test
    fun blankQueryFullyResets() {
        val s = SearchUiState()
            .queryChanged("dog")
            .resultsArrived("dog", listOf(crop(1)))
            .queryChanged("")
        assertEquals(SearchUiState(), s)
    }

    @Test
    fun whitespaceOnlyQueryAlsoResets() {
        val s = SearchUiState().queryChanged("dog").queryChanged("   ")
        assertEquals(SearchUiState(), s)
    }

    @Test
    fun resultsArrivedForCurrentQueryApply() {
        val results = listOf(crop(3), crop(1))
        val s = SearchUiState().queryChanged("dog").resultsArrived("dog", results)
        assertEquals(results, s.results)
        assertFalse(s.searching)
        // Search results own the grid; the normal crops are ignored while active.
        assertEquals(results, s.gridCrops(listOf(crop(9))))
    }

    @Test
    fun staleResultsAreIgnored() {
        val s = SearchUiState()
            .queryChanged("dog")
            .queryChanged("dogs playing")
            .resultsArrived("dog", listOf(crop(1)))
        assertTrue(s.results.isEmpty())
        assertTrue(s.searching)
        assertEquals("dogs playing", s.query)
    }

    @Test
    fun previousResultsKeptWhileNextSearchPends() {
        val first = listOf(crop(1))
        val s = SearchUiState()
            .queryChanged("dog")
            .resultsArrived("dog", first)
            .queryChanged("dogs")
        // No flicker to empty between keystrokes.
        assertEquals(first, s.results)
        assertTrue(s.searching)
    }

    @Test
    fun clipUnavailableSetsHintAndClearsResults() {
        val s = SearchUiState()
            .queryChanged("dog")
            .resultsArrived("dog", listOf(crop(1)))
            .queryChanged("cat")
            .clipUnavailable("cat")
        assertTrue(s.clipMissing)
        assertTrue(s.results.isEmpty())
        assertFalse(s.searching)
        assertFalse(s.showNoMatches)
    }

    @Test
    fun staleClipUnavailableIgnored() {
        val s = SearchUiState()
            .queryChanged("dog")
            .queryChanged("cat")
            .clipUnavailable("dog")
        assertFalse(s.clipMissing)
        assertTrue(s.searching)
    }

    @Test
    fun typingAgainClearsClipMissing() {
        val s = SearchUiState()
            .queryChanged("dog")
            .clipUnavailable("dog")
            .queryChanged("dogs")
        assertFalse(s.clipMissing)
        assertTrue(s.searching)
    }

    @Test
    fun showNoMatchesOnlyWhenSearchSettledEmpty() {
        val typing = SearchUiState().queryChanged("dog")
        assertFalse(typing.showNoMatches) // still searching

        val empty = typing.resultsArrived("dog", emptyList())
        assertTrue(empty.showNoMatches)

        val withHits = typing.resultsArrived("dog", listOf(crop(1)))
        assertFalse(withHits.showNoMatches)

        val noClip = typing.clipUnavailable("dog")
        assertFalse(noClip.showNoMatches) // hint explains the empty grid instead

        assertFalse(SearchUiState().showNoMatches) // inactive
    }

    @Test
    fun clearingAfterEmptyResultsRestoresNormalGrid() {
        val all = listOf(crop(1), crop(2), crop(3))
        val s = SearchUiState()
            .queryChanged("dog")
            .resultsArrived("dog", emptyList())
            .queryChanged("")
        assertEquals(all, s.gridCrops(all))
        assertFalse(s.showNoMatches)
    }

    @Test
    fun selectionInterplaySearchDoesNotTouchSelection() {
        // Selection lives in SelectionState, independent of search transitions:
        // entering/leaving search must not drop a pack-in-progress.
        var selection = SelectionState().start(1L).toggle(2L)
        val search = SearchUiState()
            .queryChanged("dog")
            .resultsArrived("dog", listOf(crop(2), crop(5)))
        // Toggling a search result works like toggling a normal grid crop.
        selection = selection.toggle(5L)
        assertEquals(setOf(1L, 2L, 5L), selection.ids)
        assertTrue(selection.active)
        // And clearing the query keeps the selection intact.
        search.queryChanged("")
        assertEquals(setOf(1L, 2L, 5L), selection.ids)
    }
}
