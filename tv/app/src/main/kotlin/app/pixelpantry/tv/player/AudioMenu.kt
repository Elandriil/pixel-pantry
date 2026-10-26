package app.pixelpantry.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.Track

/** The audio menu: the file's audio tracks labelled `language · codec · channels`, the current one ticked. */
@Composable
fun AudioMenu(
    tracks: List<Track>,
    choice: String?,
    onSelect: (String) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    PlayerMenuPanel(stringResource(R.string.player_menu_audio), modifier) {
        if (tracks.isEmpty()) {
            item { PlayerMenuStatus(stringResource(R.string.player_menu_no_audio), Modifier.focusRequester(firstFocus)) }
        }
        tracks.forEachIndexed { i, t ->
            val id = "audio:${t.index}"
            item(id) {
                PlayerMenuRow(
                    label = audioTrackLabel(t),
                    detail = t.title,
                    checked = id == choice,
                    onClick = { onSelect(id) },
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

/** `ENG · aac · 5.1`; parts the file does not name are left out. */
internal fun audioTrackLabel(t: Track): String =
    listOfNotNull(t.language?.uppercase(), t.codec, t.channels?.let(::channelLayout)).joinToString(" · ")

private fun channelLayout(n: Int): String = when (n) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "${n}ch"
}
