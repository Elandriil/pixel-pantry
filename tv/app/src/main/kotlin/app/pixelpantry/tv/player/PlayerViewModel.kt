package app.pixelpantry.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.ItemDetail
import app.pixelpantry.tv.api.Track
import app.pixelpantry.tv.player.ProgressReporter.Reason
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays one item: loads its detail, starts the player at the saved position (0 when [fromStart] or watched),
 * follows the player every [TICK_MS] for the overlay and progress reports, and turns player failures into a
 * readable error. The player itself sits behind [PlayerPort]; this class has no Android dependencies.
 */
class PlayerViewModel(
    private val api: ApiClient,
    // Skip amounts come from here once the remote mapping lands (Task 9).
    @Suppress("unused") private val settingsRepo: SettingsRepo,
    private val reporter: ProgressReporter,
    private val toaster: Toaster,
    private val strings: Strings,
    private val itemId: Int,
    private val fromStart: Boolean,
    private val port: PlayerPort,
    /** Outlives this view model: the STOP report is sent from here so leaving never waits for the server. */
    private val exitScope: CoroutineScope,
) : ViewModel() {

    /** Texts from string resources. */
    class Strings(
        val playbackFailed: String,
        val comingLater: String,
        val subtitlesOff: String,
        val audioDefault: String,
    )

    sealed interface UiState {
        data object Loading : UiState

        /** [fileName] is null when the item itself could not be loaded. */
        data class Error(val title: String, val detail: String, val fileName: String?) : UiState

        data class Playing(
            val detail: ItemDetail,
            val positionMs: Long,
            /** Null until the player knows it. */
            val durationMs: Long?,
            val paused: Boolean,
            val overlayVisible: Boolean,
            val buffering: Boolean,
            val audioLabel: String,
            val subtitleLabel: String,
            val subDelayMs: Int,
        ) : UiState {
            val remainingMs: Long? get() = durationMs?.let { (it - positionMs).coerceAtLeast(0) }
        }
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Set once the user asked to leave. A state, not an event: the screen leaves when it can (its entry may not
     * be resumed yet, e.g. BACK during the enter transition), so the request cannot be lost.
     */
    private val _exitRequested = MutableStateFlow(false)
    val exitRequested: StateFlow<Boolean> = _exitRequested.asStateFlow()

    /** The player the screen renders (null in tests). */
    val exoPlayer get() = port.exoPlayer

    private var detail: ItemDetail? = null
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var hideJob: Job? = null
    private var reportJob: Job? = null
    private var exiting = false

    private val listener = object : PlayerListener {
        /** Pauses the player makes itself (audio focus lost, headphones out) show and report like the user's. */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val s = _state.value as? UiState.Playing ?: return
            if (s.paused == !playWhenReady) return
            updatePlaying { it.copy(paused = !playWhenReady) }
            if (!playWhenReady) {
                hideJob?.cancel()
                updatePlaying { it.copy(overlayVisible = true) }
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !exiting) flush(Reason.PAUSE)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            updatePlaying { it.copy(buffering = state == Player.STATE_BUFFERING) }
            if (state == Player.STATE_ENDED) {
                // Record the end so the item counts as watched; what happens next is Task 11 (autoplay).
                flush(Reason.STOP, atEnd = true)
                updatePlaying { it.copy(paused = true) }
                showOverlay()
            }
        }

        override fun onPlayerError(message: String) = fail(message)
    }

    init {
        port.addListener(listener)
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            val d = try {
                api.item(itemId)
            } catch (e: ApiException) {
                _state.value = UiState.Error(strings.playbackFailed, e.message.orEmpty(), fileName = null)
                return@launch
            }
            if (exiting) return@launch
            detail = d
            // Subtitle configurations (sidecars) arrive with Task 10's SubtitlePlan; embedded tracks need none.
            port.prepare(MediaItem.Builder().setUri(api.resolve(d.streamUrl)).setMediaId(d.item.id.toString()).build())
            val progress = d.item.progress
            val startMs = if (fromStart || progress == null || progress.watched) 0L else (progress.position * 1000).toLong()
            port.seekTo(startMs)
            port.play()
            reporter.start(itemId)
            _state.value = UiState.Playing(
                detail = d,
                positionMs = startMs,
                durationMs = port.durationMs,
                paused = false,
                overlayVisible = false,
                buffering = port.playbackState == Player.STATE_BUFFERING,
                audioLabel = audioLabel(d),
                subtitleLabel = subtitleLabel(d),
                subDelayMs = 0,
            )
            startTicker()
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val position = port.currentPositionMs
                val duration = port.durationMs
                updatePlaying { it.copy(positionMs = position, durationMs = duration) }
                // Off the ticker so a slow server never stalls the position; one report in flight at a time.
                if (port.isPlaying && duration != null && duration > 0 && reportJob?.isActive != true) {
                    reportJob = launch { reporter.tick(position / 1000.0, duration / 1000.0) }
                }
            }
        }
    }

    /** OK / Play-Pause: toggles and shows the overlay; pausing reports progress at once. */
    fun togglePause() {
        val s = _state.value as? UiState.Playing ?: return
        if (s.paused) {
            port.play()
            updatePlaying { it.copy(paused = false) }
            showOverlay()
        } else {
            port.pause()
            updatePlaying { it.copy(paused = true, positionMs = port.currentPositionMs) }
            showOverlay()
            flush(Reason.PAUSE)
        }
    }

    /** Skips by [seconds] (negative = back), clamped to the item, and shows the overlay. */
    fun seekBy(seconds: Int) {
        if (_state.value !is UiState.Playing) return
        seekTo(port.currentPositionMs + seconds * 1000L)
    }

    fun seekTo(ms: Long) {
        if (_state.value !is UiState.Playing) return
        val max = port.durationMs ?: Long.MAX_VALUE
        val target = ms.coerceIn(0, max)
        port.seekTo(target)
        updatePlaying { it.copy(positionMs = target) }
        showOverlay()
    }

    /** Shows the overlay; it hides [OVERLAY_HIDE_MS] after the last call unless playback is paused. */
    fun showOverlay() {
        val s = _state.value as? UiState.Playing ?: return
        if (!s.overlayVisible) _state.value = s.copy(overlayVisible = true)
        hideJob?.cancel()
        if (!s.paused) {
            hideJob = viewModelScope.launch {
                delay(OVERLAY_HIDE_MS)
                updatePlaying { if (it.paused) it else it.copy(overlayVisible = false) }
            }
        }
    }

    fun hideOverlay() {
        hideJob?.cancel()
        updatePlaying { it.copy(overlayVisible = false) }
    }

    /**
     * BACK with the overlay hidden, or from the loading / error screen: stops at once and asks to leave; the
     * position is reported on [exitScope] so a slow server never holds the user on this screen.
     */
    fun stopAndExit() {
        if (exiting) return
        exiting = true
        loadJob?.cancel()
        tickerJob?.cancel()
        hideJob?.cancel()
        if (_state.value is UiState.Playing) {
            port.pause()
            val duration = port.durationMs?.takeIf { it > 0 }
            val position = port.currentPositionMs
            if (duration != null) exitScope.launch { reporter.flush(position / 1000.0, duration / 1000.0, Reason.STOP) }
        }
        _exitRequested.value = true
    }

    /** The app went to the background: pause and save the position (playback resumes with OK). */
    fun onHostStopped() {
        if (exiting) return
        val s = _state.value as? UiState.Playing ?: return
        if (!s.paused) togglePause()
    }

    /** Prev / Next / Audio / Subtitles / Sub delay: wired up in later tasks. */
    fun notYetAvailable() {
        toaster.show(strings.comingLater)
        showOverlay()
    }

    private fun fail(message: String) {
        if (_state.value is UiState.Error) return
        if (_state.value is UiState.Playing) flush(Reason.STOP)
        tickerJob?.cancel()
        hideJob?.cancel()
        _state.value = UiState.Error(strings.playbackFailed, message, detail?.fileName)
    }

    private fun flush(reason: Reason, atEnd: Boolean = false) {
        viewModelScope.launch { flushNow(reason, atEnd) }
    }

    private suspend fun flushNow(reason: Reason, atEnd: Boolean = false) {
        val duration = port.durationMs?.takeIf { it > 0 } ?: return
        val position = if (atEnd) duration else port.currentPositionMs
        reporter.flush(position / 1000.0, duration / 1000.0, reason)
    }

    private inline fun updatePlaying(transform: (UiState.Playing) -> UiState.Playing) {
        _state.update { if (it is UiState.Playing) transform(it) else it }
    }

    private fun audioLabel(d: ItemDetail): String {
        val track = d.audio.firstOrNull { it.isDefault } ?: d.audio.firstOrNull() ?: return strings.audioDefault
        return trackLabel(track)
    }

    private fun subtitleLabel(d: ItemDetail): String =
        d.subtitles.firstOrNull { it.id == d.subtitleChoice }?.label ?: strings.subtitlesOff

    private fun trackLabel(t: Track): String =
        listOfNotNull(t.language?.uppercase(), t.codec).joinToString(" · ")

    override fun onCleared() {
        port.removeListener(listener)
        port.release()
    }

    class Factory(
        private val api: ApiClient,
        private val settingsRepo: SettingsRepo,
        private val reporter: () -> ProgressReporter,
        private val toaster: Toaster,
        private val strings: Strings,
        private val itemId: Int,
        private val fromStart: Boolean,
        private val port: () -> PlayerPort,
        private val exitScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(api, settingsRepo, reporter(), toaster, strings, itemId, fromStart, port(), exitScope) as T
    }

    companion object {
        const val TICK_MS = 500L
        const val OVERLAY_HIDE_MS = 4_000L
    }
}
