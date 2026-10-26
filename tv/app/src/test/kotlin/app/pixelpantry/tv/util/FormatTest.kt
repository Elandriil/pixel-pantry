package app.pixelpantry.tv.util

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun formatRuntime() {
        assertEquals("52 min", formatRuntime(52 * 60))
        assertEquals("1 h 35 min", formatRuntime(95 * 60))
    }

    @Test
    fun formatRemaining() {
        assertEquals("23 min left", formatRemaining(positionSec = 600.0, durationSec = 600.0 + 23 * 60))
        assertEquals("< 1 min left", formatRemaining(positionSec = 3000.0, durationSec = 3030.0))
        assertEquals("< 1 min left", formatRemaining(positionSec = 3100.0, durationSec = 3000.0))
    }

    @Test
    fun formatEndsAt() {
        assertEquals("Ends at 22:41", formatEndsAt(remainingSec = 23 * 60.0, now = LocalTime.of(22, 18)))
        assertEquals("Ends at 00:10", formatEndsAt(remainingSec = 40 * 60.0, now = LocalTime.of(23, 30)))
        assertEquals("Ends at 09:05", formatEndsAt(remainingSec = 5 * 60.0, now = LocalTime.of(9, 0)))
    }

    @Test
    fun formatEpisodeTag() {
        assertEquals("S2 · E4", formatEpisodeTag(season = 2, episode = 4, episodeEnd = null))
        assertEquals("S3 · E1–2", formatEpisodeTag(season = 3, episode = 1, episodeEnd = 2))
        assertEquals("", formatEpisodeTag(season = null, episode = null, episodeEnd = null))
    }

    @Test
    fun formatClock() {
        assertEquals("32:04", formatClock(32 * 60 + 4.0))
        assertEquals("1:02:04", formatClock(3600 + 2 * 60 + 4.0))
        assertEquals("0:00", formatClock(0.0))
        assertEquals("0:09", formatClock(9.7))
    }

    @Test fun watched() {
        assertEquals("32 min watched", formatWatched(1925.0))
        assertEquals("1 min watched", formatWatched(60.0))
        assertEquals("< 1 min watched", formatWatched(59.9))
    }

    @Test fun hours() {
        assertEquals("0.4", formatHours(0.3611))
        assertEquals("2.5", formatHours(2.5))
        assertEquals("0.0", formatHours(-1.0))
    }
}
