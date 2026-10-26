package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

data class ButtonSpec(val label: String, val onClick: () -> Unit)

/** Full-screen error with a title, a plain-words detail and one or two actions; the primary action has focus. */
@Composable
fun ErrorScreen(
    title: String,
    detail: String,
    primary: ButtonSpec,
    secondary: ButtonSpec? = null,
    modifier: Modifier = Modifier,
) {
    val primaryFocus = remember { FocusRequester() }
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 900.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(
                detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusableButton(primary.label, primary.onClick, focusRequester = primaryFocus)
                secondary?.let { FocusableButton(it.label, it.onClick) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }
}
