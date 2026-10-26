package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImage

/** How screens load server images: the app's Coil loader plus the server-base prefixing of image paths. */
class ImageSource(val loader: ImageLoader, val resolve: (path: String) -> String)

val LocalImageSource = staticCompositionLocalOf<ImageSource> { error("No ImageSource provided") }

/**
 * A server image (path relative to the server base) on a surface-coloured box. While loading the box shows
 * nothing; with no path or a failed load it shows [fallbackText] centred, so unmatched items look intentional.
 */
@Composable
fun RemoteImage(path: String?, fallbackText: String?, modifier: Modifier = Modifier) {
    val source = LocalImageSource.current
    var failed by remember(path) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        if (path != null && !failed) {
            AsyncImage(
                model = source.resolve(path),
                imageLoader = source.loader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallbackText != null) {
            Text(
                text = fallbackText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
