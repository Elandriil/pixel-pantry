package app.pixelpantry.tv.ui.history

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.ui.history.HistoryViewModel.UiState
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val historyLoads = AtomicInteger(0)

    private val zone = ZoneId.of("Europe/Tallinn")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    /** Monday 26 Oct 2026, 22:00 local. */
    private val now = at(2026, 10, 26, 22, 0)

    private val episode = """{"id":5,"type":"episode","category":"tv","title":"Hello, Ms. Cobel","showId":3,"showTitle":"Severance","season":2,"episode":4,"still":"/img/s.jpg","addedAt":0}"""
    private val orphanEpisode = """{"id":6,"type":"episode","category":"tv","title":"Lost","season":1,"episode":1,"addedAt":0}"""
    private val movie = """{"id":9,"type":"movie","category":"movies","title":"Se7en","poster":"/img/p.jpg","addedAt":0}"""

    private fun entry(session: Int, item: String, itemId: Int, started: Long, seconds: Double) =
        """{"sessionId":$session,"itemId":$itemId,"startedAt":$started,"endedAt":${started + 1000},"secondsWatched":$seconds,"item":$item}"""

    private var historyBody = """{"entries":[
        ${entry(4, episode, 5, at(2026, 10, 26, 21, 14), 1925.0)},
        ${entry(3, movie, 9, at(2026, 10, 26, 0, 30), 30.0)},
        ${entry(2, episode, 5, at(2026, 10, 25, 23, 59), 600.0)},
        ${entry(1, movie, 9, at(2026, 10, 21, 20, 5), 3600.0)},
        ${entry(0, orphanEpisode, 6, at(2025, 12, 31, 12, 0), 120.0)}
    ]}"""
    private val statsBody = """{"hoursWeek":1.5,"hoursMonth":2.25,"hoursAll":10.0,"episodesAll":4,"moviesAll":1,"topShowMonth":{"showId":3,"title":"Severance","hours":2.0}}"""

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/api/history" -> { historyLoads.incrementAndGet(); json(historyBody) }
                "/api/stats" -> json(statsBody)
                else -> MockResponse().setResponseCode(404)
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

    private val strings = HistoryViewModel.Strings(today = "Today", yesterday = "Yesterday", couldNotRefresh = "Could not refresh")

    private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toaster = Toaster(toastScope)
    private val reconnects = AtomicInteger(0)
    private var onReconnect: () -> Unit = {}

    private fun vm() = HistoryViewModel(
        api, strings, toaster, clock = { now }, zone = zone, locale = Locale.ENGLISH,
        reconnect = { reconnects.incrementAndGet(); onReconnect() },
    )

    private suspend fun awaitRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private suspend fun HistoryViewModel.loaded(): UiState.Loaded {
        awaitRealTime { state.value !is UiState.Loading }
        return state.value as UiState.Loaded
    }

    @Test fun groupsByLocalDayWithTodayYesterdayAndDateLabels() = runTest {
        val s = vm().loaded()
        assertEquals(listOf("Today", "Yesterday", "Wed 21 Oct", "Wed 31 Dec 2025"), s.days.map { it.label })
        assertEquals(
            listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 21), LocalDate.of(2025, 12, 31)),
            s.days.map { it.date },
        )
        assertEquals(listOf(4, 3), s.days[0].entries.map { it.entry.sessionId })
        assertEquals(listOf(2), s.days[1].entries.map { it.entry.sessionId })
        assertEquals(listOf(1), s.days[2].entries.map { it.entry.sessionId })
    }

    @Test fun rowsCarryTitleWatchedAndTimeLabels() = runTest {
        val s = vm().loaded()
        val ep = s.days[0].entries[0]
        assertEquals("Severance · S2 · E4 · Hello, Ms. Cobel", ep.title)
        assertEquals("32 min watched", ep.watchedLabel)
        assertEquals("21:14", ep.timeLabel)
        val shortMovie = s.days[0].entries[1]
        assertEquals("Se7en", shortMovie.title)
        assertEquals("< 1 min watched", shortMovie.watchedLabel)
        assertEquals("00:30", shortMovie.timeLabel)
        assertEquals("23:59", s.days[1].entries[0].timeLabel)
    }

    @Test fun statsComeThrough() = runTest {
        val s = vm().loaded()
        assertEquals(1.5, s.stats.hoursWeek, 0.0)
        assertEquals("Severance", s.stats.topShowMonth?.title)
    }

    @Test fun emptyHistoryIsLoadedWithNoDays() = runTest {
        historyBody = """{"entries":[]}"""
        val s = vm().loaded()
        assertTrue(s.days.isEmpty())
    }

    @Test fun openGoesToTheShowForEpisodesAndTheMoviePageForMovies() = runTest {
        val vm = vm()
        val s = vm.loaded()
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        vm.open(s.days[0].entries[0])
        vm.open(s.days[0].entries[1])
        assertEquals(listOf(NavEvent.Show(3), NavEvent.Movie(9)), events)
    }

    @Test fun episodeWithoutAShowGoesNowhere() = runTest {
        val vm = vm()
        val s = vm.loaded()
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        val orphan = s.days.last().entries.single()
        assertEquals("S1 · E1 · Lost", orphan.title)
        vm.open(orphan)
        assertTrue(events.isEmpty())
    }

    @Test fun reloadsOnLaterResumesOnly() = runTest {
        val vm = vm()
        vm.loaded()
        vm.onResume()
        assertEquals(1, historyLoads.get())
        vm.onResume()
        awaitRealTime { historyLoads.get() == 2 }
    }

    @Test fun serverErrorIsAnErrorState() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
        }
        val vm = vm()
        awaitRealTime { vm.state.value !is UiState.Loading }
        assertEquals(UiState.Error("boom"), vm.state.value)
    }

    // Unreachable server and failed refreshes (Task 13)

    private val deadUrl = "http://127.0.0.1:1"

    @Test fun anUnreachableServerOnTheFirstLoadShowsUnreachableWithTheBaseUrl() = runTest {
        api.baseUrl = deadUrl
        val vm = vm()
        awaitRealTime { vm.state.value !is UiState.Loading }
        assertEquals(UiState.Unreachable(deadUrl), vm.state.value)
    }

    @Test fun retryReconnectsOnceThenReloads() = runTest {
        val live = api.baseUrl
        api.baseUrl = deadUrl
        val vm = vm()
        vm.state.first { it is UiState.Unreachable }
        onReconnect = { api.baseUrl = live }
        vm.retry()
        assertEquals(4, vm.loaded().days.size)
        assertEquals(1, reconnects.get())
    }

    @Test fun aFailedReloadKeepsTheLoadedHistoryAndToasts() = runTest {
        val vm = vm()
        val before = vm.loaded()
        api.baseUrl = deadUrl
        vm.onResume()
        vm.onResume()
        awaitRealTime { toaster.current.value != null }
        assertEquals("Could not refresh", toaster.current.value)
        assertEquals(before, vm.state.value)
    }
}
