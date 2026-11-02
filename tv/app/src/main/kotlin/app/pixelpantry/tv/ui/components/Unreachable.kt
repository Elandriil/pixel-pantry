package app.pixelpantry.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.ApiException

/** The server could not be reached at all (no HTTP answer), as opposed to an error the server reported. */
fun ApiException.isUnreachable(): Boolean = status == 0

/** The server no longer accepts this TV's token (its pairing was reset, or it is a different PC): pair again. */
fun ApiException.isUnauthorized(): Boolean = status == 401

/** A page cannot use the server: nothing answers, or ([pairingRequired]) it wants the TV to pair again. */
fun ApiException.blocksPage(): Boolean = isUnreachable() || isUnauthorized()

/**
 * What a page shows when its first load could not use the server: the address tried, Retry (resolves the
 * server once more, then reloads) and Change server (the connect screen, prefilled with that address, which
 * also leads to the PIN screen when the server asks for pairing).
 */
@Composable
fun UnreachableScreen(baseUrl: String, pairingRequired: Boolean = false, onRetry: () -> Unit, onChangeServer: () -> Unit) {
    ErrorScreen(
        title = stringResource(if (pairingRequired) R.string.pairing_required_title else R.string.unreachable_title),
        detail = stringResource(if (pairingRequired) R.string.pairing_required_detail else R.string.unreachable_detail, baseUrl),
        primary = ButtonSpec(stringResource(if (pairingRequired) R.string.pairing_required_pair else R.string.unreachable_retry), if (pairingRequired) onChangeServer else onRetry),
        secondary = ButtonSpec(stringResource(if (pairingRequired) R.string.unreachable_retry else R.string.unreachable_change_server), if (pairingRequired) onRetry else onChangeServer),
    )
}
