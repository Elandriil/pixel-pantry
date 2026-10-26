package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.util.formatEpisodeTag
import app.pixelpantry.tv.util.formatRemaining
import app.pixelpantry.tv.util.formatRuntime

private const val SEP = " · "

/**
 * Details of the focused card above the rows: the backdrop faded into the background, the title (the show's for
 * episodes), a meta line (`S2 · E4 · Episode`; year · runtime for movies; year · episodes · unwatched for shows) and a status line ("23 min left",
 * "Next: S1 · E7").
 */
@Composable
fun HeroStrip(card: Card?, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier) {
        val backdrop = when (card) {
            is Card.ShowCard -> card.show.backdrop
            is Card.ItemCard -> card.item.backdrop
            null -> null
        }
        if (backdrop != null) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).align(Alignment.TopEnd)) {
                RemoteImage(path = backdrop, fallbackText = null, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(0f to background, 0.5f to background.copy(alpha = 0.3f), 1f to Color.Transparent),
                    ),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.4f to Color.Transparent, 1f to background),
                    ),
                )
            }
        }
        if (card != null) {
            val lines = heroLines(card)
            Column(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.6f).padding(start = 48.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = lines.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lines.meta.isNotEmpty()) {
                    Text(
                        text = lines.meta,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                lines.status?.let {
                    Text(text = it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private class HeroLines(val title: String, val meta: String, val status: String?)

@Composable
private fun heroLines(card: Card): HeroLines = when (card) {
    is Card.ShowCard -> {
        val show = card.show
        val meta = listOfNotNull(
            show.year?.toString(),
            pluralStringResource(R.plurals.hero_episodes, show.episodeCount, show.episodeCount),
            if (show.unwatchedCount > 0) stringResource(R.string.hero_unwatched, show.unwatchedCount) else null,
        ).joinToString(SEP)
        HeroLines(show.title, meta, card.nextUp?.let { stringResource(R.string.hero_next, it.tag()) })
    }
    is Card.ItemCard -> {
        val item = card.item
        val progress = item.progress?.takeIf { it.duration > 0 && it.position > 0 }
        val status = when {
            card.reason == "next" && item.type == "episode" -> stringResource(R.string.hero_next, item.tag())
            progress != null -> formatRemaining(progress.position, progress.duration)
            else -> null
        }
        if (item.type == "episode") {
            // The show title is the headline, so together they read `Show · S2 · E4 · Episode`.
            val meta = listOfNotNull(item.tag().ifEmpty { null }, item.title.takeIf { item.showTitle != null })
                .joinToString(SEP)
            HeroLines(item.showTitle ?: item.title, meta, status)
        } else {
            val meta = listOfNotNull(item.year?.toString(), item.runtime?.let { formatRuntime(it * 60) }).joinToString(SEP)
            HeroLines(item.title, meta, status)
        }
    }
}

private fun ItemSummary.tag(): String = formatEpisodeTag(season, episode, episodeEnd)
