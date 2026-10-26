package app.pixelpantry.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoCueWatchTest {
    @Test fun rangeOfAnSrtFileSpansItsFirstAndLastTimestamp() {
        val srt = "1\n00:00:01,500 --> 00:00:04,000\nHi\n\n2\n01:02:03,250 --> 01:02:05,000\nBye\n"
        assertEquals(1_500L..3_725_000L, NoCueWatch.rangeOf(srt))
    }

    @Test fun rangeOfVttAndAssTimestamps() {
        assertEquals(2_000L..65_500L, NoCueWatch.rangeOf("WEBVTT\n\n00:02.000 --> 01:05.500\nHi\n"))
        assertEquals(1_000L..4_500L, NoCueWatch.rangeOf("Dialogue: 0,0:00:01.00,0:00:04.50,Default,,0,0,0,,Hi"))
    }

    @Test fun rangeOfAFileWithoutTimestampsIsNull() = assertNull(NoCueWatch.rangeOf("<html>not subtitles</html>"))

    @Test fun reportsOnceAfterTheThresholdOfPlaybackInsideTheRange() {
        val watch = NoCueWatch(0L..60_000L, thresholdMs = 2_000)
        assertFalse(watch.onTick(10_000, playing = true, elapsedMs = 1_000))
        assertFalse("paused time does not count", watch.onTick(10_000, playing = false, elapsedMs = 5_000))
        assertTrue(watch.onTick(11_000, playing = true, elapsedMs = 1_000))
        assertFalse("once", watch.onTick(12_000, playing = true, elapsedMs = 5_000))
    }

    @Test fun playbackOutsideTheRangeDoesNotCount() {
        val watch = NoCueWatch(0L..60_000L, thresholdMs = 2_000)
        repeat(10) { assertFalse(watch.onTick(120_000, playing = true, elapsedMs = 1_000)) }
    }

    @Test fun aCueStopsTheWatch() {
        val watch = NoCueWatch(null, thresholdMs = 2_000)
        assertFalse(watch.onTick(0, playing = true, elapsedMs = 1_000))
        watch.onCue()
        repeat(5) { assertFalse(watch.onTick(0, playing = true, elapsedMs = 1_000)) }
    }
}
