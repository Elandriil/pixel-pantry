package app.pixelpantry.tv.ui.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.api.SeasonDetail
import app.pixelpantry.tv.api.ShowSummary
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.components.isUnreachable
import app.pixelpantry.tv.util.formatRemaining
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The detail page of a show ([showId]) or a movie ([movieItemId], an item id); exactly one is set. Loads the
 * page, works out the primary (Resume / Play) and secondary (mark season) buttons, and runs the page's actions.
 * Every write reloads the page afterwards; a failed write toasts the server's message. A reload that fails keeps
 * the page and toasts [Strings.couldNotRefresh]; only a failed first load shows an error screen.
 */
class ShowViewModel(
    private val api: ApiClient,
    private val showId: Int?,
    private val movieItemId: Int?,
    private val toaster: Toaster,
    private val strings: Strings,
    /** Resolves the server once more (see `AppContainer.reconnect`); run by [retry]. */
    private val reconnect: suspend () -> Unit,
) : ViewModel() {

    init {
        require((showId == null) != (movieItemId == null)) { "Exactly one of showId and movieItemId" }
    }

    /** Button and toast texts; supplied from string resources. */
    class Strings(
        val play: String,
        val resume: String,
        val playEpisode: (tag: String) -> String,
        val resumeEpisode: (tag: String, left: String) -> String,
        val markSeasonWatched: String,
        val markSeasonUnwatched: String,
        val movedToRecycleBin: String,
        val couldNotRefresh: String,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        /** The first load got no answer from [baseUrl]. */
        data class Unreachable(val baseUrl: String) : UiState

        /**
         * Exactly one of [show] / [movie] is set. [seasons] is empty for a movie; regular seasons come in order
         * with Specials (0) last. [selectedSeason] is a season number. [resume] is the server's resume item
         * (in-progress or first unwatched episode). The trailing fields are derived for the buttons.
         */
        data class Loaded(
            val show: ShowSummary?,
            val movie: ItemSummary?,
            val seasons: List<SeasonDetail>,
            val selectedSeason: Int,
            val resume: ItemSummary?,
            val fileCount: Int,
            val primaryLabel: String = "",
            /** "Mark season watched/unwatched"; null for a movie. */
            val secondaryLabel: String? = null,
            /** Every episode of the selected season is watched. */
            val seasonWatched: Boolean = false,
            /** Every episode of the show (or the movie) is watched. */
            val allWatched: Boolean = false,
        ) : UiState {
            val title: String get() = show?.title ?: movie?.title.orEmpty()
            val selected: SeasonDetail? get() = seasons.firstOrNull { it.number == selectedSeason }
        }
    }

    sealed class NavEvent {
        data class Player(val itemId: Int, val fromStart: Boolean) : NavEvent()
        data object Back : NavEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private var loadJob: Job? = null
    private var resumedBefore = false

    private val _deleting = MutableStateFlow(false)

    /**
     * A delete is in flight, or succeeded and the page is about to close: the page's actions are disabled and
     * ignored, so a Play press cannot race the DELETE.
     */
    val deleting: StateFlow<Boolean> = _deleting.asStateFlow()

    init {
        load()
    }

    /** Fetches the page. Shows [UiState.Loading] unless a page is already on screen (then refreshes in place). */
    fun load() {
        loadJob?.cancel()
        if (_state.value !is UiState.Loaded) _state.value = UiState.Loading
        loadJob = viewModelScope.launch { fetch() }
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

    private suspend fun fetch() {
        try {
            _state.value = if (showId != null) fetchShow(showId) else fetchMovie(movieItemId!!)
        } catch (e: ApiException) {
            when {
                _state.value is UiState.Loaded -> toaster.show(strings.couldNotRefresh)
                e.isUnreachable() -> _state.value = UiState.Unreachable(api.baseUrl)
                else -> _state.value = UiState.Error(e.message.orEmpty())
            }
        }
    }

    /** Called each time the page is resumed. The first resume is the initial load; later ones refresh. */
    fun onResume() {
        if (resumedBefore) load() else resumedBefore = true
    }

    fun selectSeason(number: Int) {
        val s = _state.value as? UiState.Loaded ?: return
        if (s.seasons.none { it.number == number } || s.selectedSeason == number) return
        _state.value = derive(s.copy(selectedSeason = number))
    }

    fun play(item: ItemSummary, fromStart: Boolean) {
        if (_deleting.value) return
        _navEvents.tryEmit(NavEvent.Player(item.id, fromStart))
    }

    /** Resumes the in-progress item, else plays the first unwatched one (from the start), else the first. */
    fun primaryAction() {
        val s = _state.value as? UiState.Loaded ?: return
        val target = primaryTarget(s) ?: return
        play(target, fromStart = !target.inProgress())
    }

    fun markWatched(item: ItemSummary, watched: Boolean) = write { api.setWatched(item.id, watched) }

    fun markSeasonWatched(season: Int, watched: Boolean) = write {
        api.setShowWatched(showId ?: return@write, watched, season)
    }

    /** Marks the whole show — or, on a movie page, the movie. */
    fun markShowWatched(watched: Boolean) = write {
        if (showId != null) api.setShowWatched(showId, watched) else api.setWatched(movieItemId!!, watched)
    }

    /** Moves the show's (or movie's) files to the PC's Recycle Bin, then leaves the page. */
    fun deleteFromPc() {
        if (_deleting.value) return
        _deleting.value = true
        viewModelScope.launch {
            try {
                if (showId != null) api.deleteShow(showId) else api.deleteMovie(movieItemId!!)
                toaster.show(strings.movedToRecycleBin)
                // Stays set: the files are gone and the page is leaving.
                _navEvents.emit(NavEvent.Back)
            } catch (e: ApiException) {
                toaster.show(e.message.orEmpty())
                _deleting.value = false
            }
        }
    }

    private fun write(call: suspend () -> Unit) {
        if (_deleting.value) return
        viewModelScope.launch {
            try {
                call()
                load()
            } catch (e: ApiException) {
                toaster.show(e.message.orEmpty())
            }
        }
    }

    private suspend fun fetchShow(id: Int): UiState.Loaded {
        val detail = api.show(id)
        val seasons = detail.seasons.sortedWith(compareBy<SeasonDetail> { it.number == 0 }.thenBy { it.number })
        val previous = (_state.value as? UiState.Loaded)?.selectedSeason
        val selected = previous?.takeIf { p -> seasons.any { it.number == p } }
            ?: detail.resume?.season?.takeIf { r -> seasons.any { it.number == r } }
            ?: seasons.firstOrNull()?.number
            ?: 0
        return derive(UiState.Loaded(detail.show, null, seasons, selected, detail.resume, detail.fileCount))
    }

    private suspend fun fetchMovie(id: Int): UiState.Loaded {
        val detail = api.item(id)
        return derive(UiState.Loaded(null, detail.item, emptyList(), 0, null, fileCount = 1))
    }

    /** Fills the button labels and watched flags from the loaded data. */
    private fun derive(s: UiState.Loaded): UiState.Loaded {
        val target = primaryTarget(s)
        if (s.movie != null) {
            val watched = s.movie.progress?.watched == true
            val label = if (s.movie.inProgress()) strings.resume else strings.play
            return s.copy(primaryLabel = label, secondaryLabel = null, seasonWatched = watched, allWatched = watched)
        }
        val seasonWatched = s.selected?.episodes?.let { eps -> eps.isNotEmpty() && eps.all { it.watched() } } ?: false
        val allWatched = s.seasons.flatMap { it.episodes }.let { eps -> eps.isNotEmpty() && eps.all { it.watched() } }
        val label = when {
            target == null -> strings.play
            target.inProgress() -> {
                val p = target.progress!!
                strings.resumeEpisode(target.buttonTag(), formatRemaining(p.position, p.duration))
            }
            else -> strings.playEpisode(target.buttonTag())
        }
        return s.copy(
            primaryLabel = label,
            secondaryLabel = if (seasonWatched) strings.markSeasonUnwatched else strings.markSeasonWatched,
            seasonWatched = seasonWatched,
            allWatched = allWatched,
        )
    }

    private fun primaryTarget(s: UiState.Loaded): ItemSummary? =
        s.movie ?: s.resume ?: s.seasons.firstOrNull()?.episodes?.firstOrNull()

    class Factory(
        private val api: ApiClient,
        private val showId: Int?,
        private val movieItemId: Int?,
        private val toaster: Toaster,
        private val strings: Strings,
        private val reconnect: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShowViewModel(api, showId, movieItemId, toaster, strings, reconnect) as T
    }
}

/** Started and not finished: worth resuming. */
internal fun ItemSummary.inProgress(): Boolean = progress?.let { !it.watched && it.position > 0 && it.duration > 0 } == true

internal fun ItemSummary.watched(): Boolean = progress?.watched == true

/** Button form of the episode tag: `S2 E4`, `S3 E1–2`. */
internal fun ItemSummary.buttonTag(): String {
    val s = season ?: return ""
    val e = episode ?: return "S$s"
    return if (episodeEnd != null && episodeEnd != e) "S$s E$e–$episodeEnd" else "S$s E$e"
}
