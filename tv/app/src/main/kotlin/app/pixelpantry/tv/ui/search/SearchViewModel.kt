package app.pixelpantry.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.api.SearchResponse
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Search as you type: the server is asked once the query (trimmed) has at least [MIN_CHARS] characters and has
 * not changed for [DEBOUNCE_MS]. Shorter queries clear [results] at once. [results] is null while there is
 * nothing to show (no query yet, too short, or the last search failed — see [error]); an empty response means
 * the server found nothing.
 */
class SearchViewModel(private val api: ApiClient) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<SearchResponse?>(null)
    val results: StateFlow<SearchResponse?> = _results.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            // collectLatest cancels the pending delay (and any in-flight search) whenever the query changes.
            _query.collectLatest { raw ->
                val q = raw.trim()
                if (q.length < MIN_CHARS) {
                    _results.value = null
                    _error.value = null
                    return@collectLatest
                }
                delay(DEBOUNCE_MS)
                try {
                    _results.value = api.search(q)
                    _error.value = null
                } catch (e: ApiException) {
                    _results.value = null
                    _error.value = e.message.orEmpty()
                }
            }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
        // Clear stale results right away for a too-short query; the collector would only do so on its next turn.
        if (q.trim().length < MIN_CHARS) _results.value = null
    }

    /** Shows open the show page, movies the movie page. */
    fun open(card: Card) {
        val event = when (card) {
            is Card.ShowCard -> NavEvent.Show(card.show.id)
            is Card.ItemCard -> NavEvent.Movie(card.item.id)
        }
        _navEvents.tryEmit(event)
    }

    class Factory(private val api: ApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(api) as T
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val MIN_CHARS = 2
    }
}
