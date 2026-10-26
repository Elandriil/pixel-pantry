package app.pixelpantry.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardScale
import androidx.tv.material3.CardShape
import androidx.tv.material3.MaterialTheme

/** Shared look of browse cards: rounded corners, a 3 dp white ring and 1.05 scale when focused. */
internal object CardStyle {
    private val corner = RoundedCornerShape(8.dp)

    val shape: CardShape @Composable get() = CardDefaults.shape(corner)
    val scale: CardScale @Composable get() = CardDefaults.scale(focusedScale = 1.05f)
    val border: CardBorder
        @Composable get() = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = corner))
    val colors @Composable get() = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
}
