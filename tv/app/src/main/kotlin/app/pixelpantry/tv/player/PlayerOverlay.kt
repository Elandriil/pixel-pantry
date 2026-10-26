package app.pixelpantry.tv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.player.PlayerViewModel.UiState
import app.pixelpantry.tv.ui.show.buttonTag
import app.pixelpantry.tv.util.formatClock
import app.pixelpantry.tv.util.formatEndsAt
import java.time.LocalTime
import java.util.Locale

/** What the overlay asks the player to do. */
sealed interface OverlayAction {
    data object TogglePause : OverlayAction
    data object Prev : OverlayAction
    data object Next : OverlayAction
    data object Audio : OverlayAction
    data object Subtitles : OverlayAction
    /** LEFT/RIGHT on the Sub delay button: ∓0.5 s; OK on it: 0. */
    data class SubDelay(val deltaMs: Int) : OverlayAction
}

/**
 * The bottom control panel: title, end time, seek bar with elapsed / remaining, and the buttons row.
 * [pauseFocus] is attached to the Pause/Play button, [seekBarFocus] to the seek bar, [nextFocus] to Next episode;
 * the screen decides which takes focus when the overlay appears. Remote keys on the seek bar (skips, OK) are handled by the screen's key
 * preview (`PlayerKeys`); [onButtonsFocusChanged] tells it whether a button has focus. [audioFocus] and
 * [subtitlesFocus] take focus back when their menu closes. While a menu is open ([interactive] false) nothing
 * here can take focus, so it stays in the menu.
 */
@Composable
fun PlayerOverlay(
    state: UiState.Playing,
    onAction: (OverlayAction) -> Unit,
    pauseFocus: FocusRequester,
    seekBarFocus: FocusRequester,
    onButtonsFocusChanged: (Boolean) -> Unit,
    audioFocus: FocusRequester,
    subtitlesFocus: FocusRequester,
    nextFocus: FocusRequester,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val item = state.detail.item
    val remainingSec = (state.remainingMs ?: 0) / 1000.0
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(start = 48.dp, end = 48.dp, top = 96.dp, bottom = 32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    titleLine(item),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.durationMs != null) {
                    Text(
                        formatEndsAt(remainingSec, LocalTime.now()),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
            SeekBar(
                fraction = state.durationMs?.takeIf { it > 0 }?.let { state.positionMs.toFloat() / it } ?: 0f,
                focus = seekBarFocus,
                down = pauseFocus,
                interactive = interactive,
            )
            Row {
                Text(formatClock(state.positionMs / 1000.0), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.player_remaining, formatClock(remainingSec)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onFocusChanged { onButtonsFocusChanged(it.hasFocus) }
                    .focusProperties { canFocus = interactive },
            ) {
                OverlayButton(stringResource(R.string.player_prev), enabled = state.detail.prev != null, interactive = interactive) {
                    onAction(OverlayAction.Prev)
                }
                OverlayButton(
                    stringResource(if (state.paused) R.string.player_play else R.string.player_pause),
                    modifier = Modifier.focusRequester(pauseFocus),
                    interactive = interactive,
                ) { onAction(OverlayAction.TogglePause) }
                OverlayButton(
                    stringResource(R.string.player_next),
                    modifier = Modifier.focusRequester(nextFocus),
                    enabled = state.detail.next != null,
                    interactive = interactive,
                ) { onAction(OverlayAction.Next) }
                Spacer(Modifier.weight(1f))
                OverlayButton(
                    stringResource(R.string.player_audio, state.audioLabel),
                    modifier = Modifier.focusRequester(audioFocus),
                    interactive = interactive,
                ) { onAction(OverlayAction.Audio) }
                OverlayButton(
                    stringResource(R.string.player_subtitles, state.subtitleLabel),
                    modifier = Modifier.focusRequester(subtitlesFocus),
                    interactive = interactive,
                ) { onAction(OverlayAction.Subtitles) }
                // With a subtitle file chosen, LEFT/RIGHT on this button change the delay (the player's key
                // mapping passes them through to it); otherwise they move focus as usual. OK explains when the
                // delay does not apply.
                val delayable = SubtitlePlan.isSidecar(state.subtitleChoice)
                OverlayButton(
                    stringResource(R.string.player_sub_delay, formatSubDelay(state.subDelayMs)),
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        if (!delayable) return@onPreviewKeyEvent false
                        val delta = when (e.key) {
                            Key.DirectionLeft -> -SUB_DELAY_STEP_MS
                            Key.DirectionRight -> SUB_DELAY_STEP_MS
                            else -> return@onPreviewKeyEvent false
                        }
                        // One step per press (a held key does not repeat); the view model debounces re-prepares.
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                            onAction(OverlayAction.SubDelay(delta))
                        }
                        true
                    },
                    interactive = interactive,
                ) { onAction(OverlayAction.SubDelay(0)) }
            }
        }
    }
}

/**
 * The end-of-episode countdown card: "Next episode in N s… press BACK to cancel" over the next episode's
 * `N. Title`. Presentational: [secondsLeft] comes from the view model, which also handles BACK.
 */
@Composable
fun AutoplayBanner(next: app.pixelpantry.tv.api.ItemSummary, secondsLeft: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(32.dp)
            .widthIn(max = 560.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.player_autoplay_countdown, secondsLeft),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            next.episode?.let { "$it. ${next.title}" } ?: next.title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** `Severance · S2 E4 · Title` for an episode, the title for a movie. */
private fun titleLine(item: app.pixelpantry.tv.api.ItemSummary): String =
    if (item.type == "episode") listOfNotNull(item.showTitle, item.buttonTag().ifEmpty { null }, item.title).joinToString(" · ")
    else item.title

/** `+0.5s`, `-1.0s`, `0.0s`. */
internal fun formatSubDelay(ms: Int): String =
    if (ms == 0) "0.0s" else String.format(Locale.ROOT, "%+.1fs", ms / 1000.0)

private const val SUB_DELAY_STEP_MS = 500

/** A disabled button cannot take focus, so D-pad moves skip over it; nor can any while a menu is open. */
@Composable
private fun OverlayButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactive: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusProperties { canFocus = enabled && interactive },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Focusable progress bar (LEFT/RIGHT skip, OK toggles pause: see `PlayerKeys`). DOWN goes to [down], the
 * Pause button. The knob grows while focused.
 */
@Composable
private fun SeekBar(fraction: Float, focus: FocusRequester, down: FocusRequester, interactive: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .focusRequester(focus)
            .focusProperties {
                this.down = down
                canFocus = interactive
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
    ) {
        val trackHeight = (if (focused) 6.dp else 4.dp).toPx()
        val top = (size.height - trackHeight) / 2
        val f = fraction.coerceIn(0f, 1f)
        val radius = CornerRadius(trackHeight / 2)
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, top), Size(size.width, trackHeight), radius)
        drawRoundRect(accent, Offset(0f, top), Size(size.width * f, trackHeight), radius)
        val knob = (if (focused) 10.dp else 6.dp).toPx()
        drawCircle(if (focused) Color.White else accent, knob, Offset(size.width * f, size.height / 2))
    }
}
