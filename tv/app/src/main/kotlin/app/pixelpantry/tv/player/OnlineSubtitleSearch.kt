package app.pixelpantry.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.OsResult
import app.pixelpantry.tv.player.PlayerViewModel.OnlineState

/**
 * The subtitle menu's OpenSubtitles results: `release · ↓downloads · ✓ hash match` in the server's order. OK
 * downloads and applies one; the list stays so another pick is one click away. Errors (e.g. `OpenSubtitles not
 * configured`) show inline.
 */
@Composable
fun OnlineSubtitleSearch(
    online: OnlineState,
    onDownload: (Long) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val focusFirst = Modifier.focusRequester(firstFocus)
    PlayerMenuPanel(stringResource(R.string.player_menu_online_title), modifier) {
        when (online) {
            OnlineState.Idle, OnlineState.Searching ->
                item("status") { PlayerMenuStatus(stringResource(R.string.player_menu_searching), focusFirst) }
            is OnlineState.Error -> item("status") {
                PlayerMenuStatus(online.message, focusFirst, color = MaterialTheme.colorScheme.error)
            }
            is OnlineState.Results -> {
                val status = when {
                    online.downloading -> R.string.player_menu_downloading
                    online.applied != null -> R.string.player_menu_applied
                    online.results.isEmpty() -> R.string.player_menu_no_results
                    else -> null
                }
                if (status != null) {
                    item("status") {
                        // Only without results does the status line hold focus.
                        PlayerMenuStatus(
                            stringResource(status, online.applied.orEmpty()),
                            if (online.results.isEmpty()) focusFirst else Modifier,
                        )
                    }
                }
                online.results.forEachIndexed { i, r ->
                    item(r.fileId) {
                        PlayerMenuRow(
                            label = r.release,
                            detail = resultDetail(r),
                            onClick = { onDownload(r.fileId) },
                            modifier = if (i == 0) focusFirst else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun resultDetail(r: OsResult): String = listOfNotNull(
    r.language.uppercase(),
    stringResource(R.string.player_menu_downloads, r.downloads),
    if (r.hashMatch) stringResource(R.string.player_menu_hash_match) else null,
).joinToString(" · ")
