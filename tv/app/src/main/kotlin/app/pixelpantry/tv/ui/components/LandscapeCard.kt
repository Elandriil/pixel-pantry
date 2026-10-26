package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.util.formatEpisodeTag
import app.pixelpantry.tv.util.formatRemaining

val LandscapeCardWidth = 280.dp

/**
 * A 16:9 continue-watching card: episode still (or the backdrop), episode tag, progress bar, and below it the
 * show title with "23 min left" or "Next" ([reason] is the server's `resume` / `next`). Long-press OK or MENU
 * call [onLongClick].
 */
@Composable
fun LandscapeCard(
    item: ItemSummary,
    reason: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = item.showTitle ?: item.title
    val tag = formatEpisodeTag(item.season, item.episode, item.episodeEnd)
    val progress = item.progress?.takeIf { it.duration > 0 && it.position > 0 }
    val subtitle = when {
        reason == "next" -> stringResource(R.string.card_next)
        progress != null -> formatRemaining(progress.position, progress.duration)
        else -> null
    }
    Column(Modifier.width(LandscapeCardWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).menuKey(onLongClick),
            shape = CardStyle.shape,
            scale = CardStyle.scale,
            border = CardStyle.border,
            colors = CardStyle.colors,
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(path = item.still ?: item.backdrop, fallbackText = item.title, modifier = Modifier.fillMaxSize())
                if (tag.isNotEmpty()) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
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
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
