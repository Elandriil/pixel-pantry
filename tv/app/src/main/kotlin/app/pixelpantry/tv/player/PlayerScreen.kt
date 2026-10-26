package app.pixelpantry.tv.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.pixelpantry.tv.R
import app.pixelpantry.tv.player.PlayerViewModel.UiState
import app.pixelpantry.tv.ui.components.ButtonSpec
import app.pixelpantry.tv.ui.components.ErrorScreen
import app.pixelpantry.tv.ui.components.LoadingScreen
import app.pixelpantry.tv.ui.components.Spinner

/**
 * Full-screen playback: video, the overlay on demand, a buffering spinner, or the error screen. [onExit] leaves
 * the screen; it is called once, as soon as an exit was requested and this screen's entry is resumed.
 */
@Composable
fun PlayerScreen(vm: PlayerViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val exitRequested by vm.exitRequested.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val exited = remember { booleanArrayOf(false) }
    LaunchedEffect(exitRequested, lifecycleState) {
        if (exitRequested && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !exited[0]) {
            exited[0] = true
            onExit()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onHostStopped() }

    val current = state
    val countdownActive by vm.countdownActive.collectAsState()
    // Where overlay focus is, for PlayerKeys; read in the key preview only, so no state needed.
    val focus = remember { OverlayFocus() }
    // BACK normally arrives through the key preview below; this covers the rest.
    BackHandler {
        onKey(KeyEventLike(KeyCode.BACK, isDown = true), current, countdownActive, focus, vm)
    }

    // One focusable root for every state: the focused element must never be removed from under the next
    // state (e.g. Playing -> Error), or focus is lost and the error screen's Back button cannot take it.
    val rootFocus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Before focus handling: with an overlay control focused, Compose would turn BACK into a focus "exit"
            // and LEFT/RIGHT into focus moves.
            .onPreviewKeyEvent { e -> onKey(e.toKeyEventLike(), current, countdownActive, focus, vm) }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        when (current) {
            UiState.Loading -> LoadingScreen(stringResource(R.string.loading))
            is UiState.Error -> ErrorScreen(
                title = current.title,
                detail = listOfNotNull(current.detail, current.fileName).joinToString("\n"),
                primary = ButtonSpec(stringResource(R.string.player_back), vm::stopAndExit),
            )
            is UiState.Playing -> Playback(
                state = current,
                exoPlayer = vm.exoPlayer,
                rootFocus = rootFocus,
                focus = focus,
                onAction = { action ->
                    when (action) {
                        OverlayAction.TogglePause -> vm.togglePause()
                        OverlayAction.Prev -> vm.prev()
                        OverlayAction.Next -> vm.next()
                        OverlayAction.Audio -> vm.openMenu(PlayerViewModel.PlayerMenu.Audio)
                        OverlayAction.Subtitles -> vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
                        is OverlayAction.SubDelay -> vm.adjustSubDelay(action.deltaMs)
                    }
                },
                onSelectSubtitle = { id ->
                    vm.selectSubtitle(id)
                    vm.closeMenu()
                },
                onSelectAudio = { id ->
                    vm.selectAudio(id)
                    vm.closeMenu()
                },
                onSearchOnline = vm::searchOnline,
                onDownload = vm::download,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

/** Overlay focus as the key handling needs it. */
private class OverlayFocus {
    /**
     * A button of the overlay has focus. Otherwise the seek bar has it, or is about to: right after a skip
     * shows the overlay, the next LEFT/RIGHT may arrive before focus has moved, and must skip too.
     */
    var onButton = false

    /** The next time the overlay appears, focus goes to the seek bar (after a skip) instead of Pause. */
    var seekBarOnShow = false
}

/**
 * Runs the remote mapping ([PlayerKeys]) for one key event; true when the player consumed it. Off the Playing
 * state only BACK acts (leave); other keys go to the loading / error screen.
 */
private fun onKey(key: KeyEventLike, s: UiState, countdownActive: Boolean, focus: OverlayFocus, vm: PlayerViewModel): Boolean {
    if (s !is UiState.Playing) {
        if (key.code != KeyCode.BACK) return false
        if (key.isDown && key.repeatCount == 0) vm.stopAndExit()
        return true
    }
    val onButton = s.overlayVisible && focus.onButton
    val menuOpen = s.menu != null
    val action = PlayerKeys.map(key, s.overlayVisible, onButton, countdownActive, menuOpen)
    // Skipping keeps focus on the seek bar, so further LEFT/RIGHT keep skipping.
    if (action == PlayerAction.SkipBack || action == PlayerAction.SkipForward) focus.seekBarOnShow = true
    action?.let(vm::onAction)
    return PlayerKeys.consumes(key, s.overlayVisible, onButton, countdownActive, menuOpen)
}

private fun KeyEvent.toKeyEventLike(): KeyEventLike = KeyEventLike(
    code = when (key) {
        Key.DirectionCenter -> KeyCode.DPAD_CENTER
        Key.Enter, Key.NumPadEnter -> KeyCode.ENTER
        Key.MediaPlayPause -> KeyCode.MEDIA_PLAY_PAUSE
        Key.MediaPlay -> KeyCode.MEDIA_PLAY
        Key.MediaPause -> KeyCode.MEDIA_PAUSE
        Key.DirectionLeft -> KeyCode.DPAD_LEFT
        Key.DirectionRight -> KeyCode.DPAD_RIGHT
        Key.DirectionUp -> KeyCode.DPAD_UP
        Key.DirectionDown -> KeyCode.DPAD_DOWN
        Key.ChannelUp -> KeyCode.CHANNEL_UP
        Key.ChannelDown -> KeyCode.CHANNEL_DOWN
        Key.MediaNext -> KeyCode.MEDIA_NEXT
        Key.MediaPrevious -> KeyCode.MEDIA_PREVIOUS
        Key.Back -> KeyCode.BACK
        Key.Menu -> KeyCode.MENU
        else -> KeyCode.OTHER
    },
    isDown = type == KeyEventType.KeyDown,
    repeatCount = nativeKeyEvent.repeatCount,
)

@Composable
private fun Playback(
    state: UiState.Playing,
    exoPlayer: ExoPlayer?,
    rootFocus: FocusRequester,
    focus: OverlayFocus,
    onAction: (OverlayAction) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSearchOnline: () -> Unit,
    onDownload: (Long) -> Unit,
) {
    val pauseFocus = remember { FocusRequester() }
    val seekBarFocus = remember { FocusRequester() }
    val audioFocus = remember { FocusRequester() }
    val subtitlesFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val nextFocus = remember { FocusRequester() }

    // Keep the TV from dimming while video plays (a paused player may let it sleep).
    val view = LocalView.current
    DisposableEffect(view, state.paused) {
        view.keepScreenOn = !state.paused
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize()) {
        VideoSurface(exoPlayer)
        if (state.buffering && !state.paused) {
            Spinner(Modifier.align(Alignment.Center), color = Color.White)
        }
        if (state.overlayVisible) {
            PlayerOverlay(
                state, onAction, pauseFocus, seekBarFocus,
                onButtonsFocusChanged = { focus.onButton = it },
                audioFocus = audioFocus,
                subtitlesFocus = subtitlesFocus,
                nextFocus = nextFocus,
                modifier = Modifier.align(Alignment.BottomCenter),
                interactive = state.menu == null,
            )
        }
        val upNext = state.detail.next
        if (state.countdownSeconds != null && upNext != null) {
            AutoplayBanner(upNext, state.countdownSeconds, Modifier.align(Alignment.TopEnd))
        }
        when (state.menu) {
            PlayerViewModel.PlayerMenu.Subtitles -> SubtitleMenu(
                tracks = state.detail.subtitles,
                choice = state.subtitleChoice,
                online = state.online,
                onSelect = onSelectSubtitle,
                onSearchOnline = onSearchOnline,
                onDownload = onDownload,
                firstFocus = menuFocus,
            )
            PlayerViewModel.PlayerMenu.Audio -> AudioMenu(
                tracks = state.detail.audio,
                choice = state.audioChoice,
                onSelect = onSelectAudio,
                firstFocus = menuFocus,
            )
            null -> Unit
        }
    }

    // The menu's first row takes focus when it opens or its content changes (list -> search -> results); when
    // it closes, the button that opened it takes focus back.
    val lastMenu = remember { arrayOfNulls<PlayerViewModel.PlayerMenu>(1) }
    val onlineKey = state.online.let { it::class to ((it as? PlayerViewModel.OnlineState.Results)?.results?.isEmpty()) }
    LaunchedEffect(state.menu, onlineKey) {
        val menu = state.menu
        if (menu != null) {
            lastMenu[0] = menu
            withFrameNanos { } // the rows are composed
            runCatching { menuFocus.requestFocus() }
        } else {
            val closed = lastMenu[0] ?: return@LaunchedEffect
            lastMenu[0] = null
            withFrameNanos { } // the overlay's buttons can take focus again
            runCatching { (if (closed == PlayerViewModel.PlayerMenu.Audio) audioFocus else subtitlesFocus).requestFocus() }
        }
    }

    // The overlay's Pause button (the seek bar, after a skip) takes focus when it appears; the root takes it
    // back when it hides. At the end of an item with no countdown running, Next episode takes it (Pause, i.e.
    // replay, without a next one).
    val atEnd = state.ended && state.countdownSeconds == null
    LaunchedEffect(state.overlayVisible, atEnd) {
        val target = when {
            !state.overlayVisible -> rootFocus
            atEnd && state.detail.next != null -> nextFocus
            focus.seekBarOnShow -> seekBarFocus
            else -> pauseFocus
        }
        focus.seekBarOnShow = false
        if (!state.overlayVisible) focus.onButton = false
        runCatching { target.requestFocus() }
    }
}

/** ExoPlayer's view, without its own controls; subtitles render through its `SubtitleView`. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(exoPlayer: ExoPlayer?) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                // Keys belong to Compose; the view must never take focus.
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { it.player = exoPlayer },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
