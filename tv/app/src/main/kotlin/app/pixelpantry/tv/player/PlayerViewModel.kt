package app.pixelpantry.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.ItemDetail
import app.pixelpantry.tv.api.OsResult
import app.pixelpantry.tv.api.SubtitleTrack
import app.pixelpantry.tv.api.Track
import app.pixelpantry.tv.player.SubtitlePlan.OFF
import app.pixelpantry.tv.player.ProgressReporter.Reason
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.CancellationException
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
    /** Skip amounts; refreshed from the server when the player opens. */
    private val settingsRepo: SettingsRepo,
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
        val subtitlesOff: String,
        val audioDefault: String,
        /** Toasts after a switch; the argument is [episodeLabel], e.g. `2. Half Loop`. */
        val nextEpisode: (String) -> String,
        val previousEpisode: (String) -> String,
        val noNextEpisode: String,
        val noPreviousEpisode: String,
        /** Sub delay pressed with an embedded track or none. */
        val delaySidecarOnly: String,
        /** Saving the track choice failed; the argument is the server's message. */
        val saveChoiceFailed: (String) -> String,
    )

    /** The panel open over the playing video. */
    enum class PlayerMenu { Subtitles, Audio }

    /** The subtitle menu's OpenSubtitles search. */
    sealed interface OnlineState {
        data object Idle : OnlineState
        data object Searching : OnlineState

        /** [applied]: the label of the subtitle just downloaded and shown; [downloading] while one downloads. */
        data class Results(val results: List<OsResult>, val applied: String? = null, val downloading: Boolean = false) : OnlineState

        /** The server's message, e.g. `OpenSubtitles not configured`. */
        data class Error(val message: String) : OnlineState
    }

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
            /** A subtitle id or [SubtitlePlan.OFF]. */
            val subtitleChoice: String = OFF,
            /** `audio:<index>`; null without audio tracks. */
            val audioChoice: String? = null,
            val menu: PlayerMenu? = null,
            val online: OnlineState = OnlineState.Idle,
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

    /** The end-of-episode countdown (Task 11); BACK cancels it while it runs. Always false until then. */
    private val _countdownActive = MutableStateFlow(false)
    val countdownActive: StateFlow<Boolean> = _countdownActive.asStateFlow()

    /** The player the screen renders (null in tests). */
    val exoPlayer get() = port.exoPlayer

    private var detail: ItemDetail? = null
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var hideJob: Job? = null
    private var reportJob: Job? = null
    private var exiting = false

    /** The item playing now; after a next/previous switch it is no longer the route's [itemId]. */
    var currentItemId = itemId
        private set

    /** A next/previous switch is in flight; further presses are ignored until it ends (Review Focus 5). */
    private var isSwitching = false

    /** The subtitle plan the prepared MediaItem was built from (its sidecar URIs, the chosen one). */
    var currentPlan: SubtitlePlan.Plan? = null
        private set

    /** The current item's subtitle tracks; refreshed after an online download. */
    val subtitleTracks: List<SubtitleTrack> get() = detail?.subtitles.orEmpty()

    /**
     * Track choices to apply once the player knows the prepared item's tracks (they are known only after
     * prepare); [PLAYER_DEFAULT] asks for the embedded track the file flags as default.
     */
    private var pendingText: String? = null
    private var pendingAudio: String? = null
    private var searchJob: Job? = null
    private var persistJob: Job? = null

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

        override fun onTracksChanged() = applyPendingTracks()
    }

    init {
        port.addListener(listener)
        load()
        // Skip amounts changed on the server (admin page) apply from the next playback; the cache covers offline.
        viewModelScope.launch {
            try {
                settingsRepo.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the cached settings.
            }
        }
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
            reporter.start(itemId)
            startPlayback(d, fromStart)
        }
    }

    /** Prepares [d] and plays it from its saved position (0 when [fromStart] or watched), overlay hidden. */
    private fun startPlayback(d: ItemDetail, fromStart: Boolean) {
        currentItemId = d.item.id
        detail = d
        searchJob?.cancel()
        // The stored choices are restored when they still name a track; the delay starts at 0 for every item.
        val plan = SubtitlePlan.build(d, api::resolve, d.subtitleChoice, shiftMs = 0)
        currentPlan = plan
        val subtitle = plan.subtitleChoice
        // Nothing stored (or stale) and no default known from the server: the file's own default flag decides.
        pendingText = if (subtitle == OFF && d.subtitleChoice != OFF) PLAYER_DEFAULT else subtitle
        pendingAudio = plan.audioChoice
        port.prepare(mediaItem(d, plan))
        val progress = d.item.progress
        val startMs = if (fromStart || progress == null || progress.watched) 0L else (progress.position * 1000).toLong()
        port.seekTo(startMs)
        port.play()
        hideJob?.cancel()
        _state.value = UiState.Playing(
            detail = d,
            positionMs = startMs,
            durationMs = port.durationMs,
            paused = false,
            overlayVisible = false,
            buffering = port.playbackState == Player.STATE_BUFFERING,
            audioLabel = audioLabel(d, plan.audioChoice),
            subtitleLabel = subtitleLabel(d, subtitle),
            subDelayMs = 0,
            subtitleChoice = subtitle,
            audioChoice = plan.audioChoice,
        )
        startTicker()
    }

    /** The item's stream with every sidecar of [plan]; the chosen one is flagged default. */
    private fun mediaItem(d: ItemDetail, plan: SubtitlePlan.Plan): MediaItem =
        MediaItem.Builder()
            .setUri(api.resolve(d.streamUrl))
            .setMediaId(d.item.id.toString())
            .setSubtitleConfigurations(
                plan.subtitleConfigs.map { c ->
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.uri))
                        .setMimeType(c.mimeType)
                        .setLabel(c.label)
                        .setLanguage(c.language)
                        .setId(c.id)
                        .setSelectionFlags(if (c.selected) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                },
            )
            .build()

    /** Applies [pendingText] / [pendingAudio] once the player knows the tracks; each is applied once. */
    private fun applyPendingTracks() {
        val d = detail ?: return
        if (!port.tracksKnown || _state.value !is UiState.Playing) return
        pendingText?.let { wanted ->
            pendingText = null
            val id = if (wanted == PLAYER_DEFAULT) SubtitlePlan.defaultEmbeddedId(d, port.textTracks) ?: OFF else wanted
            if (wanted == PLAYER_DEFAULT) updatePlaying { it.copy(subtitleChoice = id, subtitleLabel = subtitleLabel(d, id)) }
            if (id == OFF) {
                port.setTextTrack(null)
            } else {
                // A sidecar missing from the player's groups keeps its default flag as the selection.
                SubtitlePlan.textGroup(id, d, port.textTracks)?.let(port::setTextTrack)
            }
        }
        pendingAudio?.let { wanted ->
            pendingAudio = null
            SubtitlePlan.audioGroup(wanted, d, port.audioTracks)?.let(port::setAudioTrack)
        }
    }

    /**
     * Shows subtitle [id] (or [SubtitlePlan.OFF]) and saves the choice for the next play. An embedded track or
     * off only changes the track selection; a sidecar re-prepares the item at the current position (same
     * progress session, still playing if it was). Choosing another track resets the delay.
     */
    fun selectSubtitle(id: String) {
        val s = _state.value as? UiState.Playing ?: return
        val d = detail ?: return
        if (isSwitching || exiting) return
        if (id != OFF && d.subtitles.none { it.id == id }) return
        val delay = if (id == s.subtitleChoice) s.subDelayMs else 0
        updatePlaying { it.copy(subtitleChoice = id, subtitleLabel = subtitleLabel(d, id), subDelayMs = delay) }
        if (SubtitlePlan.isSidecar(id)) {
            reprepare(d, id, delay)
        } else {
            pendingText = id
            applyPendingTracks()
        }
        persistChoice()
    }

    /** Plays audio track `audio:<index>` and saves the choice. */
    fun selectAudio(id: String) {
        val d = detail ?: return
        if (_state.value !is UiState.Playing || isSwitching || exiting) return
        if (d.audio.none { "audio:${it.index}" == id }) return
        updatePlaying { it.copy(audioChoice = id, audioLabel = audioLabel(d, id)) }
        pendingAudio = id
        applyPendingTracks()
        persistChoice()
    }

    /**
     * Sub delay ±: re-times the chosen sidecar by [deltaMs] (the server shifts the file, `?shift=`); a no-op with
     * a toast for embedded tracks or none. Resets with every item.
     */
    fun adjustSubDelay(deltaMs: Int) {
        val s = _state.value as? UiState.Playing ?: return
        val d = detail ?: return
        if (isSwitching || exiting) return
        if (!SubtitlePlan.isSidecar(s.subtitleChoice)) {
            toaster.show(strings.delaySidecarOnly)
            showOverlay()
            return
        }
        val delay = s.subDelayMs + deltaMs
        updatePlaying { it.copy(subDelayMs = delay) }
        reprepare(d, s.subtitleChoice, delay)
        showOverlay()
    }

    /** Rebuilds the MediaItem with sidecar [choice] at [shiftMs] and prepares it where playback is now. */
    private fun reprepare(d: ItemDetail, choice: String, shiftMs: Int) {
        val s = _state.value as? UiState.Playing ?: return
        val plan = SubtitlePlan.build(d, api::resolve, choice, shiftMs)
        currentPlan = plan
        pendingText = plan.subtitleChoice
        pendingAudio = s.audioChoice
        port.prepare(mediaItem(d, plan), startPositionMs = port.currentPositionMs)
        if (!s.paused) port.play()
    }

    /**
     * Saves both choices for the current item; a failure only toasts (the choice still applies now). Saves run
     * one after another, so quick picks reach the server in order and the last one is what it keeps.
     */
    private fun persistChoice() {
        val s = _state.value as? UiState.Playing ?: return
        val itemId = currentItemId
        val previous = persistJob
        persistJob = viewModelScope.launch {
            previous?.join()
            try {
                api.setTracks(itemId, subtitle = s.subtitleChoice, audio = s.audioChoice)
            } catch (e: ApiException) {
                toaster.show(strings.saveChoiceFailed(e.message.orEmpty()))
            }
        }
    }

    /** Opens the subtitle or audio menu; playback goes on and the overlay stays up while it is open. */
    fun openMenu(menu: PlayerMenu) {
        if (_state.value !is UiState.Playing || isSwitching) return
        hideJob?.cancel()
        searchJob?.cancel()
        updatePlaying { it.copy(menu = menu, online = OnlineState.Idle, overlayVisible = true) }
    }

    /** BACK in a menu: closes it; the overlay hides again after [OVERLAY_HIDE_MS]. */
    fun closeMenu() {
        searchJob?.cancel()
        updatePlaying { it.copy(menu = null, online = OnlineState.Idle) }
        showOverlay()
    }

    /** "Search online…": asks the server's OpenSubtitles search; its error message shows in the menu. */
    fun searchOnline() {
        if (_state.value !is UiState.Playing) return
        val itemId = currentItemId
        searchJob?.cancel()
        updatePlaying { it.copy(online = OnlineState.Searching) }
        searchJob = viewModelScope.launch {
            val next = try {
                OnlineState.Results(api.searchSubtitles(itemId).results)
            } catch (e: ApiException) {
                OnlineState.Error(e.message.orEmpty())
            }
            if (currentItemId == itemId) updatePlaying { it.copy(online = next) }
        }
    }

    /**
     * Downloads OpenSubtitles file [fileId] next to the video, refreshes the item's tracks and shows the new one at
     * once; the results stay up so another pick is one click away.
     */
    fun download(fileId: Long) {
        val s = _state.value as? UiState.Playing ?: return
        val results = (s.online as? OnlineState.Results)?.results.orEmpty()
        val itemId = currentItemId
        searchJob?.cancel()
        updatePlaying { it.copy(online = OnlineState.Results(results, applied = null, downloading = true)) }
        searchJob = viewModelScope.launch {
            val track = try {
                api.downloadSubtitle(itemId, fileId)
            } catch (e: ApiException) {
                if (currentItemId == itemId) updatePlaying { it.copy(online = OnlineState.Error(e.message.orEmpty())) }
                return@launch
            }
            val tracks = try {
                api.subtitles(itemId).tracks
            } catch (e: ApiException) {
                null
            }
            val d = detail ?: return@launch
            if (currentItemId != itemId) return@launch
            val list = (tracks ?: d.subtitles).let { if (it.any { t -> t.id == track.id }) it else it + track }
            val refreshed = d.copy(subtitles = list)
            detail = refreshed
            updatePlaying { it.copy(detail = refreshed) }
            selectSubtitle(track.id)
            updatePlaying { it.copy(online = OnlineState.Results(results, applied = track.label)) }
        }
    }

    /** Dispatches a remote key's action (see [PlayerKeys]). */
    fun onAction(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePause -> togglePause()
            PlayerAction.Play -> play()
            PlayerAction.Pause -> pause()
            PlayerAction.SkipBack -> seekBy(-settingsRepo.settings.value.skipBackSeconds)
            PlayerAction.SkipForward -> seekBy(settingsRepo.settings.value.skipForwardSeconds)
            PlayerAction.ShowOverlay, PlayerAction.PassThrough -> showOverlay()
            PlayerAction.Next -> next()
            PlayerAction.Prev -> prev()
            PlayerAction.HideOverlay -> hideOverlay()
            PlayerAction.StopAndExit -> stopAndExit()
            PlayerAction.CancelCountdown -> cancelCountdown()
            PlayerAction.CloseMenu -> closeMenu()
        }
    }

    /** CH+ / Next: switches to the next episode at once. */
    fun next() = switchTo(forward = true)

    /** CH− / Prev: switches to the previous episode at once. */
    fun prev() = switchTo(forward = false)

    /** Stops the end-of-episode countdown (Task 11). */
    fun cancelCountdown() {
        _countdownActive.value = false
    }

    /**
     * Saves the position of the item being left (a SWITCH report, sent on [exitScope] with the position taken
     * now), loads the neighbour and plays it in a new progress session. Presses while a switch is in flight
     * are ignored, so rapid CH+ can neither overlap loads nor skip past an episode.
     */
    private fun switchTo(forward: Boolean) {
        if (exiting || isSwitching) return
        val s = _state.value as? UiState.Playing ?: return
        val target = if (forward) s.detail.next else s.detail.prev
        if (target == null) {
            toaster.show(if (forward) strings.noNextEpisode else strings.noPreviousEpisode)
            return
        }
        isSwitching = true
        tickerJob?.cancel()
        port.pause()
        // Sent even while the duration is unknown (as 0): the server ignores the duration then but keeps the session.
        val duration = port.durationMs?.takeIf { it > 0 } ?: 0L
        val position = port.currentPositionMs
        val saved = exitScope.launch { reporter.flush(position / 1000.0, duration / 1000.0, Reason.SWITCH) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val d = try {
                    api.item(target.id)
                } catch (e: ApiException) {
                    // Stay on the item we have; it is paused with its overlay up.
                    toaster.show(listOf(strings.playbackFailed, e.message.orEmpty()).joinToString(": "))
                    startTicker()
                    return@launch
                }
                // The old item's report must take the old item id before the reporter moves on.
                saved.join()
                if (exiting) return@launch
                reporter.start(d.item.id)
                startPlayback(d, fromStart = false)
                val label = episodeLabel(d)
                toaster.show(if (forward) strings.nextEpisode(label) else strings.previousEpisode(label))
            } finally {
                isSwitching = false
            }
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
        if (s.paused) play() else pause()
    }

    /**
     * ▶: resumes and shows the overlay (its hide timer starts again); already playing, only shows it. Ignored
     * while a next/previous switch is in flight (the item being left is paused and its position reported).
     */
    fun play() {
        val s = _state.value as? UiState.Playing ?: return
        if (isSwitching) return
        if (s.paused) {
            port.play()
            updatePlaying { it.copy(paused = false) }
        }
        showOverlay()
    }

    /** ‖: pauses, shows the overlay and reports progress once; already paused, only shows it. */
    fun pause() {
        val s = _state.value as? UiState.Playing ?: return
        if (isSwitching) return
        if (!s.paused) {
            port.pause()
            updatePlaying { it.copy(paused = true, positionMs = port.currentPositionMs) }
            flush(Reason.PAUSE)
        }
        showOverlay()
    }

    /** Skips by [seconds] (negative = back), clamped to the item, and shows the overlay. */
    fun seekBy(seconds: Int) {
        if (_state.value !is UiState.Playing) return
        seekTo(port.currentPositionMs + seconds * 1000L)
    }

    fun seekTo(ms: Long) {
        if (_state.value !is UiState.Playing || isSwitching) return
        val max = port.durationMs ?: Long.MAX_VALUE
        val target = ms.coerceIn(0, max)
        port.seekTo(target)
        updatePlaying { it.copy(positionMs = target) }
        showOverlay()
    }

    /**
     * Shows the overlay; it hides [OVERLAY_HIDE_MS] after the last call unless playback is paused or a menu is
     * open.
     */
    fun showOverlay() {
        val s = _state.value as? UiState.Playing ?: return
        if (!s.overlayVisible) _state.value = s.copy(overlayVisible = true)
        hideJob?.cancel()
        if (!s.paused && s.menu == null) {
            hideJob = viewModelScope.launch {
                delay(OVERLAY_HIDE_MS)
                updatePlaying { if (it.paused || it.menu != null) it else it.copy(overlayVisible = false) }
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

    private fun fail(message: String) {
        // After BACK the STOP report is already out and the screen is leaving: no second report, no error screen.
        if (exiting || _state.value is UiState.Error) return
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

    private fun audioLabel(d: ItemDetail, choice: String?): String {
        val track = d.audio.firstOrNull { "audio:${it.index}" == choice } ?: return strings.audioDefault
        return trackLabel(track)
    }

    private fun subtitleLabel(d: ItemDetail, choice: String): String =
        d.subtitles.firstOrNull { it.id == choice }?.label ?: strings.subtitlesOff

    /** `2. Half Loop` for an episode, the title for a movie. */
    private fun episodeLabel(d: ItemDetail): String =
        d.item.episode?.let { "$it. ${d.item.title}" } ?: d.item.title

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

        /** A pending text choice: the embedded track the file flags as default, else off. */
        private const val PLAYER_DEFAULT = "player-default"
    }
}
