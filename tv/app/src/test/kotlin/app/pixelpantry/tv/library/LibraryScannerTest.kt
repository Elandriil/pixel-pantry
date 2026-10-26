package app.pixelpantry.tv.library

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.di.appScope
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryScannerTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val requests = ConcurrentLinkedQueue<String>()
    private val statusPolls = AtomicInteger(0)

    /** Replies for successive GET /api/scan/status calls; the last one repeats. */
    private var scanStates = listOf("scanning", "idle")
    private var lastError: String? = null
    private var scanCode = 202

    private val strings = LibraryScanner.Strings(
        scanning = "Scanning…",
        updated = "Library updated",
        scanFailed = { "Scan failed: $it" },
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when (request.path) {
                    "/api/scan" ->
                        if (scanCode >= 400) json("""{"error":"Media folder not set"}""").setResponseCode(scanCode)
                        else json("""{"status":${status("scanning")}}""").setResponseCode(scanCode)
                    "/api/scan/status" -> {
                        val n = statusPolls.getAndIncrement()
                        json(status(scanStates[minOf(n, scanStates.lastIndex)]))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun status(state: String) =
        """{"state":"$state","startedAt":1,"filesSeen":3,"filesProcessed":1,"phase":"probe","lastError":${lastError?.let { "\"$it\"" } ?: "null"}}"""

    private class Fixture(val scanner: LibraryScanner, val toaster: Toaster, val toasts: List<String>, val finished: List<Unit>)

    private fun TestScope.fixture(): Fixture {
        val toaster = Toaster(backgroundScope)
        val toasts = mutableListOf<String>()
        val finished = mutableListOf<Unit>()
        val scanner = LibraryScanner(api, toaster, backgroundScope, strings, pollIntervalMs = 2_000)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toaster.current.filterNotNull().toList(toasts) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { scanner.scanFinished.toList(finished) }
        return Fixture(scanner, toaster, toasts, finished)
    }

    @Test fun rescanPostsPollsUntilIdleSignalsFinishedAndToastsInOrder() = runTest {
        val f = fixture()
        f.scanner.rescan()
        f.toaster.current.first { it == "Library updated" }

        assertEquals(listOf("Scanning…", "Library updated"), f.toasts)
        assertEquals(listOf("POST /api/scan", "GET /api/scan/status", "GET /api/scan/status"), requests.toList())
        assertEquals(2, statusPolls.get())
        assertEquals(1, f.finished.size)
    }

    @Test fun pollsWaitTheIntervalBetweenPolls() = runTest {
        scanStates = listOf("scanning", "scanning", "idle")
        val f = fixture()
        val start = testScheduler.currentTime
        f.scanner.rescan()
        f.toaster.current.first { it == "Library updated" }

        assertEquals(3, statusPolls.get())
        // Each poll comes after a full interval (virtual time may run further while real HTTP is in flight).
        val elapsed = testScheduler.currentTime - start
        assertTrue("elapsed $elapsed ms", elapsed >= 6_000)
    }

    @Test fun lastErrorIsReportedAndStillSignalsFinished() = runTest {
        lastError = "Media folder is missing"
        val f = fixture()
        f.scanner.rescan()
        f.toaster.current.first { it?.startsWith("Scan failed") == true }

        assertEquals(listOf("Scanning…", "Scan failed: Media folder is missing"), f.toasts)
        assertEquals(1, f.finished.size)
    }

    @Test fun secondRescanWhileScanningPostsOnce() = runTest {
        val f = fixture()
        f.scanner.rescan()
        f.scanner.rescan()
        f.toaster.current.first { it == "Library updated" }

        assertEquals(1, requests.count { it == "POST /api/scan" })
        assertEquals(2, statusPolls.get())
        assertEquals(1, f.finished.size)
    }

    @Test fun rescanAfterFinishStartsANewScan() = runTest {
        val f = fixture()
        f.scanner.rescan()
        f.toaster.current.first { it == "Library updated" }
        statusPolls.set(0)
        f.scanner.rescan()
        f.toaster.current.first { it == "Scanning…" }
        f.toaster.current.first { it == "Library updated" }

        assertEquals(2, requests.count { it == "POST /api/scan" })
        assertEquals(2, f.finished.size)
    }

    @Test fun serverErrorIsToastedWithoutPollingOrSignal() = runTest {
        scanCode = 409
        val f = fixture()
        f.scanner.rescan()
        f.toaster.current.first { it == "Media folder not set" }

        assertEquals(listOf("POST /api/scan"), requests.toList())
        assertEquals(0, f.finished.size)
    }

    @Test fun aThrowingPollDoesNotPropagateOutOfTheAppScope() = runTest {
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val scope = appScope { uncaught += it }
        // A failure that is not an ApiException (OkHttp hands an interceptor's RuntimeException straight through).
        val throwing = ApiClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.encodedPath == "/api/scan/status") throw IllegalStateException("boom")
                chain.proceed(chain.request())
            }.build(),
            ApiClient.defaultJson,
        ).also { it.baseUrl = api.baseUrl }
        val scanner = LibraryScanner(throwing, Toaster(scope), scope, strings, pollIntervalMs = 10)

        scanner.rescan()
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (uncaught.isEmpty()) delay(10) } }

        assertEquals("boom", uncaught.single().message)
        assertTrue("the app scope keeps running", scope.isActive)
        scanner.rescan()
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (uncaught.size < 2) delay(10) } }
        assertEquals("a later rescan runs again", 2, requests.count { it == "POST /api/scan" })
        scope.cancel()
    }
}
