package app.pixelpantry.tv.ui.show

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.show.ShowViewModel.NavEvent
import app.pixelpantry.tv.ui.show.ShowViewModel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ShowViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var toaster: Toaster

    /** Requests in arrival order, as "METHOD /path"; write bodies by path. */
    private val requests = ConcurrentLinkedQueue<String>()
    private val bodies = ConcurrentLinkedQueue<Pair<String, String>>()
    private val showLoads = AtomicInteger(0)
    private val movieLoads = AtomicInteger(0)

    private var showBody: () -> MockResponse = { json(showFixture(resume = s2e4InProgress)) }
    private var movieBody: () -> MockResponse = { json(movieFixture(progress = null)) }
    private var deleteBody: () -> MockResponse = { json("""{"trashed":["a.mkv","b.mkv"]}""") }
    private var watchedBody: () -> MockResponse = { json("""{"ok":true}""") }

    private val strings = ShowViewModel.Strings(
        play = "Play",
        resume = "Resume",
        playEpisode = { tag -> "Play $tag" },
        resumeEpisode = { tag, left -> "Resume $tag · $left" },
        markSeasonWatched = "Mark season watched",
        markSeasonUnwatched = "Mark season unwatched",
        movedToRecycleBin = "Moved to Recycle Bin",
        couldNotRefresh = "Could not refresh",
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.path}"
                requests += line
                if (request.method == "POST") bodies += line to request.body.readUtf8()
                return when (line) {
                    "GET /api/shows/1" -> { showLoads.incrementAndGet(); showBody() }
                    "GET /api/shows/2" -> json(specialsOnlyFixture)
                    "GET /api/items/20" -> { movieLoads.incrementAndGet(); movieBody() }
                    "POST /api/shows/1/watched", "POST /api/items/12/watched", "POST /api/items/20/watched" -> watchedBody()
                    "DELETE /api/shows/1", "DELETE /api/movies/20" -> deleteBody()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        toastScope.cancel()
        runCatching { server.shutdown() }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /**
     * Toasts clear on a real-time scope: on the test scheduler, virtual time jumps ahead while a test waits in
     * real time for HTTP, and the toast would be gone before it is checked.
     */
    private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val reconnects = AtomicInteger(0)
    private var onReconnect: () -> Unit = {}

    private fun showVm(id: Int = 1): ShowViewModel {
        toaster = Toaster(toastScope)
        return ShowViewModel(api, showId = id, movieItemId = null, toaster = toaster, strings = strings) {
            reconnects.incrementAndGet()
            onReconnect()
        }
    }

    private fun movieVm(): ShowViewModel {
        toaster = Toaster(toastScope)
        return ShowViewModel(api, showId = null, movieItemId = 20, toaster = toaster, strings = strings) { reconnects.incrementAndGet() }
    }

    private fun TestScope.collectEvents(vm: ShowViewModel): MutableList<NavEvent> {
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        return events
    }

    /** Waits in real time (HTTP runs on real threads) until [condition] holds, failing after 5 s. */
    private suspend fun awaitRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private suspend fun ShowViewModel.loaded(): UiState.Loaded = state.first { it !is UiState.Loading } as UiState.Loaded

    private fun body(line: String): JsonObject =
        Json.parseToJsonElement(bodies.last { it.first == line }.second) as JsonObject

    @Test fun showLoadsWithSpecialsLastAndTheResumeSeasonSelected() = runTest {
        val s = showVm().loaded()
        assertEquals("Severance", s.show?.title)
        assertNull(s.movie)
        assertEquals(listOf(1, 2, 0), s.seasons.map { it.number })
        assertEquals(2, s.selectedSeason)
        assertEquals(12, s.resume?.id)
        assertEquals(7, s.fileCount)
        assertEquals(listOf("GET /api/shows/1"), requests.toList())
    }

    @Test fun firstSeasonIsSelectedWithoutAResumeItem() = runTest {
        showBody = { json(showFixture(resume = null)) }
        assertEquals(1, showVm().loaded().selectedSeason)
    }

    @Test fun primaryActionResumesTheInProgressEpisode() = runTest {
        val vm = showVm()
        val events = collectEvents(vm)
        assertEquals("Resume S2 E4 · 23 min left", vm.loaded().primaryLabel)

        vm.primaryAction()
        assertEquals(listOf(NavEvent.Player(12, fromStart = false)), events)
    }

    @Test fun primaryActionPlaysTheFirstUnwatchedEpisode() = runTest {
        showBody = { json(showFixture(resume = s1e2Unstarted)) }
        val vm = showVm()
        val events = collectEvents(vm)
        assertEquals("Play S1 E2", vm.loaded().primaryLabel)

        vm.primaryAction()
        assertEquals(listOf(NavEvent.Player(11, fromStart = true)), events)
    }

    @Test fun primaryActionRestartsTheFirstEpisodeWhenAllAreWatched() = runTest {
        showBody = { json(showFixture(resume = null, allWatched = true)) }
        val vm = showVm()
        val events = collectEvents(vm)
        val s = vm.loaded()
        assertEquals("Play S1 E1", s.primaryLabel)
        assertTrue(s.allWatched)
        assertTrue(s.seasonWatched)
        assertEquals("Mark season unwatched", s.secondaryLabel)

        vm.primaryAction()
        assertEquals(listOf(NavEvent.Player(10, fromStart = true)), events)
    }

    @Test fun secondaryLabelFollowsTheSelectedSeason() = runTest {
        val vm = showVm()
        val s = vm.loaded() // season 2 selected: not all watched
        assertFalse(s.seasonWatched)
        assertEquals("Mark season watched", s.secondaryLabel)

        vm.selectSeason(0) // Specials: its one episode is watched
        val specials = vm.state.value as UiState.Loaded
        assertEquals(0, specials.selectedSeason)
        assertTrue(specials.seasonWatched)
        assertEquals("Mark season unwatched", specials.secondaryLabel)
    }

    @Test fun playEmitsPlayerEvent() = runTest {
        val vm = showVm()
        val events = collectEvents(vm)
        val episode = vm.loaded().seasons[0].episodes[0]
        vm.play(episode, fromStart = true)
        assertEquals(listOf(NavEvent.Player(10, fromStart = true)), events)
    }

    @Test fun markSeasonWatchedPostsWatchedAndSeasonThenReloads() = runTest {
        val vm = showVm()
        vm.loaded()
        vm.markSeasonWatched(2, watched = true)
        awaitRealTime { showLoads.get() == 2 }

        val body = body("POST /api/shows/1/watched")
        assertEquals(true, body["watched"]!!.jsonPrimitive.boolean)
        assertEquals(2, body["season"]!!.jsonPrimitive.int)
        assertEquals(listOf("GET /api/shows/1", "POST /api/shows/1/watched", "GET /api/shows/1"), requests.toList())
        assertEquals(2, (vm.state.value as UiState.Loaded).selectedSeason) // selection kept across the reload
    }

    @Test fun selectedSeasonSurvivesAReload() = runTest {
        val vm = showVm()
        vm.loaded()
        vm.selectSeason(1)
        vm.markSeasonWatched(1, watched = true)
        awaitRealTime { showLoads.get() == 2 }
        assertEquals(1, (vm.state.value as UiState.Loaded).selectedSeason)
    }

    @Test fun markShowWatchedPostsWithoutSeasonThenReloads() = runTest {
        val vm = showVm()
        vm.loaded()
        vm.markShowWatched(watched = false)
        awaitRealTime { showLoads.get() == 2 }

        val body = body("POST /api/shows/1/watched")
        assertEquals(false, body["watched"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("season"))
    }

    @Test fun markWatchedPostsTheItemThenReloads() = runTest {
        val vm = showVm()
        val episode = vm.loaded().seasons[1].episodes[0]
        vm.markWatched(episode, watched = true)
        awaitRealTime { showLoads.get() == 2 }

        assertEquals(true, body("POST /api/items/12/watched")["watched"]!!.jsonPrimitive.boolean)
    }

    @Test fun failedWriteToastsTheServerMessageAndDoesNotReload() = runTest {
        watchedBody = { json("""{"error":"Item not found"}""").setResponseCode(404) }
        val vm = showVm()
        val episode = vm.loaded().seasons[1].episodes[0]
        vm.markWatched(episode, watched = true)
        awaitRealTime { toaster.current.value != null }

        assertEquals("Item not found", toaster.current.value)
        assertEquals(1, showLoads.get())
    }

    @Test fun deleteFromPcDeletesTheShowToastsAndGoesBack() = runTest {
        val vm = showVm()
        val events = collectEvents(vm)
        vm.loaded()
        vm.deleteFromPc()
        awaitRealTime { events.isNotEmpty() }

        assertTrue("DELETE /api/shows/1" in requests)
        assertEquals("Moved to Recycle Bin", toaster.current.value)
        assertEquals(listOf<NavEvent>(NavEvent.Back), events)
    }

    @Test fun failedDeleteToastsTheErrorAndStays() = runTest {
        deleteBody = { json("""{"error":"File is in use"}""").setResponseCode(409) }
        val vm = showVm()
        val events = collectEvents(vm)
        vm.loaded()
        vm.deleteFromPc()
        awaitRealTime { toaster.current.value != null }

        assertEquals("File is in use", toaster.current.value)
        assertTrue(events.isEmpty())
    }

    @Test fun specialsOnlyShowIsLoadedWithSeasonZeroSelected() = runTest {
        val vm = showVm(id = 2)
        val events = collectEvents(vm)
        val s = vm.loaded()
        assertEquals(listOf(0), s.seasons.map { it.number })
        assertEquals(0, s.selectedSeason)
        assertEquals("Play S0 E1", s.primaryLabel)

        vm.play(s.seasons[0].episodes[0], fromStart = false)
        assertEquals(listOf(NavEvent.Player(30, fromStart = false)), events)
    }

    @Test fun movieLoadsThroughItemWithNoSeasons() = runTest {
        val vm = movieVm()
        val events = collectEvents(vm)
        val s = vm.loaded()
        assertNull(s.show)
        assertEquals("Heat", s.movie?.title)
        assertTrue(s.seasons.isEmpty())
        assertEquals(1, s.fileCount)
        assertEquals("Play", s.primaryLabel)
        assertEquals(listOf("GET /api/items/20"), requests.toList())

        vm.primaryAction()
        assertEquals(listOf(NavEvent.Player(20, fromStart = true)), events)
    }

    @Test fun movieInProgressResumes() = runTest {
        movieBody = { json(movieFixture(progress = """{"position":600,"duration":10200,"watched":false}""")) }
        val vm = movieVm()
        val events = collectEvents(vm)
        assertEquals("Resume", vm.loaded().primaryLabel)
        vm.primaryAction()
        assertEquals(listOf(NavEvent.Player(20, fromStart = false)), events)
    }

    @Test fun movieMarkWatchedAndDeleteUseTheMovieEndpoints() = runTest {
        val vm = movieVm()
        val events = collectEvents(vm)
        vm.loaded()
        vm.markShowWatched(watched = true)
        awaitRealTime { movieLoads.get() == 2 }
        assertEquals(true, body("POST /api/items/20/watched")["watched"]!!.jsonPrimitive.boolean)

        vm.deleteFromPc()
        awaitRealTime { events.isNotEmpty() }
        assertTrue("DELETE /api/movies/20" in requests)
        assertEquals(listOf<NavEvent>(NavEvent.Back), events)
    }

    @Test fun loadErrorCarriesTheServerMessage() = runTest {
        showBody = { json("""{"error":"Show not found"}""").setResponseCode(404) }
        assertEquals(UiState.Error("Show not found"), showVm().state.first { it !is UiState.Loading })
    }

    @Test fun reloadsOnResumeButNotOnTheFirstResume() = runTest {
        val vm = showVm()
        vm.loaded()
        vm.onResume()
        assertEquals(1, showLoads.get())

        vm.onResume()
        awaitRealTime { showLoads.get() == 2 }
        assertTrue(vm.state.value is UiState.Loaded)
    }

    // Unreachable server, failed refreshes, actions during a delete (Task 13)

    private val deadUrl = "http://127.0.0.1:1"

    @Test fun anUnreachableServerOnTheFirstLoadShowsUnreachableWithTheBaseUrl() = runTest {
        api.baseUrl = deadUrl
        assertEquals(UiState.Unreachable(deadUrl), showVm().state.first { it !is UiState.Loading })
        assertEquals(0, reconnects.get())
    }

    @Test fun retryReconnectsOnceThenReloads() = runTest {
        val live = api.baseUrl
        api.baseUrl = deadUrl
        val vm = showVm()
        vm.state.first { it is UiState.Unreachable }
        onReconnect = { api.baseUrl = live }
        vm.retry()
        assertEquals("Severance", vm.loaded().title)
        assertEquals(1, reconnects.get())
    }

    @Test fun aFailedReloadKeepsThePageAndToastsCouldNotRefresh() = runTest {
        val vm = showVm()
        val before = vm.loaded()
        api.baseUrl = deadUrl
        vm.onResume()
        vm.onResume()
        awaitRealTime { toaster.current.value != null }
        assertEquals("Could not refresh", toaster.current.value)
        assertEquals(before, vm.state.value)
    }

    @Test fun actionsAreIgnoredWhileDeletingAndAfterTheDeleteSucceeded() = runTest {
        deleteBody = { json("""{"trashed":["a.mkv"]}""").setHeadersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
        val vm = showVm()
        val events = collectEvents(vm)
        val s = vm.loaded()
        vm.deleteFromPc()
        assertTrue(vm.deleting.value)
        vm.primaryAction()
        vm.play(s.seasons[0].episodes[0], fromStart = false)
        vm.markShowWatched(true)
        awaitRealTime { events.isNotEmpty() }

        // Still deleting until the page has gone: a late press cannot start playback of deleted files.
        assertTrue(vm.deleting.value)
        vm.primaryAction()
        assertEquals(listOf<NavEvent>(NavEvent.Back), events)
        assertTrue(requests.none { it.startsWith("POST") })
    }

    @Test fun aFailedDeleteEnablesTheActionsAgain() = runTest {
        deleteBody = { json("""{"error":"File is in use"}""").setResponseCode(409) }
        val vm = showVm()
        val events = collectEvents(vm)
        vm.loaded()
        vm.deleteFromPc()
        awaitRealTime { !vm.deleting.value }
        vm.primaryAction()
        assertEquals(listOf<NavEvent>(NavEvent.Player(12, fromStart = false)), events)
    }

    private companion object {
        fun ep(id: Int, season: Int, episode: Int, progress: String? = null) =
            """{"id":$id,"type":"episode","category":"tv","title":"Episode $episode","showId":1,"showTitle":"Severance","season":$season,"episode":$episode,"runtime":50,"progress":${progress ?: "null"},"addedAt":1}"""

        const val WATCHED = """{"position":3000,"duration":3000,"watched":true}"""
        val s2e4InProgress = ep(12, 2, 4, """{"position":1620,"duration":3000,"watched":false}""")
        val s1e2Unstarted = ep(11, 1, 2)

        val show = """{"id":1,"category":"tv","title":"Severance","year":2022,"rating":8.7,"genres":["Drama","Mystery"],"episodeCount":4,"unwatchedCount":2,"addedAt":1}"""

        /** Seasons in a deliberately wrong order (Specials first) to check the client's ordering. */
        fun showFixture(resume: String?, allWatched: Boolean = false): String {
            val w = if (allWatched) WATCHED else null
            val s1 = """{"number":1,"name":"Season 1","episodes":[${ep(10, 1, 1, w ?: WATCHED)},${ep(11, 1, 2, w)}]}"""
            val s2 = """{"number":2,"name":"Season 2","episodes":[${if (allWatched) ep(12, 2, 4, w) else s2e4InProgress}]}"""
            val sp = """{"number":0,"name":"Specials","episodes":[${ep(13, 0, 1, WATCHED)}]}"""
            return """{"show":$show,"seasons":[$sp,$s2,$s1],"resume":${resume ?: "null"},"fileCount":7}"""
        }

        val specialsOnlyFixture = """{"show":{"id":2,"category":"anime","title":"Frieren Specials","episodeCount":1,"unwatchedCount":1,"addedAt":1},
            "seasons":[{"number":0,"name":"Specials","episodes":[${ep(30, 0, 1)}]}],"resume":${ep(30, 0, 1)},"fileCount":1}"""

        fun movieFixture(progress: String?) =
            """{"item":{"id":20,"type":"movie","category":"movies","title":"Heat","year":1995,"runtime":170,"progress":${progress ?: "null"},"addedAt":1},
                "streamUrl":"/api/items/20/stream","fileName":"Heat.mkv","fileSize":1}"""
    }
}
