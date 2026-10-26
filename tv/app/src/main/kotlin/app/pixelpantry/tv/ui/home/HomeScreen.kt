package app.pixelpantry.tv.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.ui.components.ButtonSpec
import app.pixelpantry.tv.ui.components.CardRow
import app.pixelpantry.tv.ui.components.ErrorScreen
import app.pixelpantry.tv.ui.components.FocusableButton
import app.pixelpantry.tv.ui.components.HeroStrip
import app.pixelpantry.tv.ui.components.LoadingScreen
import app.pixelpantry.tv.ui.components.key
import app.pixelpantry.tv.ui.components.rememberRowsBringIntoViewSpec
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import app.pixelpantry.tv.ui.home.HomeViewModel.UiState

/** Content of a browse page (sits inside the app shell): hero strip over the card rows. */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onNavigate: (NavEvent) -> Unit,
    onCardLongClick: (Card) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navEvents.collect(onNavigate) }

    when (val s = state) {
        UiState.Loading -> LoadingScreen(stringResource(R.string.loading))
        is UiState.Error -> ErrorScreen(
            title = stringResource(R.string.home_error_title),
            detail = s.message,
            primary = ButtonSpec(stringResource(R.string.home_retry), vm::load),
        )
        is UiState.Loaded ->
            if (s.rows.isEmpty()) EmptyLibrary(onRescan = vm::rescan)
            else Rows(s, onFocus = vm::onFocus, onClick = vm::open, onLongClick = onCardLongClick)
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Rows(
    state: UiState.Loaded,
    onFocus: (Card) -> Unit,
    onClick: (Card) -> Unit,
    onLongClick: (Card) -> Unit,
) {
    // One requester follows the focused card (row + key: a card can sit in two rows). It is the target when
    // focus enters the rows — on first load, on return from a detail page, from the sidebar — so focus always
    // lands on the last focused card. Row and column scroll positions come back through rememberSaveable.
    val focusedKey = state.focused?.key()
    var savedRow by rememberSaveable { mutableStateOf<String?>(null) }
    val focusedRow = savedRow?.takeIf { key -> state.rows.any { r -> r.key == key && r.cards.any { it.key() == focusedKey } } }
        ?: state.rows.firstOrNull { row -> row.cards.any { it.key() == focusedKey } }?.key
    val lastFocused = remember { FocusRequester() }

    Column(
        Modifier
            .fillMaxSize()
            .focusProperties { enter = { lastFocused } }
            // Compose 1.7 can hand focus to this group itself (the shell's focus restorer, or RIGHT from the
            // expanded sidebar that overlaps the first cards); pass it on to the card.
            .onFocusChanged { if (it.isFocused) runCatching { lastFocused.requestFocus() } }
            .focusGroup(),
    ) {
        HeroStrip(state.focused, Modifier.fillMaxWidth().fillMaxHeight(0.4f))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowsBringIntoViewSpec()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.key }) { row ->
                    CardRow(
                        title = row.title,
                        cards = row.cards,
                        onFocus = { card -> savedRow = row.key; onFocus(card) },
                        onClick = onClick,
                        onLongClick = onLongClick,
                        landscape = row.key == "continue",
                        focusKey = focusedKey.takeIf { row.key == focusedRow },
                        focusRequester = lastFocused,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { lastFocused.requestFocus() } }
}

@Composable
private fun EmptyLibrary(onRescan: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                text = stringResource(R.string.home_empty),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            FocusableButton(stringResource(R.string.home_rescan), onRescan, focusRequester = focus)
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
