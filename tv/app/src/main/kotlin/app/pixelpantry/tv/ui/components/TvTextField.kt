package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * A text field that behaves on a remote: D-pad focus only highlights it; OK starts editing and opens the
 * on-screen keyboard; the keyboard's action key ([imeAction]) calls [onSubmit]. While editing, UP/DOWN leave
 * the field in that direction and BACK (once the keyboard is gone) returns to the highlighted field.
 * Put a [Modifier.focusRequester] in [modifier] to focus the field programmatically.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    width: Dp = 560.dp,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Go,
) {
    var editing by remember { mutableStateOf(false) }
    val textFocus = remember { FocusRequester() }
    val surfaceFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val submit = {
        keyboard?.hide()
        onSubmit()
    }

    Surface(
        onClick = { editing = true },
        // The surface itself loses focus to the inner text field while editing; keep the outline visible.
        modifier = modifier.focusRequester(surfaceFocus).width(width).then(if (editing) Modifier.border(2.dp, colors.primary, shape) else Modifier),
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
            Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onGo = { submit() }, onSearch = { submit() }, onDone = { submit() }),
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
                            Text(placeholder, style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant)
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

/** While editing: UP/DOWN leave the field in that direction, BACK returns to the field; [leave] gets null for BACK. */
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
