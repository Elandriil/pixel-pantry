package app.pixelpantry.tv.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.pixelpantry.tv.R

val SidebarCollapsedWidth = 56.dp
val SidebarExpandedWidth = 220.dp

enum class SidebarEntry(@StringRes val label: Int, val icon: ImageVector, val bottom: Boolean = false) {
    HOME(R.string.nav_home, PantryIcons.Home),
    TV(R.string.nav_tv, PantryIcons.Tv),
    ANIME(R.string.nav_anime, PantryIcons.Anime),
    MOVIES(R.string.nav_movies, PantryIcons.Movie),
    SEARCH(R.string.nav_search, PantryIcons.Search),
    HISTORY(R.string.nav_history, PantryIcons.History),
    RESCAN(R.string.nav_rescan, PantryIcons.Refresh, bottom = true),
    SETTINGS(R.string.nav_settings, PantryIcons.Settings, bottom = true),
}

/**
 * Plex-style navigation rail: icons only (56 dp) while focus is elsewhere, labels (220 dp) while any entry has
 * focus. Focus entering the rail from content lands on [selected]. [SidebarEntry.RESCAN] is an action and goes
 * to [onRescan]; every other entry goes to [onSelect].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(
    selected: SidebarEntry,
    onSelect: (SidebarEntry) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) SidebarExpandedWidth else SidebarCollapsedWidth, label = "sidebarWidth")
    val requesters = remember { SidebarEntry.entries.associateWith { FocusRequester() } }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 24.dp)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { enter = { requesters.getValue(selected) } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val (bottom, top) = SidebarEntry.entries.partition { it.bottom }
        top.forEach { entry ->
            SidebarItem(entry, entry == selected, expanded, requesters.getValue(entry)) { activate(entry, onSelect, onRescan) }
        }
        Spacer(Modifier.weight(1f))
        bottom.forEach { entry ->
            SidebarItem(entry, entry == selected, expanded, requesters.getValue(entry)) { activate(entry, onSelect, onRescan) }
        }
    }
}

private fun activate(entry: SidebarEntry, onSelect: (SidebarEntry) -> Unit, onRescan: () -> Unit) =
    if (entry == SidebarEntry.RESCAN) onRescan() else onSelect(entry)

@Composable
private fun SidebarItem(
    entry: SidebarEntry,
    isSelected: Boolean,
    expanded: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(44.dp).focusRequester(focusRequester),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxHeight().padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(entry.icon, contentDescription = stringResource(entry.label), modifier = Modifier.size(24.dp))
            if (expanded) {
                Text(
                    text = stringResource(entry.label),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }
}
