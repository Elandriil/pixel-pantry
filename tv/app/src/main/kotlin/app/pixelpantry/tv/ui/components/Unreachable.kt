package app.pixelpantry.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.ApiException

/** The server could not be reached at all (no HTTP answer), as opposed to an error the server reported. */
fun ApiException.isUnreachable(): Boolean = status == 0

/**
 * What a page shows when its first load could not reach the server: the address tried, Retry (resolves the
 * server once more, then reloads) and Change server (the connect screen, prefilled with that address).
 */
@Composable
fun UnreachableScreen(baseUrl: String, onRetry: () -> Unit, onChangeServer: () -> Unit) {
    ErrorScreen(
        title = stringResource(R.string.unreachable_title),
        detail = stringResource(R.string.unreachable_detail, baseUrl),
        primary = ButtonSpec(stringResource(R.string.unreachable_retry), onRetry),
        secondary = ButtonSpec(stringResource(R.string.unreachable_change_server), onChangeServer),
    )
}
