package com.candidstickers.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.candidstickers.clip.Clip
import com.candidstickers.clip.StickerSearch
import com.candidstickers.data.CropDb
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced CLIP text search over the crop grid. State transitions are pure
 * ([SearchUiState]); this class only owns the timing (one cancellable job per
 * keystroke) and the IO.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = CropDb.getInstance(app)
    private val stickerSearch = StickerSearch(db)
    private var searchJob: Job? = null

    var state by mutableStateOf(SearchUiState())
        private set

    fun onQueryChange(query: String) {
        searchJob?.cancel()
        state = state.queryChanged(query)
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(query)
        }
    }

    fun clearQuery() = onQueryChange("")

    /** Re-runs the active query, e.g. when the tab is re-shown after enrichment added embeddings. */
    fun refresh() {
        val query = state.query
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        if (Clip.get(getApplication()) == null) {
            state = state.clipUnavailable(query)
            return
        }
        val results = stickerSearch.search(getApplication(), query)
        state = state.resultsArrived(query, results)
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
