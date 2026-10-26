package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

val PosterCardWidth = 160.dp

/**
 * A 2:3 poster (shows and movies) with the title below. Long-press OK or MENU call [onLongClick]. [modifier]
 * targets the focusable card itself.
 */
@Composable
fun PosterCard(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.width(PosterCardWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).menuKey(onLongClick),
            shape = CardStyle.shape,
            scale = CardStyle.scale,
            border = CardStyle.border,
            colors = CardStyle.colors,
        ) {
            RemoteImage(path = poster, fallbackText = title, modifier = Modifier.fillMaxSize())
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
