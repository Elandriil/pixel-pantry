package app.pixelpantry.tv.player

import app.pixelpantry.tv.player.AutoplayCountdown.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplayCountdownTest {
    @Test fun tenTicksFinish() {
        val c = AutoplayCountdown()
        assertEquals(State.Counting(10), c.onEnded(hasNext = true, autoplayEnabled = true))
        for (left in 9 downTo 1) assertEquals(State.Counting(left), c.tick())
        assertEquals(State.Finished, c.tick())
        assertEquals("finished stays finished", State.Finished, c.tick())
    }

    @Test fun cancelAtThreeIsIdleAndFurtherTicksStayIdle() {
        val c = AutoplayCountdown()
        c.onEnded(hasNext = true, autoplayEnabled = true)
        repeat(7) { c.tick() }
        assertEquals(State.Counting(3), c.state)
        assertEquals(State.Idle, c.cancel())
        repeat(20) { assertEquals(State.Idle, c.tick()) }
    }

    @Test fun noNextEpisodeIsIdle() {
        val c = AutoplayCountdown()
        assertEquals(State.Idle, c.onEnded(hasNext = false, autoplayEnabled = true))
        assertEquals(State.Idle, c.tick())
    }

    @Test fun autoplayOffIsIdle() {
        val c = AutoplayCountdown()
        assertEquals(State.Idle, c.onEnded(hasNext = true, autoplayEnabled = false))
        assertEquals(State.Idle, c.tick())
    }

    @Test fun resetReturnsToIdle() {
        val c = AutoplayCountdown()
        c.onEnded(hasNext = true, autoplayEnabled = true)
        c.reset()
        assertEquals(State.Idle, c.state)
        assertEquals(State.Idle, c.tick())
    }
}
