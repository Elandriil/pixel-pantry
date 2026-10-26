package app.pixelpantry.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.api.Row
import app.pixelpantry.tv.api.ScanStatus
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * server as-is; empty rows are dropped. Also drives the library rescan started from the sidebar.
 */
class HomeViewModel(
    private val api: ApiClient,
    private val category: String,
    private val toaster: Toaster,
    private val strings: Strings,
    private val pollIntervalMs: Long = 2_000,
) : ViewModel() {

    /** Toast texts; supplied from string resources. */
    class Strings(val scanning: String, val updated: String, val scanFailed: (error: String) -> String)

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
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
    private var scanJob: Job? = null

    init {
        load()
    }

    /** Fetches the page. Shows [UiState.Loading] unless a page is already on screen (then refreshes in place). */
    fun load() {
        loadJob?.cancel()
        if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
        loadJob = viewModelScope.launch { fetch() }
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

    /** Starts a server scan, polls until it finishes, then reloads. A second call while scanning is ignored. */
    fun rescan() {
        if (scanJob?.isActive == true) {
            toaster.show(strings.scanning)
            return
        }
        scanJob = viewModelScope.launch {
            val finished: ScanStatus = try {
                api.scan()
                toaster.show(strings.scanning)
                var status: ScanStatus
                do {
                    delay(pollIntervalMs)
                    status = api.scanStatus()
                } while (status.state != "idle")
                status
            } catch (e: ApiException) {
                toaster.show(e.message.orEmpty())
                return@launch
            }
            loadJob?.cancel()
            if (!fetch()) return@launch
            toaster.show(finished.lastError?.let(strings.scanFailed) ?: strings.updated)
        }
    }

    /** Loads the page into [state]; keeps the focused card when it is still on the page. Returns success. */
    private suspend fun fetch(): Boolean = try {
        val page = if (category == "home") api.home() else api.category(category)
        val rows = page.rows.filter { it.cards.isNotEmpty() }
        val cards = rows.flatMap { it.cards }
        val previous = (_state.value as? UiState.Loaded)?.focused?.let(::identity)
        val focused = cards.firstOrNull { previous != null && identity(it) == previous } ?: cards.firstOrNull()
        _state.value = UiState.Loaded(rows, focused)
        true
    } catch (e: ApiException) {
        _state.value = UiState.Error(e.message.orEmpty())
        false
    }

    private fun identity(card: Card): String = when (card) {
        is Card.ShowCard -> "show:${card.show.id}"
        is Card.ItemCard -> "item:${card.item.id}"
    }

    class Factory(
        private val api: ApiClient,
        private val category: String,
        private val toaster: Toaster,
        private val strings: Strings,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(api, category, toaster, strings) as T
    }
}
