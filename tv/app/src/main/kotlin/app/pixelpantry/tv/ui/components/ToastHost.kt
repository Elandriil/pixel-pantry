package app.pixelpantry.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-wide transient messages; a new toast replaces the current one. */
class Toaster(private val scope: CoroutineScope) {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()
    private var clearJob: Job? = null

    @Synchronized
    fun show(text: String, durationMs: Long = 3000) {
        clearJob?.cancel()
        _current.value = text
        clearJob = scope.launch {
            delay(durationMs)
            _current.compareAndSet(text, null)
        }
    }
}

/** Renders the current toast top-right over everything else; draws nothing when there is none. */
@Composable
fun ToastHost(toaster: Toaster, modifier: Modifier = Modifier) {
    val current by toaster.current.collectAsStateWithLifecycle()
    // Keep the last text on screen while it fades out.
    val last = remember { arrayOf("") }
    current?.let { last[0] = it }
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(visible = current != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = last[0],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}
