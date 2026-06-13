package com.candidstickers.ui

import com.candidstickers.data.CandidCrop

/**
 * Pure view-state machine for sticker search. Debounce *timing* lives in
 * [SearchViewModel]; every transition here is a pure function so the
 * interesting logic (stale-result rejection, blank-clears, CLIP-missing hint)
 * unit-tests without coroutines.
 *
 * While a new search is in flight the previous [results] are kept so the grid
 * doesn't flicker to empty between keystrokes.
 */
data class SearchUiState(
    val query: String = "",
    val results: List<CandidCrop> = emptyList(),
    val searching: Boolean = false,
    val clipMissing: Boolean = false,
) {
    /** Search owns the grid while the query is non-blank. */
    val active: Boolean get() = query.isNotBlank()

    /** True when the grid should show an explicit "no matches" hint. */
    val showNoMatches: Boolean get() = active && !searching && !clipMissing && results.isEmpty()

    /** User typed. Blank fully resets; otherwise a (debounced) search is pending. */
    fun queryChanged(newQuery: String): SearchUiState =
        if (newQuery.isBlank()) SearchUiState()
        else copy(query = newQuery, searching = true, clipMissing = false)

    /** Search finished for [forQuery]; ignored if the query has moved on since. */
    fun resultsArrived(forQuery: String, items: List<CandidCrop>): SearchUiState =
        if (forQuery != query) this
        else copy(results = items, searching = false, clipMissing = false)

    /** Clip.get() returned null for [forQuery]; ignored if the query has moved on. */
    fun clipUnavailable(forQuery: String): SearchUiState =
        if (forQuery != query) this
        else copy(results = emptyList(), searching = false, clipMissing = true)

    /** What the Stickers grid should render given the normal (non-search) crops. */
    fun gridCrops(allCrops: List<CandidCrop>): List<CandidCrop> =
        if (active) results else allCrops
}
