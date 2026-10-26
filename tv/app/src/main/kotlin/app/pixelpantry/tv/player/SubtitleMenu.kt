package app.pixelpantry.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.SubtitleTrack
import app.pixelpantry.tv.player.PlayerViewModel.OnlineState

/**
 * The subtitle menu: embedded tracks, sidecar files, Off, then "Search online…", the current choice ticked.
 * Once a search started it shows [OnlineSubtitleSearch] instead. Presentational: every pick goes to a callback.
 */
@Composable
fun SubtitleMenu(
    tracks: List<SubtitleTrack>,
    choice: String,
    online: OnlineState,
    onSelect: (String) -> Unit,
    onSearchOnline: () -> Unit,
    onDownload: (Long) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    if (online != OnlineState.Idle) {
        OnlineSubtitleSearch(online, onDownload, firstFocus, modifier)
        return
    }
    val embedded = tracks.filter { it.format == "embedded" }
    val files = tracks.filter { it.format != "embedded" }
    val embeddedKind = stringResource(R.string.player_menu_embedded)
    val rows = embedded.map { it to embeddedKind } + files.map { it to it.format.uppercase() }
    PlayerMenuPanel(stringResource(R.string.player_menu_subtitles), modifier) {
        rows.forEachIndexed { i, (track, kind) ->
            item(track.id) {
                PlayerMenuRow(
                    label = track.label,
                    detail = listOfNotNull(track.lang?.uppercase(), kind).joinToString(" · "),
                    checked = track.id == choice,
                    onClick = { onSelect(track.id) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
        item(SubtitlePlan.OFF) {
            PlayerMenuRow(
                label = stringResource(R.string.player_subtitles_off),
                checked = choice == SubtitlePlan.OFF,
                onClick = { onSelect(SubtitlePlan.OFF) },
                modifier = if (rows.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
        item("search") {
            PlayerMenuRow(label = stringResource(R.string.player_menu_search_online), onClick = onSearchOnline)
        }
    }
}

/**
 * The menus' panel: the right third of the screen, full height, over the playing video. Focus cannot leave it
 * (the overlay behind is not focusable while it is up either); BACK is handled by the player screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerMenuPanel(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / 3)
                .padding(vertical = 32.dp)
                .padding(end = 32.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.player_menu_hint_back),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        }
    }
}

/** A menu row; [checked] marks the current choice with ✓. */
@Composable
internal fun PlayerMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    checked: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(if (checked) "✓" else "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
        }
    }
}

/**
 * A line of text that can hold focus, so the menu keeps focus (and BACK keeps working) while it has no rows, e.g.
 * during a search or after an error.
 */
@Composable
internal fun PlayerMenuStatus(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Box(modifier.fillMaxWidth().focusable().background(Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
