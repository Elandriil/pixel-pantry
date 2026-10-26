package app.pixelpantry.tv.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.ui.components.CardRow
import app.pixelpantry.tv.ui.components.TvTextField
import app.pixelpantry.tv.ui.components.key
import app.pixelpantry.tv.ui.components.rememberRowsBringIntoViewSpec
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent

/**
 * Search page content: the query field on top, "Shows" and "Movies" rows below (each hidden when empty).
 * Focus starts in the field; coming back from a show or movie page puts it back on the card that was opened.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(vm: SearchViewModel, onNavigate: (NavEvent) -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navEvents.collect(onNavigate) }

    // Local field state keeps typing responsive; every change goes to the ViewModel, which owns the search.
    var text by rememberSaveable { mutableStateOf(vm.query.value) }
    // The card focus was last on (null while the field has it), restored when the page is shown again.
    var focusedCard by rememberSaveable { mutableStateOf<String?>(null) }
    val fieldFocus = remember { FocusRequester() }
    val cardFocus = remember { FocusRequester() }

    val shows = remember(results) { results?.shows.orEmpty().map { Card.ShowCard(it) } }
    val movies = remember(results) { results?.movies.orEmpty().map { Card.ItemCard(it) } }
    val shortQuery = text.trim().length < SearchViewModel.MIN_CHARS
    val cardShown = focusedCard != null && (shows + movies).any { it.key() == focusedCard }
    // The last focused card when it is still listed, else the field. Before the rows are laid out the card's
    // requester is not attached yet; the field takes focus then.
    fun focusTarget() {
        val onCard = cardShown && runCatching { cardFocus.requestFocus() }.isSuccess
        if (!onCard) runCatching { fieldFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 40.dp)
            .focusProperties { enter = { if (cardShown) cardFocus else fieldFocus } }
            // Compose 1.7 can hand focus to this group itself (the shell's focus restorer); pass it on.
            .onFocusChanged { if (it.isFocused) focusTarget() }
            .focusGroup(),
    ) {
        TvTextField(
            value = text,
            onValueChange = {
                text = it
                vm.setQuery(it)
            },
            onSubmit = {},
            label = stringResource(R.string.search_label),
            placeholder = stringResource(R.string.search_hint),
            imeAction = ImeAction.Search,
            width = 720.dp,
            modifier = Modifier
                .padding(start = 48.dp)
                .focusRequester(fieldFocus)
                .onFocusChanged { if (it.hasFocus) focusedCard = null },
        )
        val message = when {
            error != null -> stringResource(R.string.search_failed, error.orEmpty())
            !shortQuery && results != null && shows.isEmpty() && movies.isEmpty() -> stringResource(R.string.search_no_results)
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, top = 32.dp),
            )
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowsBringIntoViewSpec()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shows.isNotEmpty()) {
                    item(key = "shows") { ResultRow(stringResource(R.string.search_shows), shows, focusedCard, cardFocus, vm::open) { focusedCard = it } }
                }
                if (movies.isNotEmpty()) {
                    item(key = "movies") { ResultRow(stringResource(R.string.search_movies), movies, focusedCard, cardFocus, vm::open) { focusedCard = it } }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // One frame first, so the result rows are composed and a restored card can take focus.
        withFrameNanos { }
        focusTarget()
    }
}

@Composable
private fun ResultRow(
    title: String,
    cards: List<Card>,
    focusedCard: String?,
    cardFocus: FocusRequester,
    onClick: (Card) -> Unit,
    onFocus: (String) -> Unit,
) {
    CardRow(
        title = title,
        cards = cards,
        onFocus = { onFocus(it.key()) },
        onClick = onClick,
        onLongClick = {},
        focusKey = focusedCard?.takeIf { key -> cards.any { it.key() == key } },
        focusRequester = cardFocus,
    )
}
