package app.pixelpantry.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val PantryColorScheme = darkColorScheme(
    primary = PantryAccent,
    onPrimary = PantryOnAccent,
    secondary = PantryAccent,
    onSecondary = PantryOnAccent,
    background = PantryBackground,
    onBackground = PantryText,
    surface = PantrySurface,
    onSurface = PantryText,
    surfaceVariant = PantrySurface,
    onSurfaceVariant = PantryTextSecondary,
)

@Composable
fun PantryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PantryColorScheme,
        typography = PantryTypography,
        content = content,
    )
}
