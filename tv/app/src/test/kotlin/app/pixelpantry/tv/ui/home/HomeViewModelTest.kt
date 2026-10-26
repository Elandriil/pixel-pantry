package app.pixelpantry.tv.ui.home

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import app.pixelpantry.tv.ui.home.HomeViewModel.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class HomeViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    /** Requests in arrival order, as "METHOD /path". */
    private val requests = ConcurrentLinkedQueue<String>()
    private val homeLoads = AtomicInteger(0)
    private val statusPolls = AtomicInteger(0)

    /** Replies for successive GET /api/scan/status calls; the last one repeats. */
    private var scanStates = listOf("scanning", "idle")
    private var lastError: String? = null
    private var homeBody: () -> MockResponse = { json(homeFixture) }

    private val strings = HomeViewModel.Strings(
        scanning = "Scanning…",
        updated = "Library updated",
        scanFailed = { "Scan failed: $it" },
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when (request.path) {
                    "/api/home" -> { homeLoads.incrementAndGet(); homeBody() }
                    "/api/categories/tv" -> json(tvFixture)
                    "/api/scan" -> json("""{"status":${status("scanning")}}""").setResponseCode(202)
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

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun status(state: String) =
        """{"state":"$state","startedAt":1,"finishedAt":null,"filesSeen":3,"filesProcessed":1,"phase":"probe","lastError":${lastError?.let { "\"$it\"" } ?: "null"}}"""

    private fun TestScope.newVm(category: String = "home", toaster: Toaster = Toaster(backgroundScope)) =
        HomeViewModel(api, category, toaster, strings, pollIntervalMs = 2_000)

    private suspend fun HomeViewModel.loaded(): UiState.Loaded = state.first { it !is UiState.Loading } as UiState.Loaded

    @Test fun homeLoadsNonEmptyRowsAndFocusesFirstCard() = runTest {
        val vm = newVm()
        val loaded = vm.loaded()
        assertEquals(listOf("continue", "recent"), loaded.rows.map { it.key })
        assertEquals(2, loaded.rows[1].cards.size)
        assertEquals(loaded.rows[0].cards[0], loaded.focused)
        assertEquals(listOf("GET /api/home"), requests.toList())
    }

    @Test fun categoryLoadsFromCategoryEndpoint() = runTest {
        val vm = newVm(category = "tv")
        val loaded = vm.loaded()
        assertEquals(listOf("recent", "all"), loaded.rows.map { it.key })
        assertEquals(listOf("GET /api/categories/tv"), requests.toList())
    }

    @Test fun emptyLibraryIsLoadedWithNoRowsAndNoFocus() = runTest {
        homeBody = { json("""{"category":"home","rows":[{"key":"continue","title":"Continue watching","cards":[]},{"key":"recent","title":"Recently added","cards":[]}]}""") }
        val vm = newVm()
        assertEquals(UiState.Loaded(rows = emptyList(), focused = null), vm.loaded())
    }

    @Test fun errorCarriesServerMessage() = runTest {
        homeBody = { json("""{"error":"Database is locked"}""").setResponseCode(500) }
        val vm = newVm()
        assertEquals(UiState.Error("Database is locked"), vm.state.first { it !is UiState.Loading })
    }

    @Test fun onFocusUpdatesHeroCard() = runTest {
        val vm = newVm()
        val show = vm.loaded().rows[1].cards[0]
        vm.onFocus(show)
        assertEquals(show, (vm.state.value as UiState.Loaded).focused)
    }

    @Test fun openEmitsNavEventPerCardKind() = runTest {
        val vm = newVm()
        val rows = vm.loaded().rows
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }

        vm.open(rows[0].cards[0]) // continue-row episode
        vm.open(rows[1].cards[0]) // show
        vm.open(rows[1].cards[1]) // movie elsewhere

        assertEquals(listOf(NavEvent.Player(7), NavEvent.Show(1), NavEvent.Movie(20)), events)
    }

    @Test fun rescanPostsPollsUntilIdleReloadsAndToastsInOrder() = runTest {
        val toaster = Toaster(backgroundScope)
        val toasts = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toaster.current.filterNotNull().toList(toasts) }
        val vm = newVm(toaster = toaster)
        vm.loaded()
        requests.clear()

        vm.rescan()
        toaster.current.first { it == "Library updated" }

        assertEquals(listOf("Scanning…", "Library updated"), toasts)
        assertEquals(2, statusPolls.get())
        assertEquals(
            listOf("POST /api/scan", "GET /api/scan/status", "GET /api/scan/status", "GET /api/home"),
            requests.toList(),
        )
        assertEquals(2, homeLoads.get())
        assertTrue(vm.state.value is UiState.Loaded)
    }

    @Test fun rescanWaitsThePollIntervalBetweenPolls() = runTest {
        scanStates = listOf("scanning", "scanning", "idle")
        val toaster = Toaster(backgroundScope)
        val vm = newVm(toaster = toaster)
        vm.loaded()
        val start = testScheduler.currentTime

        vm.rescan()
        toaster.current.first { it == "Library updated" }

        assertEquals(3, statusPolls.get())
        // Three polls, each after a full interval (virtual time may run further while real HTTP is in flight).
        val elapsed = testScheduler.currentTime - start
        assertTrue("elapsed $elapsed ms", elapsed >= 6_000)
    }

    @Test fun rescanReportsLastError() = runTest {
        lastError = "Media folder is missing"
        val toaster = Toaster(backgroundScope)
        val vm = newVm(toaster = toaster)
        vm.loaded()

        vm.rescan()
        assertEquals("Scan failed: Media folder is missing", toaster.current.first { it?.startsWith("Scan failed") == true })
    }

    @Test fun rescanWhileScanningDoesNotStartASecondPoll() = runTest {
        scanStates = listOf("scanning", "idle")
        val toaster = Toaster(backgroundScope)
        val vm = newVm(toaster = toaster)
        vm.loaded()

        vm.rescan()
        vm.rescan()
        toaster.current.first { it == "Library updated" }

        assertEquals(1, requests.count { it == "POST /api/scan" })
        assertEquals(2, statusPolls.get())
    }

    private companion object {
        val episode = """{"id":7,"type":"episode","category":"tv","title":"Pilot","showId":1,"showTitle":"Severance","season":1,"episode":1,"runtime":3000,"still":"/api/images/w500/s.jpg","progress":{"position":1620,"duration":3000,"watched":false},"addedAt":5}"""
        val show = """{"id":1,"category":"tv","title":"Severance","year":2022,"poster":"/api/images/w500/p.jpg","backdrop":"/api/images/w1280/b.jpg","episodeCount":9,"unwatchedCount":8,"matched":true,"addedAt":5}"""
        val movie = """{"id":20,"type":"movie","category":"movies","title":"Heat","year":1995,"runtime":10200,"addedAt":4}"""

        val homeFixture = """{"category":"home","rows":[
            {"key":"continue","title":"Continue watching","cards":[{"kind":"item","item":$episode,"reason":"resume"}]},
            {"key":"recent","title":"Recently added","cards":[{"kind":"show","show":$show},{"kind":"item","item":$movie}]}]}"""

        val tvFixture = """{"category":"tv","rows":[
            {"key":"continue","title":"Continue watching","cards":[]},
            {"key":"recent","title":"Recently added","cards":[{"kind":"show","show":$show}]},
            {"key":"all","title":"All","cards":[{"kind":"show","show":$show}]}]}"""
    }
}
