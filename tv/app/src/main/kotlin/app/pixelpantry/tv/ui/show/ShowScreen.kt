package app.pixelpantry.tv.ui.show

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.api.SeasonDetail
import app.pixelpantry.tv.ui.components.ActionMenu
import app.pixelpantry.tv.ui.components.ButtonSpec
import app.pixelpantry.tv.ui.components.ConfirmDialog
import app.pixelpantry.tv.ui.components.EpisodeCard
import app.pixelpantry.tv.ui.components.ErrorScreen
import app.pixelpantry.tv.ui.components.FocusableButton
import app.pixelpantry.tv.ui.components.LoadingScreen
import app.pixelpantry.tv.ui.components.MenuItem
import app.pixelpantry.tv.ui.components.PivotBringIntoViewSpec
import app.pixelpantry.tv.ui.components.RemoteImage
import app.pixelpantry.tv.ui.components.UnreachableScreen
import app.pixelpantry.tv.ui.show.ShowViewModel.NavEvent
import app.pixelpantry.tv.ui.show.ShowViewModel.UiState
import app.pixelpantry.tv.util.formatRuntime
import kotlinx.coroutines.launch

private const val SEP = " · "
private val SideMargin = 48.dp
private val PosterWidth = 180.dp

/**
 * The page scrolls between two positions (top: header; bottom: seasons and episodes, see [Page]), so focus moves
 * must not scroll by themselves: Compose's TV default would pivot every focused element to 30 % of the height.
 * This spec scrolls only as far as needed to reveal an element that is out of view.
 */
@OptIn(ExperimentalFoundationApi::class)
private object MinimalBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f
            offset < 0 && trailing > containerSize -> 0f
            offset < 0 -> offset
            else -> trailing - containerSize
        }
    }
}

/** What the `⋯` / long-press menu is open for. */
private sealed interface MenuTarget {
    data object Page : MenuTarget
    data class Episode(val item: ItemSummary) : MenuTarget
}

/** The show or movie page (sits inside the app shell). The primary button has focus when it opens. */
@Composable
fun ShowScreen(vm: ShowViewModel, onNavigate: (NavEvent) -> Unit, onChangeServer: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navEvents.collect(onNavigate) }
    // Refresh when shown again (back from the player) so progress and the Resume button are current.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    when (val s = state) {
        UiState.Loading -> LoadingScreen(stringResource(R.string.loading))
        is UiState.Error -> ErrorScreen(
            title = stringResource(R.string.show_error_title),
            detail = s.message,
            primary = ButtonSpec(stringResource(R.string.show_retry), vm::load),
        )
        is UiState.Unreachable -> UnreachableScreen(s.baseUrl, onRetry = vm::retry, onChangeServer = onChangeServer)
        is UiState.Loaded -> DetailPage(s, vm)
    }
}

@Composable
private fun DetailPage(state: UiState.Loaded, vm: ShowViewModel) {
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Page(
        state = state,
        onPrimary = vm::primaryAction,
        onSecondary = { vm.markSeasonWatched(state.selectedSeason, !state.seasonWatched) },
        onMore = { menu = MenuTarget.Page },
        onSelectSeason = vm::selectSeason,
        onEpisodeClick = { vm.play(it, fromStart = false) },
        onEpisodeMenu = { menu = MenuTarget.Episode(it) },
    )

    when (val target = menu) {
        null -> Unit
        MenuTarget.Page -> {
            val markLabel = when {
                state.movie != null && state.allWatched -> stringResource(R.string.menu_mark_unwatched)
                state.movie != null -> stringResource(R.string.menu_mark_watched)
                state.allWatched -> stringResource(R.string.menu_mark_show_unwatched)
                else -> stringResource(R.string.menu_mark_show_watched)
            }
            ActionMenu(
                items = listOf(
                    MenuItem(markLabel) { vm.markShowWatched(!state.allWatched) },
                    MenuItem(stringResource(R.string.menu_delete_from_pc)) { confirmDelete = true },
                ),
                onDismiss = { menu = null },
            )
        }
        is MenuTarget.Episode -> {
            val watched = target.item.watched()
            ActionMenu(
                items = listOf(
                    MenuItem(stringResource(if (watched) R.string.menu_mark_unwatched else R.string.menu_mark_watched)) {
                        vm.markWatched(target.item, !watched)
                    },
                    MenuItem(stringResource(R.string.menu_play_from_start)) { vm.play(target.item, fromStart = true) },
                ),
                onDismiss = { menu = null },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_title, state.title),
            body = pluralStringResource(R.plurals.delete_body, state.fileCount, state.fileCount),
            confirmLabel = stringResource(R.string.dialog_delete),
            onConfirm = { confirmDelete = false; vm.deleteFromPc() },
            onCancel = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Page(
    state: UiState.Loaded,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onMore: () -> Unit,
    onSelectSeason: (Int) -> Unit,
    onEpisodeClick: (ItemSummary) -> Unit,
    onEpisodeMenu: (ItemSummary) -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Whether the page was shown before (survives the trip to the player) / resumed in this composition.
    // Both are written and read only in the resume callback below, never during composition.
    var shownBefore by rememberSaveable { mutableStateOf(false) }
    var resumedHere by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val backdropHeight = maxHeight * 0.45f
        CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalBringIntoViewSpec) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 32.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // Focus in the header shows the whole header (top of the page) ...
                        .onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } },
                ) {
                    Backdrop(state.show?.backdrop ?: state.movie?.backdrop, Modifier.fillMaxWidth().height(backdropHeight))
                    Header(
                        state = state,
                        primaryFocus = primaryFocus,
                        onPrimary = onPrimary,
                        onSecondary = onSecondary,
                        onMore = onMore,
                        modifier = Modifier.padding(start = SideMargin, end = SideMargin, top = 40.dp),
                    )
                }
                if (state.seasons.isNotEmpty()) {
                    // ... and focus in the seasons shows tabs and episodes whole (bottom of the page).
                    Column(Modifier.onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(scroll.maxValue) } }) {
                        SeasonTabs(state.seasons, state.selectedSeason, onSelectSeason, Modifier.padding(start = SideMargin, top = 24.dp))
                        state.selected?.let { season ->
                            EpisodeRow(season, state.resume, onEpisodeClick, onEpisodeMenu)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }
    // Coming back from the player the request above does not take: focus entering the shell's content goes
    // through its focus restorer, which aims at the last focused episode card (not composed yet) and leaves a
    // focused group with no focused element. Once resumed, focus is already inside the group, so requesting
    // the primary button is a move within it and works. Ordering: this runs after the LaunchedEffect above and
    // after the shell's own initial focus request (AppShell's effect), so it has the last word on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (shownBefore && !resumedHere) runCatching { primaryFocus.requestFocus() }
        resumedHere = true
        shownBefore = true
    }
}

@Composable
private fun Backdrop(path: String?, modifier: Modifier) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier) {
        if (path != null) RemoteImage(path = path, fallbackText = null, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to background.copy(alpha = 0.8f), 0.6f to background.copy(alpha = 0.2f), 1f to Color.Transparent),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to background)))
    }
}

@Composable
private fun Header(
    state: UiState.Loaded,
    primaryFocus: FocusRequester,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val poster = state.show?.poster ?: state.movie?.poster
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Bottom) {
        RemoteImage(
            path = poster,
            fallbackText = state.title,
            modifier = Modifier.width(PosterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = metaLine(state)
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            (state.show?.overview ?: state.movie?.overview)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusableButton(state.primaryLabel, onPrimary, focusRequester = primaryFocus)
                state.secondaryLabel?.let { FocusableButton(it, onSecondary) }
                FocusableButton(stringResource(R.string.show_more), onMore)
            }
        }
    }
}

/** `2022 · 2 seasons · 19 episodes · ★ 8.7 · Drama, Mystery`; for movies `1995 · 2 h 50 min`. */
@Composable
private fun metaLine(state: UiState.Loaded): String {
    val show = state.show
    val movie = state.movie
    val parts = if (show != null) {
        val seasonCount = state.seasons.count { it.number != 0 }
        listOfNotNull(
            show.year?.toString(),
            if (seasonCount > 0) pluralStringResource(R.plurals.show_seasons, seasonCount, seasonCount) else null,
            pluralStringResource(R.plurals.show_episodes, show.episodeCount, show.episodeCount),
            show.rating?.let { stringResource(R.string.show_rating, it) },
            show.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
    } else {
        listOfNotNull(movie?.year?.toString(), movie?.runtime?.let { formatRuntime(it * 60) })
    }
    return parts.joinToString(SEP)
}

/** One tab per season, Specials last; focusing a tab (LEFT/RIGHT) shows its episodes. Entering the row lands on the selected tab. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeasonTabs(seasons: List<SeasonDetail>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val selectedIndex = seasons.indexOfFirst { it.number == selected }.coerceAtLeast(0)
    val requesters = remember(seasons.size) { List(seasons.size) { FocusRequester() } }
    Box(modifier.focusProperties { enter = { requesters[selectedIndex] } }.focusGroup()) {
        TabRow(selectedTabIndex = selectedIndex) {
            seasons.forEachIndexed { index, season ->
                Tab(
                    selected = index == selectedIndex,
                    onFocus = { onSelect(season.number) },
                    modifier = Modifier.focusRequester(requesters[index]),
                ) {
                    Text(
                        text = season.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The selected season's episodes, scrolled to the resume episode when it is in this season. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun EpisodeRow(
    season: SeasonDetail,
    resume: ItemSummary?,
    onClick: (ItemSummary) -> Unit,
    onMenu: (ItemSummary) -> Unit,
) {
    key(season.number) {
        val start = season.episodes.indexOfFirst { it.id == resume?.id }.coerceAtLeast(0)
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
        CompositionLocalProvider(LocalBringIntoViewSpec provides PivotBringIntoViewSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(start = SideMargin, end = SideMargin, top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(season.episodes, key = { it.id }) { episode ->
                    EpisodeCard(episode, onClick = { onClick(episode) }, onLongClick = { onMenu(episode) })
                }
            }
        }
    }
}
