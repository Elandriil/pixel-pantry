package app.pixelpantry.tv.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToasterTest {
    @Test fun showsThenClearsAfterDuration() = runTest {
        val toaster = Toaster(backgroundScope)
        toaster.show("Hello", durationMs = 3000)
        assertEquals("Hello", toaster.current.value)
        advanceTimeBy(2999); runCurrent()
        assertEquals("Hello", toaster.current.value)
        advanceTimeBy(2); runCurrent()
        assertNull(toaster.current.value)
    }

    @Test fun newToastReplacesAndRestartsTimer() = runTest {
        val toaster = Toaster(backgroundScope)
        toaster.show("First", durationMs = 3000)
        advanceTimeBy(2000); runCurrent()
        toaster.show("Second", durationMs = 3000)
        advanceTimeBy(2000); runCurrent()
        assertEquals("Second", toaster.current.value)
        advanceTimeBy(1001); runCurrent()
        assertNull(toaster.current.value)
    }
}
