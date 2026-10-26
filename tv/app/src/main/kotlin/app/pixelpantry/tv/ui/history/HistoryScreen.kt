package app.pixelpantry.tv.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.Stats
import app.pixelpantry.tv.ui.components.ButtonSpec
import app.pixelpantry.tv.ui.components.ErrorScreen
import app.pixelpantry.tv.ui.components.LoadingScreen
import app.pixelpantry.tv.ui.components.RemoteImage
import app.pixelpantry.tv.ui.history.HistoryViewModel.HistoryRow
import app.pixelpantry.tv.ui.history.HistoryViewModel.UiState
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import app.pixelpantry.tv.util.formatHours

/** History page content: stat tiles over the sessions, grouped by day. */
@Composable
fun HistoryScreen(vm: HistoryViewModel, onNavigate: (NavEvent) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.navEvents.collect(onNavigate) }
    // Refresh when shown again (back from a show page or the player) so the newest sessions are listed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    when (val s = state) {
        UiState.Loading -> LoadingScreen(stringResource(R.string.loading))
        is UiState.Error -> ErrorScreen(
            title = stringResource(R.string.history_error_title),
            detail = s.message,
            primary = ButtonSpec(stringResource(R.string.home_retry), vm::load),
        )
        is UiState.Loaded -> Loaded(s, vm::open)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Loaded(state: UiState.Loaded, onOpen: (HistoryRow) -> Unit) {
    // The session focus was last on; it gets focus again when the page is shown again.
    var focusedSession by rememberSaveable { mutableStateOf<Int?>(null) }
    val rowFocus = remember { FocusRequester() }
    val rows = state.days.flatMap { it.entries }
    val target = rows.firstOrNull { it.entry.sessionId == focusedSession } ?: rows.firstOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 32.dp)
            .focusProperties { enter = { if (target != null) rowFocus else FocusRequester.Default } }
            // Compose 1.7 can hand focus to this group itself (the shell's focus restorer); pass it on.
            .onFocusChanged { if (it.isFocused && target != null) runCatching { rowFocus.requestFocus() } }
            .focusGroup(),
    ) {
        StatTiles(state.stats, Modifier.padding(horizontal = 48.dp))
        if (state.days.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.days.forEach { day ->
                item(key = "day:${day.date}") {
                    Text(
                        day.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(day.entries, key = { it.entry.sessionId }) { row ->
                    EntryRow(
                        row = row,
                        onClick = { onOpen(row) },
                        modifier = Modifier
                            .onFocusChanged { if (it.hasFocus) focusedSession = row.entry.sessionId }
                            .then(if (row == target) Modifier.focusRequester(rowFocus) else Modifier),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // One frame first, so the list rows are composed and the requester is attached.
        withFrameNanos { }
        runCatching { rowFocus.requestFocus() }
    }
}

@Composable
private fun StatTiles(stats: Stats, modifier: Modifier = Modifier) {
    val top = stats.topShowMonth
    val tiles = listOf(
        stringResource(R.string.history_hours_week) to formatHours(stats.hoursWeek),
        stringResource(R.string.history_hours_month) to formatHours(stats.hoursMonth),
        stringResource(R.string.history_hours_all) to formatHours(stats.hoursAll),
        stringResource(R.string.history_episodes) to stats.episodesAll.toString(),
        stringResource(R.string.history_movies) to stats.moviesAll.toString(),
        stringResource(R.string.history_top_show) to
            (top?.let { stringResource(R.string.history_top_show_value, it.title, formatHours(it.hours)) } ?: stringResource(R.string.history_none)),
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEachIndexed { index, (label, value) ->
            Column(
                Modifier
                    // The top-show tile holds a title; give it more room.
                    .weight(if (index == tiles.lastIndex) 2.4f else 1f)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines: "Hours this month" does not fit a sixth of the width on one.
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EntryRow(row: HistoryRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            // Lighter than the surface-coloured thumbnail, so an image-less row still reads as focused.
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            focusedContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val item = row.item
            RemoteImage(
                path = item.still ?: item.backdrop ?: item.poster,
                fallbackText = null,
                modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    row.watchedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            Text(row.timeLabel, style = MaterialTheme.typography.titleMedium)
        }
    }
}
