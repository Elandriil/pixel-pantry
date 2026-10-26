package app.pixelpantry.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/** A remote-friendly text button; pass [focusRequester] to focus it programmatically. */
@Composable
fun FocusableButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = if (focusRequester != null) modifier.focusRequester(focusRequester) else modifier,
    ) {
        Text(label)
    }
}
