package app.pixelpantry.tv.library

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.ScanStatus
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The one place a library rescan runs (app-wide, so it survives page changes): POST `/api/scan`, toast, poll
 * `/api/scan/status` every [pollIntervalMs] until idle, then signal [scanFinished] and toast the outcome.
 * A rescan requested while one is running is not started twice.
 */
class LibraryScanner(
    private val api: ApiClient,
    private val toaster: Toaster,
    private val scope: CoroutineScope,
    private val strings: Strings,
    private val pollIntervalMs: Long = 2_000,
) {
    /** Toast texts; supplied from string resources. */
    class Strings(val scanning: String, val updated: String, val scanFailed: (error: String) -> String)

    private val _scanFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once per finished scan (also one that reported an error: it may still have changed the library). */
    val scanFinished: SharedFlow<Unit> = _scanFinished.asSharedFlow()

    private var job: Job? = null

    @Synchronized
    fun rescan() {
        if (job?.isActive == true) {
            toaster.show(strings.scanning)
            return
        }
        job = scope.launch {
            val finished: ScanStatus = try {
                api.scan()
                toaster.show(strings.scanning)
                var status: ScanStatus
                do {
                    delay(pollIntervalMs)
                    status = api.scanStatus()
                } while (status.state != "idle")
                status
            } catch (e: ApiException) {
                toaster.show(e.message.orEmpty())
                return@launch
            }
            _scanFinished.emit(Unit)
            toaster.show(finished.lastError?.let(strings.scanFailed) ?: strings.updated)
        }
    }
}
