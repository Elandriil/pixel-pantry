package app.pixelpantry.tv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.util.formatRuntime

val EpisodeCardWidth = 280.dp

/**
 * A 16:9 episode still with `N. Title` and the runtime below; a ✓ badge when watched and a progress bar when in
 * progress. OK calls [onClick]; long-press OK or the remote's MENU key call [onLongClick] (the episode menu).
 * [modifier] targets the focusable card itself.
 */
@Composable
fun EpisodeCard(
    item: ItemSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val number = item.episode?.let { e -> if (item.episodeEnd != null && item.episodeEnd != e) "$e–${item.episodeEnd}" else "$e" }
    val label = if (number != null) "$number. ${item.title}" else item.title
    val progress = item.progress?.takeIf { !it.watched && it.duration > 0 && it.position > 0 }
    Column(Modifier.width(EpisodeCardWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onPreviewKeyEvent {
                    val menu = it.key.nativeKeyCode == KeyEvent.KEYCODE_MENU
                    if (menu && it.type == KeyEventType.KeyUp) onLongClick()
                    menu
                },
            shape = CardStyle.shape,
            scale = CardStyle.scale,
            border = CardStyle.border,
            colors = CardStyle.colors,
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(path = item.still, fallbackText = number?.let { "E$it" } ?: item.title, modifier = Modifier.fillMaxSize())
                if (item.progress?.watched == true) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                if (progress != null) {
                    val fraction = (progress.position / progress.duration).toFloat().coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.White.copy(alpha = 0.25f)),
                    ) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        item.runtime?.let {
            Text(
                text = formatRuntime(it * 60),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
