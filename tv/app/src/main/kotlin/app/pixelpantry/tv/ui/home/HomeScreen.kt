package app.pixelpantry.tv.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.ui.components.ActionMenu
import app.pixelpantry.tv.ui.components.ButtonSpec
import app.pixelpantry.tv.ui.components.CardGridRow
import app.pixelpantry.tv.ui.components.CardRow
import app.pixelpantry.tv.ui.components.CardRowTitle
import app.pixelpantry.tv.ui.components.ConfirmDialog
import app.pixelpantry.tv.ui.components.MenuItem
import app.pixelpantry.tv.ui.components.posterGridColumns
import app.pixelpantry.tv.ui.components.ErrorScreen
import app.pixelpantry.tv.ui.components.FocusableButton
import app.pixelpantry.tv.ui.components.HeroStrip
import app.pixelpantry.tv.ui.components.LoadingScreen
import app.pixelpantry.tv.ui.components.UnreachableScreen
import app.pixelpantry.tv.ui.components.key
import app.pixelpantry.tv.ui.components.rememberRowsBringIntoViewSpec
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import app.pixelpantry.tv.ui.home.HomeViewModel.UiState

/** Content of a browse page (sits inside the app shell): hero strip over the card rows. */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onNavigate: (NavEvent) -> Unit,
    onChangeServer: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navEvents.collect(onNavigate) }
    // Refresh when the page is shown again (back from the player or a detail page) so progress is current.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    when (val s = state) {
        UiState.Loading -> LoadingScreen(stringResource(R.string.loading))
        is UiState.Error -> ErrorScreen(
            title = stringResource(R.string.home_error_title),
            detail = s.message,
            primary = ButtonSpec(stringResource(R.string.home_retry)) { vm.load() },
        )
        is UiState.Unreachable -> UnreachableScreen(s.baseUrl, onRetry = vm::retry, onChangeServer = onChangeServer)
        is UiState.Loaded ->
            if (s.rows.isEmpty()) EmptyLibrary(onRescan = vm::rescan)
            else {
                var menu by remember { mutableStateOf<HomeViewModel.CardTarget?>(null) }
                Rows(s, onFocus = vm::onFocus, onClick = vm::open, onLongClick = { card -> vm.menuTarget(card)?.let { menu = it } })
                menu?.let { target -> CardMenu(target, vm, onDismiss = { menu = null }) }
                DeleteDialog(vm)
            }
    }
}

/** Long-press OK / MENU on a card: mark the whole show (or the movie) watched or unwatched, or delete it. */
@Composable
private fun CardMenu(target: HomeViewModel.CardTarget, vm: HomeViewModel, onDismiss: () -> Unit) {
    val mark = when (target) {
        is HomeViewModel.CardTarget.Show -> MenuItem(
            stringResource(if (target.watched) R.string.menu_mark_show_unwatched else R.string.menu_mark_show_watched),
        ) { vm.markShowWatched(target.id, !target.watched) }
        is HomeViewModel.CardTarget.Movie -> MenuItem(
            stringResource(if (target.watched) R.string.menu_mark_unwatched else R.string.menu_mark_watched),
        ) { vm.markMovieWatched(target.id, !target.watched) }
    }
    ActionMenu(
        items = listOf(mark, MenuItem(stringResource(R.string.menu_delete_from_pc)) { vm.requestDelete(target) }),
        onDismiss = onDismiss,
    )
}

/** The delete confirmation, with the same copy as the show page. */
@Composable
private fun DeleteDialog(vm: HomeViewModel) {
    val confirm by vm.deleteConfirm.collectAsStateWithLifecycle()
    val c = confirm ?: return
    ConfirmDialog(
        title = stringResource(R.string.delete_title, c.target.title),
        body = pluralStringResource(R.plurals.delete_body, c.fileCount, c.fileCount),
        confirmLabel = stringResource(R.string.dialog_delete),
        onConfirm = vm::confirmDelete,
        onCancel = vm::cancelDelete,
    )
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
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val columns = posterGridColumns(maxWidth)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.rows.forEach { row ->
                        if (row.key == ALL_ROW) {
                            // "All (A–Z)" is a vertical poster grid (spec): the title, then lines of posters.
                            // The title shares the first line's item, so that line scrolls into view like a row.
                            row.cards.chunked(columns).forEachIndexed { i, line ->
                                item(key = "$ALL_ROW:${line.first().key()}") {
                                    Column {
                                        if (i == 0) CardRowTitle(row.title)
                                        CardGridRow(
                                            cards = line,
                                            onFocus = { card -> savedRow = row.key; onFocus(card) },
                                            onClick = onClick,
                                            onLongClick = onLongClick,
                                            focusKey = focusedKey.takeIf { row.key == focusedRow },
                                            focusRequester = lastFocused,
                                        )
                                    }
                                }
                            }
                        } else {
                            item(key = row.key) {
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
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { lastFocused.requestFocus() } }
}

/** The server's key for the "All (A–Z)" row. */
private const val ALL_ROW = "all"

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
