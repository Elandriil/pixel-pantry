package app.pixelpantry.tv.player

/**
 * Notices a subtitle file the player shows nothing from (a file it could not parse fails silently): after
 * [thresholdMs] of playback inside the file's time range without a single cue, [onTick] answers true, once.
 * [rangeMs] is the span the file's timestamps cover ([rangeOf]); null when it has none, so all of the item counts.
 * Pure: the [PlayerViewModel] feeds it ticks and cue events.
 */
class NoCueWatch(private val rangeMs: LongRange?, private val thresholdMs: Long = THRESHOLD_MS) {
    private var playedMs = 0L
    private var cueSeen = false
    private var reported = false

    /** The player showed a (non-empty) cue from the file. */
    fun onCue() {
        cueSeen = true
    }

    /**
     * [elapsedMs] passed while [playing] at [positionMs] (the file's own time, i.e. without a sub delay). True when
     * the "no subtitles" notice should show now.
     */
    fun onTick(positionMs: Long, playing: Boolean, elapsedMs: Long): Boolean {
        if (cueSeen || reported || !playing) return false
        if (rangeMs != null && positionMs !in rangeMs) return false
        playedMs += elapsedMs
        if (playedMs < thresholdMs) return false
        reported = true
        return true
    }

    companion object {
        const val THRESHOLD_MS = 20_000L

        private val TIMESTAMP = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{2,3})""")

        /**
         * The span from the first to the last timestamp in a subtitle file's [text] (SRT / VTT `00:01:02,500`,
         * ASS `0:01:02.50`); null when it has none.
         */
        fun rangeOf(text: String): LongRange? {
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            TIMESTAMP.findAll(text).forEach { m ->
                val (h, mm, ss, frac) = m.destructured
                val fracMs = if (frac.length == 2) frac.toLong() * 10 else frac.toLong()
                val ms = ((h.ifEmpty { "0" }.toLong() * 60 + mm.toLong()) * 60 + ss.toLong()) * 1000 + fracMs
                if (ms < min) min = ms
                if (ms > max) max = ms
            }
            return if (min == Long.MAX_VALUE) null else min..max
        }
    }
}
