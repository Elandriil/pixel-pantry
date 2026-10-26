package app.pixelpantry.tv.player

import app.pixelpantry.tv.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reports playback progress for one item at a time (server contract: `server/docs/tv-client-notes.md`).
 * [tick] sends at most every [intervalMs]; [flush] always sends (pause, stop, switching items). The first report
 * of a playback carries no `sessionId`; later ones resend the id the server returned. Never throws: a failed
 * report is passed to [onError] and dropped. Reports are serialised so a flush always sees the id of the tick
 * before it.
 */
class ProgressReporter(
    private val api: ApiClient,
    private val clock: () -> Long,
    private val intervalMs: Long = 30_000,
    private val onError: (String) -> Unit = {},
) {
    enum class Reason { PAUSE, STOP, SWITCH }

    private val mutex = Mutex()

    @Volatile private var itemId: Int? = null
    @Volatile private var sessionId: Int? = null
    @Volatile private var lastSentAt: Long? = null

    /** Bumped by [start], so a reply for an earlier playback cannot leak its session id into this one. */
    @Volatile private var generation = 0

    /** Begins a new playback of [itemId]: the next report opens a new session. */
    fun start(itemId: Int) {
        generation++
        this.itemId = itemId
        sessionId = null
        lastSentAt = null
    }

    /** Reports if at least [intervalMs] passed since the last report (or none was sent yet). */
    suspend fun tick(positionSec: Double, durationSec: Double) = mutex.withLock {
        val last = lastSentAt
        if (last != null && clock() - last < intervalMs) return@withLock
        send(positionSec, durationSec, "tick")
    }

    /** Reports now. [reason] only labels the log line; the server contract is the same for all of them. */
    suspend fun flush(positionSec: Double, durationSec: Double, reason: Reason) = mutex.withLock {
        send(positionSec, durationSec, reason.name)
    }

    private suspend fun send(positionSec: Double, durationSec: Double, label: String) {
        val id = itemId ?: return
        val gen = generation
        lastSentAt = clock()
        try {
            val response = api.progress(id, positionSec, durationSec, sessionId)
            if (generation == gen) sessionId = response.sessionId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError("Progress report ($label) for item $id failed: ${e.message}")
        }
    }
}
