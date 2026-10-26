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

/** Temporary skip for LEFT/RIGHT with the overlay hidden; Task 9 takes it from the settings. */
private const val SKIP_SECONDS = 10

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
    val onBack = {
        if (current is UiState.Playing && current.overlayVisible) vm.hideOverlay() else vm.stopAndExit()
    }
    // BACK normally arrives through the key preview below (see handleBack); this covers the rest.
    BackHandler(onBack = onBack)

    // One focusable root for every state: the focused element must never be removed from under the next
    // state (e.g. Playing -> Error), or focus is lost and the error screen's Back button cannot take it.
    val rootFocus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e -> handleBack(e, onBack) || (current is UiState.Playing && handleKey(e, current, vm)) }
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
                onAction = { action ->
                    when (action) {
                        OverlayAction.TogglePause -> vm.togglePause()
                        is OverlayAction.SeekBy -> vm.seekBy(action.seconds)
                        OverlayAction.Prev, OverlayAction.Next,
                        OverlayAction.Audio, OverlayAction.Subtitles, OverlayAction.SubDelay -> vm.notYetAvailable()
                    }
                },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

/**
 * BACK must be caught before focus handling: with a control inside the overlay focused, Compose turns BACK into
 * a focus "exit" and consumes it, so the first press would only move focus. Acts on key-down, swallows key-up.
 */
private fun handleBack(e: KeyEvent, onBack: () -> Unit): Boolean {
    if (e.key != Key.Back) return false
    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onBack()
    return true
}

/**
 * Temporary remote mapping until Task 9's `PlayerKeys`: with the overlay hidden OK toggles pause, UP/DOWN show
 * the overlay and LEFT/RIGHT skip; with it shown keys go to the focused control and keep the overlay up.
 * Play/Pause always toggles. BACK is handled by [handleBack].
 */
private fun handleKey(e: KeyEvent, s: UiState.Playing, vm: PlayerViewModel): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    if (e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause) {
        vm.togglePause()
        return true
    }
    if (s.overlayVisible) {
        vm.showOverlay()
        return false
    }
    when (e.key) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> vm.togglePause()
        Key.DirectionUp, Key.DirectionDown -> vm.showOverlay()
        Key.DirectionLeft -> vm.seekBy(-SKIP_SECONDS)
        Key.DirectionRight -> vm.seekBy(SKIP_SECONDS)
        else -> return false
    }
    return true
}

@Composable
private fun Playback(
    state: UiState.Playing,
    exoPlayer: ExoPlayer?,
    rootFocus: FocusRequester,
    onAction: (OverlayAction) -> Unit,
) {
    val pauseFocus = remember { FocusRequester() }

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
            PlayerOverlay(state, onAction, pauseFocus, Modifier.align(Alignment.BottomCenter))
        }
    }

    // The overlay's Pause button takes focus when it appears; the root takes it back when it hides.
    LaunchedEffect(state.overlayVisible) {
        runCatching { if (state.overlayVisible) pauseFocus.requestFocus() else rootFocus.requestFocus() }
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
