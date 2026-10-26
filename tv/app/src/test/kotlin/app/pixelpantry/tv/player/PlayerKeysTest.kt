package app.pixelpantry.tv.player

import app.pixelpantry.tv.player.KeyCode.BACK
import app.pixelpantry.tv.player.KeyCode.CHANNEL_DOWN
import app.pixelpantry.tv.player.KeyCode.CHANNEL_UP
import app.pixelpantry.tv.player.KeyCode.DPAD_CENTER
import app.pixelpantry.tv.player.KeyCode.DPAD_DOWN
import app.pixelpantry.tv.player.KeyCode.DPAD_LEFT
import app.pixelpantry.tv.player.KeyCode.DPAD_RIGHT
import app.pixelpantry.tv.player.KeyCode.DPAD_UP
import app.pixelpantry.tv.player.KeyCode.ENTER
import app.pixelpantry.tv.player.KeyCode.MEDIA_NEXT
import app.pixelpantry.tv.player.KeyCode.MEDIA_PAUSE
import app.pixelpantry.tv.player.KeyCode.MEDIA_PLAY
import app.pixelpantry.tv.player.KeyCode.MEDIA_PLAY_PAUSE
import app.pixelpantry.tv.player.KeyCode.MEDIA_PREVIOUS
import app.pixelpantry.tv.player.KeyCode.OTHER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerKeysTest {
    private fun down(code: KeyCode, repeat: Int = 0) = KeyEventLike(code, isDown = true, repeatCount = repeat)
    private fun up(code: KeyCode) = KeyEventLike(code, isDown = false, repeatCount = 0)

    /** Overlay hidden. */
    private fun hidden(key: KeyEventLike, countdown: Boolean = false) =
        PlayerKeys.map(key, overlayVisible = false, focusOnOverlay = false, countdownActive = countdown)

    /** Overlay shown, the seek bar focused. */
    private fun onSeekBar(key: KeyEventLike) =
        PlayerKeys.map(key, overlayVisible = true, focusOnOverlay = false, countdownActive = false)

    /** Overlay shown, a button focused. */
    private fun onButton(key: KeyEventLike, countdown: Boolean = false) =
        PlayerKeys.map(key, overlayVisible = true, focusOnOverlay = true, countdownActive = countdown)

    // Overlay hidden

    @Test fun hiddenLeftSkipsBack() = assertEquals(PlayerAction.SkipBack, hidden(down(DPAD_LEFT)))

    @Test fun hiddenRightSkipsForward() = assertEquals(PlayerAction.SkipForward, hidden(down(DPAD_RIGHT)))

    @Test fun hiddenUpAndDownShowTheOverlay() {
        assertEquals(PlayerAction.ShowOverlay, hidden(down(DPAD_UP)))
        assertEquals(PlayerAction.ShowOverlay, hidden(down(DPAD_DOWN)))
    }

    @Test fun hiddenOkEnterAndPlayPauseTogglePause() {
        for (code in listOf(DPAD_CENTER, ENTER, MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE)) {
            assertEquals(code.name, PlayerAction.TogglePause, hidden(down(code)))
        }
    }

    // Overlay visible

    @Test fun buttonFocusedDpadPassesThroughToComposeFocus() {
        for (code in listOf(DPAD_LEFT, DPAD_RIGHT, DPAD_UP, DPAD_DOWN, DPAD_CENTER, ENTER)) {
            assertEquals(code.name, PlayerAction.PassThrough, onButton(down(code)))
        }
    }

    @Test fun seekBarFocusedLeftRightSkip() {
        assertEquals(PlayerAction.SkipBack, onSeekBar(down(DPAD_LEFT)))
        assertEquals(PlayerAction.SkipForward, onSeekBar(down(DPAD_RIGHT)))
    }

    @Test fun seekBarFocusedUpDownMoveFocus() {
        assertEquals(PlayerAction.PassThrough, onSeekBar(down(DPAD_DOWN)))
        assertEquals(PlayerAction.PassThrough, onSeekBar(down(DPAD_UP)))
    }

    @Test fun seekBarFocusedOkTogglesPause() = assertEquals(PlayerAction.TogglePause, onSeekBar(down(DPAD_CENTER)))

    @Test fun playPauseTogglesEvenWithAButtonFocused() =
        assertEquals(PlayerAction.TogglePause, onButton(down(MEDIA_PLAY_PAUSE)))

    // Channel / media next and previous: always

    @Test fun channelUpAndMediaNextAreNextInEveryState() {
        for (code in listOf(CHANNEL_UP, MEDIA_NEXT)) {
            assertEquals(PlayerAction.Next, hidden(down(code)))
            assertEquals(PlayerAction.Next, onSeekBar(down(code)))
            assertEquals(PlayerAction.Next, onButton(down(code)))
        }
    }

    @Test fun channelDownAndMediaPreviousArePrevInEveryState() {
        for (code in listOf(CHANNEL_DOWN, MEDIA_PREVIOUS)) {
            assertEquals(PlayerAction.Prev, hidden(down(code)))
            assertEquals(PlayerAction.Prev, onSeekBar(down(code)))
            assertEquals(PlayerAction.Prev, onButton(down(code)))
        }
    }

    // BACK

    @Test fun backWithCountdownCancelsIt() {
        assertEquals(PlayerAction.CancelCountdown, hidden(down(BACK), countdown = true))
        assertEquals(PlayerAction.CancelCountdown, onButton(down(BACK), countdown = true))
    }

    @Test fun backWithOverlayVisibleHidesIt() {
        assertEquals(PlayerAction.HideOverlay, onButton(down(BACK)))
        assertEquals(PlayerAction.HideOverlay, onSeekBar(down(BACK)))
    }

    /** Review Focus 2: BACK with nothing on screen stops and leaves (the position is saved by stopAndExit). */
    @Test fun backWithOverlayHiddenStopsAndExits() = assertEquals(PlayerAction.StopAndExit, hidden(down(BACK)))

    // Key-up and repeats

    @Test fun keyUpIsIgnored() {
        for (code in KeyCode.entries) {
            assertNull(code.name, hidden(up(code)))
            assertNull(code.name, onButton(up(code)))
        }
    }

    @Test fun heldLeftAndRightKeepSkipping() {
        assertEquals(PlayerAction.SkipBack, hidden(down(DPAD_LEFT, repeat = 3)))
        assertEquals(PlayerAction.SkipForward, hidden(down(DPAD_RIGHT, repeat = 1)))
        assertEquals(PlayerAction.SkipForward, onSeekBar(down(DPAD_RIGHT, repeat = 7)))
    }

    @Test fun heldKeysOtherThanSkipsActOnce() {
        assertNull(hidden(down(DPAD_CENTER, repeat = 1)))
        assertNull(hidden(down(MEDIA_PLAY_PAUSE, repeat = 1)))
        assertNull(hidden(down(CHANNEL_UP, repeat = 1)))
        assertNull(hidden(down(BACK, repeat = 1)))
        assertNull(onButton(down(BACK, repeat = 1)))
        assertNull("a held OK must not click the button its first press focused", onButton(down(DPAD_CENTER, repeat = 1)))
    }

    @Test fun heldDpadOnAButtonStillMovesFocus() = assertEquals(PlayerAction.PassThrough, onButton(down(DPAD_RIGHT, repeat = 2)))

    @Test fun otherKeysAreNotHandled() {
        assertNull(hidden(down(OTHER)))
        assertNull(onButton(down(OTHER)))
    }

    // What Compose sees: only what passes through, plus keys the player does not know

    @Test fun consumesEverythingExceptPassThroughAndUnknownKeys() {
        fun consumes(key: KeyEventLike, visible: Boolean, onButton: Boolean) =
            PlayerKeys.consumes(key, overlayVisible = visible, focusOnOverlay = onButton, countdownActive = false)
        // A key-up reaches the focused button only where its key-down would have (so OK-up clicks it).
        assertFalse(consumes(up(DPAD_CENTER), visible = true, onButton = true))
        assertTrue(consumes(up(DPAD_CENTER), visible = false, onButton = false))
        assertTrue(consumes(up(DPAD_RIGHT), visible = true, onButton = false))
        assertTrue("BACK never reaches Compose focus", consumes(up(BACK), visible = true, onButton = true))
        assertTrue(consumes(down(BACK, repeat = 1), visible = false, onButton = false))
        // A held OK must not reach the Pause button the first press focused.
        assertTrue(consumes(down(DPAD_CENTER, repeat = 1), visible = true, onButton = true))
        assertFalse(consumes(down(DPAD_DOWN), visible = true, onButton = false))
        assertTrue(consumes(down(DPAD_LEFT), visible = false, onButton = false))
        assertFalse(consumes(down(OTHER), visible = true, onButton = true))
        assertFalse(consumes(up(OTHER), visible = false, onButton = false))
    }
}
