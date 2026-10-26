package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.api.Card

private val RowVerticalPadding = 12.dp

/** Stable identity of a card within a row (and across reloads). */
fun Card.key(): String = when (this) {
    is Card.ShowCard -> "show:${show.id}"
    is Card.ItemCard -> "item:${item.id}"
}

/** Keeps the focused card about 15 % from the row's leading edge while scrolling. */
@OptIn(ExperimentalFoundationApi::class)
private object PivotBringIntoViewSpec : BringIntoViewSpec {
    private const val PIVOT = 0.15f
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - PIVOT * containerSize
}

/** Height of a row's title plus the row's top padding: the distance from a row's top to its cards. */
val CardRowHeaderHeight = 44.dp

/**
 * For the vertical list of rows: scrolls so the focused card sits [CardRowHeaderHeight] below the top, i.e. its
 * whole row (title, cards, captions) is in view rather than just the card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberRowsBringIntoViewSpec(): BringIntoViewSpec {
    val keyline = with(LocalDensity.current) { CardRowHeaderHeight.toPx() }
    return remember(keyline) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - keyline
        }
    }
}

/**
 * A titled horizontal row of cards. [landscape] renders item cards as [LandscapeCard] (continue watching);
 * otherwise every card is a [PosterCard]. The card whose key is [focusKey] gets [focusRequester], so the screen
 * can put focus on it. Returning to the row restores the last focused card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CardRow(
    title: String,
    cards: List<Card>,
    onFocus: (Card) -> Unit,
    onClick: (Card) -> Unit,
    onLongClick: (Card) -> Unit,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    focusKey: String? = null,
    focusRequester: FocusRequester? = null,
    state: LazyListState = rememberLazyListState(),
) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(CardRowHeaderHeight - RowVerticalPadding).padding(start = 48.dp),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotBringIntoViewSpec) {
            LazyRow(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = RowVerticalPadding, bottom = RowVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(cards, key = { it.key() }) { card ->
                    val cardModifier = Modifier
                        .onFocusChanged { if (it.hasFocus) onFocus(card) }
                        .then(
                            if (focusRequester != null && card.key() == focusKey) Modifier.focusRequester(focusRequester)
                            else Modifier,
                        )
                    if (landscape && card is Card.ItemCard) {
                        LandscapeCard(card.item, card.reason, { onClick(card) }, { onLongClick(card) }, cardModifier)
                    } else {
                        val (name, poster) = when (card) {
                            is Card.ShowCard -> card.show.title to card.show.poster
                            is Card.ItemCard -> card.item.title to card.item.poster
                        }
                        PosterCard(name, poster, { onClick(card) }, { onLongClick(card) }, cardModifier)
                    }
                }
            }
        }
    }
}
