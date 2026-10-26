package app.pixelpantry.tv.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.MaterialTheme
import app.pixelpantry.tv.ui.components.Sidebar
import app.pixelpantry.tv.ui.components.SidebarCollapsedWidth
import app.pixelpantry.tv.ui.components.SidebarEntry

/**
 * A browse screen: the collapsed sidebar on the left, [content] beside it. The sidebar overlays the content when
 * it expands. Content gets focus when the screen opens and again after any sidebar entry is chosen; focus
 * coming back from the sidebar returns to the last focused content element. Content may take focus itself on
 * open; the shell then leaves it where it is. With [reselect] (detail pages, which sit under a sidebar entry but
 * are not its page) choosing the selected entry navigates too instead of only returning focus to the content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppShell(
    selected: SidebarEntry,
    onSelect: (SidebarEntry) -> Unit,
    onRescan: () -> Unit,
    reselect: Boolean = false,
    content: @Composable () -> Unit,
) {
    val contentFocus = remember { FocusRequester() }
    // Plain holder, not state: read only from the effect below, never during composition.
    val contentHasFocus = remember { booleanArrayOf(false) }
    fun focusContent() = runCatching { contentFocus.requestFocus() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = SidebarCollapsedWidth)
                .onFocusChanged { contentHasFocus[0] = it.hasFocus }
                .focusRequester(contentFocus)
                .focusRestorer()
                // Only LEFT may leave the content (into the sidebar); UP/DOWN/RIGHT at the edges stay put.
                .focusProperties {
                    exit = { direction -> if (direction == FocusDirection.Left) FocusRequester.Default else FocusRequester.Cancel }
                }
                .focusGroup(),
        ) {
            content()
        }
        Sidebar(
            selected = selected,
            onSelect = { entry -> if (entry == selected && !reselect) focusContent() else onSelect(entry) },
            onRescan = { onRescan(); focusContent() },
        )
    }
    // Content that focuses a specific element itself when it opens (e.g. restoring the last focused card) wins.
    LaunchedEffect(Unit) { if (!contentHasFocus[0]) focusContent() }
}
