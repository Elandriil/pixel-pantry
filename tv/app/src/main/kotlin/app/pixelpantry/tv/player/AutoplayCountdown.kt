package app.pixelpantry.tv.player

/**
 * The end-of-episode countdown (spec "End of episode"): when an item ends with a next episode and autoplay on, it
 * counts [seconds] down, one [tick] per second, and is [State.Finished] when the next episode should start.
 * Pure: the view model owns the timing.
 */
class AutoplayCountdown(private val seconds: Int = SECONDS) {
    sealed interface State {
        data object Idle : State
        data class Counting(val secondsLeft: Int) : State
        data object Finished : State
    }

    var state: State = State.Idle
        private set

    /** The item ended: counts down when there is a next episode and autoplay is on; otherwise [State.Idle]. */
    fun onEnded(hasNext: Boolean, autoplayEnabled: Boolean): State {
        state = if (hasNext && autoplayEnabled) State.Counting(seconds) else State.Idle
        return state
    }

    /** One second passed; after the last one, [State.Finished]. Idle and Finished stay as they are. */
    fun tick(): State {
        val s = state
        if (s is State.Counting) state = if (s.secondsLeft <= 1) State.Finished else State.Counting(s.secondsLeft - 1)
        return state
    }

    /** BACK: no autoplay for this item; later ticks stay [State.Idle]. */
    fun cancel(): State {
        state = State.Idle
        return state
    }

    /** A new item started. */
    fun reset() {
        state = State.Idle
    }

    companion object {
        const val SECONDS = 10
    }
}
