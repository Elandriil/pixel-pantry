package app.pixelpantry.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.pixelpantry.tv.player.KeyCode
import app.pixelpantry.tv.player.KeyEventLike

/**
 * Keeps a dialog opened by a long-press OK from acting on that same press. tv-material fires `onLongClick` on the
 * first key repeat and the dialog's window takes focus at once, so the held OK's remaining repeats and its key-up
 * arrive in the dialog, where the key-up would click the focused row. Until the dialog has seen a fresh press
 * (an OK key-down with `repeatCount == 0`, or any other key), every OK event is swallowed.
 */
class MenuArmGate {
    private var armed = false

    /** True when [event] may reach the dialog; false when it must be consumed. */
    fun accept(event: KeyEventLike): Boolean {
        if (armed) return true
        val ok = event.code == KeyCode.DPAD_CENTER || event.code == KeyCode.ENTER
        if (!ok) {
            armed = true
            return true
        }
        if (event.isDown && event.repeatCount == 0) {
            armed = true
            return true
        }
        return false
    }
}

/** Applies a [MenuArmGate] to the key events reaching this node's focused descendants. */
fun Modifier.menuArmGate(gate: MenuArmGate): Modifier = onPreviewKeyEvent { e ->
    val code = when (e.key) {
        Key.DirectionCenter -> KeyCode.DPAD_CENTER
        Key.Enter, Key.NumPadEnter -> KeyCode.ENTER
        else -> KeyCode.OTHER
    }
    // Only key-downs and key-ups count; anything else (multiple) is left alone.
    if (e.type != KeyEventType.KeyDown && e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
    !gate.accept(KeyEventLike(code, isDown = e.type == KeyEventType.KeyDown, repeatCount = e.nativeKeyEvent.repeatCount))
}
