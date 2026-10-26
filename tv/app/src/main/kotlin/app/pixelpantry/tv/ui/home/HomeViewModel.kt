package app.pixelpantry.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.api.Row
import app.pixelpantry.tv.library.LibraryScanner
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.components.isUnreachable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One browse page: `home` (all categories) or a single category (`tv`, `anime`, `movies`). Rows come from the
 * server as-is; empty rows are dropped. The page refreshes in place after every library scan and whenever it
 * is shown again (e.g. back from the player). A refresh that fails keeps the page on screen and toasts
 * [couldNotRefresh]; only a first load that fails shows an error ([UiState.Unreachable] when the server did not
 * answer at all). After a scan the page toasts the scan's outcome once its reload went through.
 */
class HomeViewModel(
    private val api: ApiClient,
    private val category: String,
    private val scanner: LibraryScanner,
    private val toaster: Toaster,
    private val couldNotRefresh: String,
    /** Resolves the server once more (see `AppContainer.reconnect`); run by [retry]. */
    private val reconnect: suspend () -> Unit,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        /** The first load got no answer from [baseUrl]. */
        data class Unreachable(val baseUrl: String) : UiState
        /** [rows] holds only non-empty rows; an empty list means an empty library. [focused] feeds the hero. */
        data class Loaded(val rows: List<Row>, val focused: Card?) : UiState
    }

    sealed class NavEvent {
        data class Player(val itemId: Int) : NavEvent()
        data class Movie(val itemId: Int) : NavEvent()
        data class Show(val showId: Int) : NavEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private var loadJob: Job? = null
    private var resumedBefore = false

    init {
        load()
        viewModelScope.launch { scanner.scanFinished.collect { outcome -> load(toastOnSuccess = outcome) } }
    }

    /**
     * Fetches the page. Shows [UiState.Loading] unless a page is already on screen (then refreshes in place).
     * [toastOnSuccess] is shown once the page loaded.
     */
    fun load(toastOnSuccess: String? = null) {
        loadJob?.cancel()
        if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            if (fetch()) toastOnSuccess?.let(toaster::show)
        }
    }

    /** Unreachable / error screen's Retry: resolves the server once more, then loads. */
    fun retry() {
        loadJob?.cancel()
        _state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            reconnect()
            fetch()
        }
    }

    fun onFocus(card: Card) {
        _state.update { if (it is UiState.Loaded) it.copy(focused = card) else it }
    }

    /**
     * Continue-watching cards (an [Card.ItemCard] with a `reason`) go straight to the player; other item cards
     * are movies and open the movie page; show cards open the show page.
     */
    fun open(card: Card) {
        val event = when (card) {
            is Card.ShowCard -> NavEvent.Show(card.show.id)
            is Card.ItemCard -> if (card.reason != null) NavEvent.Player(card.item.id) else NavEvent.Movie(card.item.id)
        }
        _navEvents.tryEmit(event)
    }

    /** Starts a library rescan on the app-wide [LibraryScanner]; every page reloads when it finishes. */
    fun rescan() = scanner.rescan()

    /** Called each time the page is resumed. The first resume is the initial load; later ones refresh. */
    fun onResume() {
        if (resumedBefore) load() else resumedBefore = true
    }

    /** Loads the page into [state]; keeps the focused card when it is still on the page. True when it loaded. */
    private suspend fun fetch(): Boolean = try {
        // Restored after process death before any server was resolved: resolve first.
        if (api.baseUrl.isBlank()) reconnect()
        val page = if (category == "home") api.home() else api.category(category)
        val rows = page.rows.filter { it.cards.isNotEmpty() }
        val cards = rows.flatMap { it.cards }
        val previous = (_state.value as? UiState.Loaded)?.focused?.let(::identity)
        val focused = cards.firstOrNull { previous != null && identity(it) == previous } ?: cards.firstOrNull()
        _state.value = UiState.Loaded(rows, focused)
        true
    } catch (e: ApiException) {
        when {
            _state.value is UiState.Loaded -> toaster.show(couldNotRefresh)
            e.isUnreachable() -> _state.value = UiState.Unreachable(api.baseUrl)
            else -> _state.value = UiState.Error(e.message.orEmpty())
        }
        false
    }

    private fun identity(card: Card): String = when (card) {
        is Card.ShowCard -> "show:${card.show.id}"
        is Card.ItemCard -> "item:${card.item.id}"
    }

    class Factory(
        private val api: ApiClient,
        private val category: String,
        private val scanner: LibraryScanner,
        private val toaster: Toaster,
        private val couldNotRefresh: String,
        private val reconnect: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(api, category, scanner, toaster, couldNotRefresh, reconnect) as T
    }
}
