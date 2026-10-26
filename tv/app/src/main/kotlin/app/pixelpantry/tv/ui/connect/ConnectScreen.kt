package app.pixelpantry.tv.ui.connect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.ui.components.FocusableButton
import app.pixelpantry.tv.ui.components.Spinner
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
                    onConnect = { vm.submitManual(s.manualDraft) },
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
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        stringResource(R.string.connect_not_found),
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
    AddressField(
        value = state.manualDraft,
        onValueChange = onDraftChange,
        onSubmit = onConnect,
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

/**
 * A text field that behaves on a remote: D-pad focus only highlights it; OK starts editing and opens the
 * on-screen keyboard; the keyboard's Go action submits.
 */
@Composable
private fun AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    val textFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)

    Surface(
        onClick = { editing = true },
        // The surface itself loses focus to the inner text field while editing; keep the outline visible.
        modifier = modifier.focusRequester(surfaceFocus).width(560.dp).then(if (editing) Modifier.border(2.dp, colors.primary, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            focusedContainerColor = colors.surface,
            focusedContentColor = colors.onSurface,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, colors.primary), shape = shape),
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.connect_address_label), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    keyboard?.hide()
                    onSubmit()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusRequester(textFocus)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused) editing = false }
                    .onPreviewKeyEvent { event ->
                        editingKey(event) { direction ->
                            editing = false
                            if (direction == null || !focusManager.moveFocus(direction)) surfaceFocus.requestFocus()
                        }
                    },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(stringResource(R.string.connect_address_hint), style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { textFocus.requestFocus() }
            keyboard?.show()
        }
    }
}

/**
 * While editing, UP/DOWN leave the field in that direction and BACK (once the keyboard is gone) returns to the
 * highlighted field instead of leaving the screen; [leave] gets the direction, or null for BACK.
 */
private fun editingKey(event: KeyEvent, leave: (FocusDirection?) -> Unit): Boolean {
    val direction = when (event.key) {
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionUp -> FocusDirection.Up
        Key.Back -> null
        else -> return false
    }
    if (event.type == KeyEventType.KeyUp) leave(direction)
    return true
}
