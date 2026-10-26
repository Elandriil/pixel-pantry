package app.pixelpantry.tv.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.ui.components.FocusableButton
import app.pixelpantry.tv.ui.components.Spinner
import app.pixelpantry.tv.ui.components.TvTextField
import app.pixelpantry.tv.ui.connect.ConnectViewModel.ConnectError
import app.pixelpantry.tv.ui.connect.ConnectViewModel.UiState

@Composable
fun ConnectScreen(vm: ConnectViewModel, onConnected: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 900.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            when (val s = state) {
                is UiState.Resolving -> Searching(s.triedSoFar.lastOrNull())
                is UiState.Ready -> {
                    Searching(trying = null)
                    LaunchedEffect(s) { onConnected() }
                }
                is UiState.Failed -> NotFound(
                    state = s,
                    onDraftChange = vm::onDraftChange,
                    onConnect = vm::submitManual,
                    onRetry = vm::retry,
                )
            }
        }
    }
}

@Composable
private fun Searching(trying: String?) {
    Spinner()
    Text(
        stringResource(R.string.connect_looking),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    trying?.let {
        Text(
            stringResource(R.string.connect_trying, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotFound(
    state: UiState.Failed,
    onDraftChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
) {
    val fieldFocus = remember { FocusRequester() }
    // Local field state keeps typing responsive; every change is pushed to the ViewModel, which submits its own copy.
    var text by remember { mutableStateOf(state.manualDraft) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        stringResource(if (state.changingServer) R.string.connect_change_server else R.string.connect_not_found),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.tried.isEmpty()) {
            Text(stringResource(R.string.connect_tried_none), style = MaterialTheme.typography.bodyLarge, color = secondary)
        } else {
            Text(stringResource(R.string.connect_tried), style = MaterialTheme.typography.bodyLarge, color = secondary)
            state.tried.forEach {
                Text(stringResource(R.string.connect_tried_item, it), style = MaterialTheme.typography.bodyLarge, color = secondary)
            }
        }
    }
    TvTextField(
        value = text,
        onValueChange = {
            text = it
            onDraftChange(it)
        },
        onSubmit = onConnect,
        label = stringResource(R.string.connect_address_label),
        placeholder = stringResource(R.string.connect_address_hint),
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.focusRequester(fieldFocus),
    )
    state.error?.let { error ->
        Text(
            text = when (error) {
                ConnectError.InvalidAddress -> stringResource(R.string.connect_error_invalid)
                is ConnectError.Unreachable -> stringResource(R.string.connect_error_unreachable, error.address)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FocusableButton(stringResource(R.string.connect_connect), onConnect)
        FocusableButton(stringResource(R.string.connect_retry), onRetry)
    }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
}
