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
    data object SubDelay : OverlayAction
}

/**
 * The bottom control panel: title, end time, seek bar with elapsed / remaining, and the buttons row.
 * [pauseFocus] is attached to the Pause/Play button, [seekBarFocus] to the seek bar; the screen decides which
 * takes focus when the overlay appears. Remote keys on the seek bar (skips, OK) are handled by the screen's key
 * preview (`PlayerKeys`); [onButtonsFocusChanged] tells it whether a button has focus.
 */
@Composable
fun PlayerOverlay(
    state: UiState.Playing,
    onAction: (OverlayAction) -> Unit,
    pauseFocus: FocusRequester,
    seekBarFocus: FocusRequester,
    onButtonsFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
                modifier = Modifier.onFocusChanged { onButtonsFocusChanged(it.hasFocus) },
            ) {
                OverlayButton(stringResource(R.string.player_prev), enabled = state.detail.prev != null) {
                    onAction(OverlayAction.Prev)
                }
                OverlayButton(
                    stringResource(if (state.paused) R.string.player_play else R.string.player_pause),
                    modifier = Modifier.focusRequester(pauseFocus),
                ) { onAction(OverlayAction.TogglePause) }
                OverlayButton(stringResource(R.string.player_next), enabled = state.detail.next != null) {
                    onAction(OverlayAction.Next)
                }
                Spacer(Modifier.weight(1f))
                OverlayButton(stringResource(R.string.player_audio, state.audioLabel)) { onAction(OverlayAction.Audio) }
                OverlayButton(stringResource(R.string.player_subtitles, state.subtitleLabel)) {
                    onAction(OverlayAction.Subtitles)
                }
                OverlayButton(stringResource(R.string.player_sub_delay, formatSubDelay(state.subDelayMs))) {
                    onAction(OverlayAction.SubDelay)
                }
            }
        }
    }
}

/** `Severance · S2 E4 · Title` for an episode, the title for a movie. */
private fun titleLine(item: app.pixelpantry.tv.api.ItemSummary): String =
    if (item.type == "episode") listOfNotNull(item.showTitle, item.buttonTag().ifEmpty { null }, item.title).joinToString(" · ")
    else item.title

/** `+0.5s`, `-1.0s`, `+0.0s`. */
internal fun formatSubDelay(ms: Int): String = String.format(Locale.ROOT, "%+.1fs", ms / 1000.0)

/** A disabled button cannot take focus, so D-pad moves skip over it. */
@Composable
private fun OverlayButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusProperties { canFocus = enabled },
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
private fun SeekBar(fraction: Float, focus: FocusRequester, down: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .focusRequester(focus)
            .focusProperties { this.down = down }
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
