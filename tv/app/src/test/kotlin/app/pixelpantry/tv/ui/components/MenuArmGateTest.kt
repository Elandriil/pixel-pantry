package app.pixelpantry.tv.ui.components

import app.pixelpantry.tv.player.KeyCode
import app.pixelpantry.tv.player.KeyEventLike
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuArmGateTest {
    private fun down(code: KeyCode = KeyCode.DPAD_CENTER, repeat: Int = 0) = KeyEventLike(code, isDown = true, repeatCount = repeat)
    private fun up(code: KeyCode = KeyCode.DPAD_CENTER) = KeyEventLike(code, isDown = false)

    @Test fun upBeforeDownIsSwallowed() {
        val gate = MenuArmGate()
        assertFalse(gate.accept(up()))
        assertFalse(gate.accept(up(KeyCode.ENTER)))
    }

    @Test fun freshDownThenUpIsAccepted() {
        val gate = MenuArmGate()
        assertTrue(gate.accept(down()))
        assertTrue(gate.accept(up()))
    }

    @Test fun repeatDownsAreSwallowedUntilAFreshDown() {
        val gate = MenuArmGate()
        assertFalse(gate.accept(down(repeat = 1)))
        assertFalse(gate.accept(down(repeat = 2)))
        assertFalse(gate.accept(up()))
        assertTrue(gate.accept(down()))
        assertTrue(gate.accept(down(repeat = 1)))
        assertTrue(gate.accept(up()))
    }

    @Test fun anyOtherKeyArmsTheGate() {
        val gate = MenuArmGate()
        assertTrue(gate.accept(down(KeyCode.DPAD_DOWN)))
        assertTrue(gate.accept(up(KeyCode.DPAD_DOWN)))
        assertTrue(gate.accept(up()))
    }
}
