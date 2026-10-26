package app.pixelpantry.tv.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.HistoryEntry
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.api.Stats
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.components.isUnreachable
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import app.pixelpantry.tv.util.formatEpisodeTag
import app.pixelpantry.tv.util.formatWatched
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Watch history grouped by local day (newest first, as the server sends it) plus the stats tiles. Reloads each
 * time the page is shown again; a reload that fails keeps the page and toasts [Strings.couldNotRefresh].
 */
class HistoryViewModel(
    private val api: ApiClient,
    private val strings: Strings,
    private val toaster: Toaster,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    /** Resolves the server once more (see `AppContainer.reconnect`); run by [retry]. */
    private val reconnect: suspend () -> Unit = {},
) : ViewModel() {

    data class Strings(val today: String, val yesterday: String, val couldNotRefresh: String)

    /** One session; [title] is `Show · S2 · E4 · Episode title` for episodes, the title for movies. */
    data class HistoryRow(
        val entry: HistoryEntry,
        val item: ItemSummary,
        val title: String,
        val watchedLabel: String,
        val timeLabel: String,
    )

    /** One local day; [date] is its stable identity, [label] what is shown. */
    data class DayGroup(val date: LocalDate, val label: String, val entries: List<HistoryRow>)

    sealed interface UiState {
        data object Loading : UiState
        data class Error(val message: String) : UiState
        /** The first load got no answer from [baseUrl]. */
        data class Unreachable(val baseUrl: String) : UiState
        /** [days] is empty when nothing has been watched yet. */
        data class Loaded(val stats: Stats, val days: List<DayGroup>) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val dayWithYearFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    private var loadJob: Job? = null
    private var resumedBefore = false

    init {
        load()
    }

    /** Fetches history and stats. Shows [UiState.Loading] unless a page is already on screen. */
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
        // Restored after process death before any server was resolved: resolve first.
        if (api.baseUrl.isBlank()) reconnect()
        try {
            _state.value = coroutineScope {
                val history = async { api.history() }
                val stats = async { api.stats() }
                UiState.Loaded(stats.await(), group(history.await().entries))
            }
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

    /** Episodes open their show's page, movies the movie page. An episode without a show id goes nowhere. */
    fun open(row: HistoryRow) {
        val event = when (row.item.type) {
            "episode" -> row.item.showId?.let { NavEvent.Show(it) }
            else -> NavEvent.Movie(row.item.id)
        } ?: return
        _navEvents.tryEmit(event)
    }

    private fun group(entries: List<HistoryEntry>): List<DayGroup> {
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        return entries
            .mapNotNull { entry -> entry.item?.let { entry to it } }
            .groupBy { (entry, _) -> Instant.ofEpochMilli(entry.startedAt).atZone(zone).toLocalDate() }
            .map { (date, rows) -> DayGroup(date, dayLabel(date, today), rows.map { (entry, item) -> row(entry, item) }) }
    }

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> strings.today
        today.minusDays(1) -> strings.yesterday
        else -> date.format(if (date.year == today.year) dayFormatter else dayWithYearFormatter)
    }

    private fun row(entry: HistoryEntry, item: ItemSummary): HistoryRow {
        val tag = formatEpisodeTag(item.season, item.episode, item.episodeEnd)
        val title = if (item.type == "episode") {
            listOfNotNull(item.showTitle, tag.ifEmpty { null }, item.title).joinToString(" · ")
        } else {
            item.title
        }
        return HistoryRow(
            entry = entry,
            item = item,
            title = title,
            watchedLabel = formatWatched(entry.secondsWatched),
            timeLabel = Instant.ofEpochMilli(entry.startedAt).atZone(zone).format(timeFormatter),
        )
    }

    class Factory(
        private val api: ApiClient,
        private val strings: Strings,
        private val toaster: Toaster,
        private val reconnect: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(api, strings, toaster, reconnect = reconnect) as T
    }
}
