package app.pixelpantry.tv.player

/**
 * Decides whether a failing media load is retried or given up (pure; [ExoPlayerPort] feeds it).
 *
 * A "run" is a stretch of consecutive failures. It starts at the first error, again whenever data arrived since
 * the previous error ([bytesLoaded] grew), again when [bytesLoaded] fell (a seek started a new loadable, whose
 * count starts from zero), and again after a quiet gap of [newRunGapMs] — so a second outage
 * minutes later gets its own full budget. Within a run errors are retried (after 1 s, then every 2 s) until the
 * run has lasted [giveUpAfterMs]. Media3's own error count cannot be used for this: for a continuous
 * progressive load it is reset only after the policy has been asked.
 */
class LoadErrorBudget(
    private val giveUpAfterMs: Long = 8_000,
    private val newRunGapMs: Long = 15_000,
) {
    sealed interface Decision {
        data class Retry(val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    private var failingSinceMs = 0L
    private var lastErrorAtMs: Long? = null
    private var lastBytesLoaded = 0L
    private var errorsInRun = 0

    @Synchronized
    fun onError(bytesLoaded: Long, now: Long): Decision {
        val last = lastErrorAtMs
        if (last == null || bytesLoaded != lastBytesLoaded || now - last > newRunGapMs) {
            failingSinceMs = now
            errorsInRun = 0
        }
        errorsInRun++
        lastErrorAtMs = now
        lastBytesLoaded = bytesLoaded
        if (now - failingSinceMs >= giveUpAfterMs) return Decision.GiveUp
        return Decision.Retry(if (errorsInRun == 1) 1_000 else 2_000)
    }

    /** Forgets the current run (a new item is being prepared). */
    @Synchronized
    fun reset() {
        lastErrorAtMs = null
        lastBytesLoaded = 0L
        errorsInRun = 0
    }
}
