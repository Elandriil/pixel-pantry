package app.pixelpantry.tv.player

/** The remote keys the player knows; everything else is [OTHER]. */
enum class KeyCode {
    DPAD_CENTER, ENTER, MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE,
    DPAD_LEFT, DPAD_RIGHT, DPAD_UP, DPAD_DOWN,
    CHANNEL_UP, CHANNEL_DOWN, MEDIA_NEXT, MEDIA_PREVIOUS,
    BACK, MENU, OTHER,
}

/** A key event without Android: [repeatCount] > 0 while the key is held. */
data class KeyEventLike(val code: KeyCode, val isDown: Boolean, val repeatCount: Int = 0)

/** What a key asks the player to do. */
sealed class PlayerAction {
    data object TogglePause : PlayerAction()

    /** The remote's own ▶ key: resumes, or only shows the overlay when already playing. */
    data object Play : PlayerAction()

    /** The remote's own ‖ key: pauses, or only shows the overlay when already paused. */
    data object Pause : PlayerAction()
    data object SkipBack : PlayerAction()
    data object SkipForward : PlayerAction()
    data object ShowOverlay : PlayerAction()
    data object Next : PlayerAction()
    data object Prev : PlayerAction()
    data object HideOverlay : PlayerAction()
    data object StopAndExit : PlayerAction()
    data object CancelCountdown : PlayerAction()

    /** BACK while the subtitle or audio menu is open. */
    data object CloseMenu : PlayerAction()

    /** Leave the key to Compose focus (move between overlay buttons, click the focused one). */
    data object PassThrough : PlayerAction()
}

/**
 * The player's remote mapping (spec "Remote mapping in the player"). Pure: the screen turns Compose key events
 * into [KeyEventLike] and dispatches the result.
 */
object PlayerKeys {
    /**
     * [focusOnOverlay]: a button of the visible overlay has focus (false: the seek bar has it, or the overlay is
     * hidden). [menuOpen]: the subtitle or audio menu is up; it has focus, so every key but BACK (which closes
     * it) goes to Compose focus. Only key-down acts; a held key repeats only skips and focus moves. Null: nothing
     * to do.
     */
    fun map(
        key: KeyEventLike,
        overlayVisible: Boolean,
        focusOnOverlay: Boolean,
        countdownActive: Boolean,
        menuOpen: Boolean = false,
    ): PlayerAction? {
        if (!key.isDown) return null
        val action = when {
            key.code == KeyCode.OTHER -> null
            menuOpen -> if (key.code == KeyCode.BACK) PlayerAction.CloseMenu else PlayerAction.PassThrough
            else -> mapPlaying(key.code, overlayVisible, focusOnOverlay, countdownActive)
        }
        if (key.repeatCount > 0 && action !in REPEATABLE) return null
        // A held OK passes nothing through: its first press may have just focused a button, which must not click.
        if (key.repeatCount > 0 && (key.code == KeyCode.DPAD_CENTER || key.code == KeyCode.ENTER)) return null
        return action
    }

    private fun mapPlaying(code: KeyCode, overlayVisible: Boolean, focusOnOverlay: Boolean, countdownActive: Boolean): PlayerAction? =
        when (code) {
            KeyCode.CHANNEL_UP, KeyCode.MEDIA_NEXT -> PlayerAction.Next
            KeyCode.CHANNEL_DOWN, KeyCode.MEDIA_PREVIOUS -> PlayerAction.Prev
            KeyCode.MEDIA_PLAY_PAUSE -> PlayerAction.TogglePause
            KeyCode.MEDIA_PLAY -> PlayerAction.Play
            KeyCode.MEDIA_PAUSE -> PlayerAction.Pause
            KeyCode.BACK -> when {
                countdownActive -> PlayerAction.CancelCountdown
                overlayVisible -> PlayerAction.HideOverlay
                else -> PlayerAction.StopAndExit
            }
            KeyCode.OTHER -> null
            else -> when {
                !overlayVisible -> hiddenOverlay(code)
                focusOnOverlay -> PlayerAction.PassThrough
                else -> onSeekBar(code)
            }
        }

    /**
     * Whether the player keeps this event from Compose. Everything is kept except what [map] passes through and
     * keys the player does not know; a key-up follows its key-down, so the OK that a button received also
     * reaches it on release (which is when it clicks).
     */
    fun consumes(
        key: KeyEventLike,
        overlayVisible: Boolean,
        focusOnOverlay: Boolean,
        countdownActive: Boolean,
        menuOpen: Boolean = false,
    ): Boolean {
        if (key.code == KeyCode.OTHER) return false
        val asDown = if (key.isDown) key else key.copy(isDown = true, repeatCount = 0)
        return map(asDown, overlayVisible, focusOnOverlay, countdownActive, menuOpen) != PlayerAction.PassThrough
    }

    private fun hiddenOverlay(code: KeyCode): PlayerAction? = when (code) {
        KeyCode.DPAD_LEFT -> PlayerAction.SkipBack
        KeyCode.DPAD_RIGHT -> PlayerAction.SkipForward
        KeyCode.DPAD_UP, KeyCode.DPAD_DOWN, KeyCode.MENU -> PlayerAction.ShowOverlay
        KeyCode.DPAD_CENTER, KeyCode.ENTER -> PlayerAction.TogglePause
        else -> null
    }

    private fun onSeekBar(code: KeyCode): PlayerAction? = when (code) {
        KeyCode.DPAD_LEFT -> PlayerAction.SkipBack
        KeyCode.DPAD_RIGHT -> PlayerAction.SkipForward
        KeyCode.DPAD_CENTER, KeyCode.ENTER -> PlayerAction.TogglePause
        else -> PlayerAction.PassThrough // UP/DOWN/MENU move focus
    }

    private val REPEATABLE = setOf(PlayerAction.SkipBack, PlayerAction.SkipForward, PlayerAction.PassThrough)
}
