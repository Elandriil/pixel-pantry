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
    /** Toast after a card's "Delete from PC". */
    private val movedToRecycleBin: String,
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

    /** What a card's long-press / MENU menu acts on: a whole show, or a movie. */
    sealed interface CardTarget {
        val title: String
        data class Show(val id: Int, override val title: String, val watched: Boolean) : CardTarget
        data class Movie(val id: Int, override val title: String, val watched: Boolean) : CardTarget
    }

    /** "Delete from PC" was chosen: the confirm dialog for [target], [fileCount] files. */
    data class DeleteConfirm(val target: CardTarget, val fileCount: Int)

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private val _deleteConfirm = MutableStateFlow<DeleteConfirm?>(null)
    val deleteConfirm: StateFlow<DeleteConfirm?> = _deleteConfirm.asStateFlow()

    private var loadJob: Job? = null

    /** The reload after a scan; a later [load] (e.g. on resume) runs beside it and never cancels it. */
    private var scanReloadJob: Job? = null
    private var resumedBefore = false

    init {
        load()
        viewModelScope.launch { scanner.scanFinished.collect(::reloadAfterScan) }
    }

    /** Fetches the page. Shows [UiState.Loading] unless a page is already on screen (then refreshes in place). */
    fun load() {
        loadJob?.cancel()
        if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
        loadJob = viewModelScope.launch { fetch() }
    }

    /** A scan finished: reload, and toast its [outcome] once that reload went through (a newer scan replaces it). */
    private fun reloadAfterScan(outcome: String) {
        scanReloadJob?.cancel()
        if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
        scanReloadJob = viewModelScope.launch {
            if (fetch()) toaster.show(outcome)
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

    /**
     * The card's menu target: a show card is its show; a movie card the movie; an episode card (continue
     * watching) its whole show. Null when there is nothing to act on.
     */
    fun menuTarget(card: Card): CardTarget? = when (card) {
        is Card.ShowCard -> CardTarget.Show(card.show.id, card.show.title, watched = card.show.unwatchedCount == 0 && card.show.episodeCount > 0)
        is Card.ItemCard -> when {
            card.item.type == "movie" -> CardTarget.Movie(card.item.id, card.item.title, watched = card.item.progress?.watched == true)
            card.item.showId != null -> CardTarget.Show(card.item.showId, card.item.showTitle ?: card.item.title, watched = false)
            else -> null
        }
    }

    /** Marks every episode of show [showId] watched / unwatched, then reloads the page. */
    fun markShowWatched(showId: Int, watched: Boolean) = write { api.setShowWatched(showId, watched) }

    /** Marks movie [itemId] watched / unwatched, then reloads the page. */
    fun markMovieWatched(itemId: Int, watched: Boolean) = write { api.setWatched(itemId, watched) }

    /** Moves show [showId]'s files to the PC's Recycle Bin, then reloads the page. */
    fun deleteShow(showId: Int) = write {
        api.deleteShow(showId)
        toaster.show(movedToRecycleBin)
    }

    /** Moves movie [itemId]'s file to the PC's Recycle Bin, then reloads the page. */
    fun deleteMovie(itemId: Int) = write {
        api.deleteMovie(itemId)
        toaster.show(movedToRecycleBin)
    }

    /**
     * "Delete from PC" in a card's menu: asks for confirmation with the number of files, as on the show page (a
     * show's count comes from `GET /api/shows/:id`; a movie is one file).
     */
    fun requestDelete(target: CardTarget) {
        when (target) {
            is CardTarget.Movie -> _deleteConfirm.value = DeleteConfirm(target, fileCount = 1)
            is CardTarget.Show -> viewModelScope.launch {
                try {
                    _deleteConfirm.value = DeleteConfirm(target, api.show(target.id).fileCount)
                } catch (e: ApiException) {
                    toaster.show(e.message.orEmpty())
                }
            }
        }
    }

    fun cancelDelete() {
        _deleteConfirm.value = null
    }

    fun confirmDelete() {
        val confirm = _deleteConfirm.value ?: return
        _deleteConfirm.value = null
        when (val t = confirm.target) {
            is CardTarget.Show -> deleteShow(t.id)
            is CardTarget.Movie -> deleteMovie(t.id)
        }
    }

    /** Runs a write; reloads the page when it went through, toasts the server's message when not. */
    private fun write(call: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                call()
                load()
            } catch (e: ApiException) {
                toaster.show(e.message.orEmpty())
            }
        }
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
        private val movedToRecycleBin: String,
        private val reconnect: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(api, category, scanner, toaster, couldNotRefresh, movedToRecycleBin, reconnect) as T
    }
}
