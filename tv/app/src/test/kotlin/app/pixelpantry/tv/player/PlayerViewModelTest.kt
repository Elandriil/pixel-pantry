package app.pixelpantry.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.player.PlayerViewModel.NavEvent
import app.pixelpantry.tv.player.PlayerViewModel.UiState
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var port: FakePlayerPort
    private val http = OkHttpClient()
    private val store = ViewModelStore()

    /** Progress posts in arrival order. */
    private val progressPosts = ConcurrentLinkedQueue<JsonObject>()
    private var itemBody: () -> MockResponse = { json(itemFixture(progress = """{"position":754.5,"duration":3000,"watched":false}""")) }

    /** Toasts clear on a real-time scope (see ShowViewModelTest). */
    private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toaster = Toaster(toastScope)

    private val strings = PlayerViewModel.Strings(
        playbackFailed = "Playback failed",
        comingLater = "Coming in a later step",
        subtitlesOff = "Off",
        audioDefault = "Default",
    )

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.path}"
                return when (line) {
                    "GET /api/items/4" -> itemBody()
                    "POST /api/items/4/progress" -> {
                        progressPosts += Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        json("""{"sessionId":7,"watched":false}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        api = ApiClient(http, ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
        port = FakePlayerPort()
    }

    @After fun tearDown() {
        store.clear()
        // A progress call still in flight resumes its (cancelled) coroutine on Main when it returns; let it
        // finish while Main is still the test dispatcher, or it fails the next test.
        val deadline = System.currentTimeMillis() + 5_000
        while (http.dispatcher.runningCallsCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(50)
        Dispatchers.resetMain()
        toastScope.cancel()
        runCatching { server.shutdown() }
    }

    /**
     * Runs a test and clears the view model at its end: its ticker loops forever on the test scheduler, and
     * `runTest` would otherwise keep advancing virtual time through it.
     */
    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 30.seconds) {
        try {
            body()
        } finally {
            store.clear()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun TestScope.vm(fromStart: Boolean = false): PlayerViewModel {
        val reporter = ProgressReporter(api, clock = { testScheduler.currentTime })
        val settings = SettingsRepo(api, ServerStore(NoopDataStore), backgroundScope)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlayerViewModel(api, settings, reporter, toaster, strings, itemId = 4, fromStart = fromStart, port = port) as T
        }
        return ViewModelProvider(store, factory)[PlayerViewModel::class.java]
    }

    private suspend fun PlayerViewModel.playing(): UiState.Playing =
        state.first { it !is UiState.Loading } as UiState.Playing

    private fun PlayerViewModel.current(): UiState.Playing = state.value as UiState.Playing

    private fun TestScope.collectEvents(vm: PlayerViewModel): MutableList<NavEvent> {
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        return events
    }

    /** Waits in real time (HTTP runs on real threads) until [condition] holds, failing after 5 s. */
    private suspend fun awaitRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test fun loadsTheItemAndSeeksToTheSavedPosition() = playerTest {
        val vm = vm()
        val s = vm.playing()
        assertEquals("Severance S01E01.mkv", s.detail.fileName)
        assertEquals(listOf("prepare", "seekTo(754500)", "play"), port.calls)
        assertEquals("4", port.prepared.single().mediaId)
        assertEquals(754_500L, s.positionMs)
        assertFalse(s.paused)
        assertFalse(s.overlayVisible)
        assertEquals("ENG · aac", s.audioLabel)
        assertEquals("Off", s.subtitleLabel)
        assertEquals(0, s.subDelayMs)
    }

    @Test fun fromStartSeeksToZero() = playerTest {
        vm(fromStart = true).playing()
        assertEquals(listOf(0L), port.seeks)
    }

    @Test fun watchedItemSeeksToZero() = playerTest {
        itemBody = { json(itemFixture(progress = """{"position":2990,"duration":3000,"watched":true}""")) }
        vm().playing()
        assertEquals(listOf(0L), port.seeks)
    }

    @Test fun itemWithoutProgressSeeksToZero() = playerTest {
        itemBody = { json(itemFixture(progress = null)) }
        vm().playing()
        assertEquals(listOf(0L), port.seeks)
    }

    @Test fun tickerFollowsThePlayerAndReportsProgressWithoutSessionIdFirst() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 760_000
        advanceTimeBy(501)
        assertEquals(760_000L, vm.current().positionMs)
        assertEquals(3_000_000L, vm.current().durationMs)
        awaitRealTime { progressPosts.isNotEmpty() }
        val first = progressPosts.first()
        assertEquals(760.0, first["position"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3000.0, first["duration"]!!.jsonPrimitive.double, 0.0)
        assertNull(first["sessionId"])
    }

    @Test fun noProgressIsReportedWhileTheDurationIsUnknown() = playerTest {
        val vm = vm()
        vm.playing()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(progressPosts.isEmpty())
    }

    @Test fun togglePausePausesFlushesAndKeepsTheOverlayUp() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 800_000
        vm.togglePause()
        assertEquals("pause", port.calls.last())
        assertTrue(vm.current().paused)
        assertTrue(vm.current().overlayVisible)
        awaitRealTime { progressPosts.any { it["position"]!!.jsonPrimitive.double == 800.0 } }

        advanceTimeBy(10_000)
        assertTrue("overlay stays while paused", vm.current().overlayVisible)

        vm.togglePause()
        assertEquals("play", port.calls.last())
        assertFalse(vm.current().paused)
    }

    @Test fun stopAndExitPausesFlushesThenNavigatesBack() = playerTest {
        val vm = vm()
        val events = collectEvents(vm)
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.stopAndExit()
        assertEquals("pause", port.calls.last())
        val back = vm.navEvents.first()
        assertEquals(NavEvent.Back, back)
        assertTrue("flushed before going back", progressPosts.any { it["position"]!!.jsonPrimitive.double == 900.0 })
        assertEquals(listOf(NavEvent.Back), events)

        vm.stopAndExit() // a second BACK does nothing more
        runCurrent()
        assertEquals(listOf(NavEvent.Back), events)
    }

    @Test fun playerErrorShowsTheMessageAndFileName() = playerTest {
        val vm = vm()
        vm.playing()
        port.fire { it.onPlayerError("Lost connection to the server") }
        val s = vm.state.value as UiState.Error
        assertEquals("Playback failed", s.title)
        assertEquals("Lost connection to the server", s.detail)
        assertEquals("Severance S01E01.mkv", s.fileName)

        // Back from the error screen leaves the player.
        val events = collectEvents(vm)
        vm.stopAndExit()
        runCurrent()
        assertEquals(listOf(NavEvent.Back), events)
    }

    @Test fun loadFailureIsAnError() = playerTest {
        itemBody = { MockResponse().setResponseCode(404).setBody("""{"error":"Item not found"}""") }
        val vm = vm()
        val s = vm.state.first { it !is UiState.Loading } as UiState.Error
        assertEquals("Playback failed", s.title)
        assertEquals("Item not found", s.detail)
        assertNull(s.fileName)
        assertTrue(port.prepared.isEmpty())
    }

    @Test fun overlayAutoHidesFourSecondsAfterTheLastShow() = playerTest {
        val vm = vm()
        vm.playing()
        vm.showOverlay()
        assertTrue(vm.current().overlayVisible)
        advanceTimeBy(3_000)
        vm.showOverlay() // interaction restarts the timer
        advanceTimeBy(3_999)
        assertTrue(vm.current().overlayVisible)
        advanceTimeBy(2)
        assertFalse(vm.current().overlayVisible)
    }

    @Test fun hideOverlayHidesAtOnce() = playerTest {
        val vm = vm()
        vm.playing()
        vm.showOverlay()
        vm.hideOverlay()
        assertFalse(vm.current().overlayVisible)
    }

    @Test fun bufferingFollowsThePlaybackState() = playerTest {
        val vm = vm()
        vm.playing()
        port.fire { it.onPlaybackStateChanged(Player.STATE_BUFFERING) }
        assertTrue(vm.current().buffering)
        port.fire { it.onPlaybackStateChanged(Player.STATE_READY) }
        assertFalse(vm.current().buffering)
    }

    @Test fun seekByClampsToTheItemAndShowsTheOverlay() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 760_000
        port.position = 754_500
        vm.seekBy(10)
        assertEquals(760_000L, port.seeks.last())
        assertEquals(760_000L, vm.current().positionMs)
        assertTrue(vm.current().overlayVisible)
        port.position = 5_000
        vm.seekBy(-10)
        assertEquals(0L, port.seeks.last())
    }

    @Test fun clearingTheViewModelReleasesThePlayer() = playerTest {
        vm().playing()
        store.clear()
        assertTrue(port.released)
    }
}

private fun itemFixture(progress: String?): String = """
{"item":{"id":4,"type":"episode","category":"tv","title":"Good News About Hell","showId":3,"showTitle":"Severance",
 "season":1,"episode":1,"progress":${progress ?: "null"},"addedAt":1},
 "streamUrl":"/api/items/4/stream","fileName":"Severance S01E01.mkv","fileSize":880485,
 "audio":[{"index":1,"codec":"aac","language":"eng","default":true,"channels":2}],
 "subtitles":[],
 "next":{"id":5,"type":"episode","category":"tv","title":"Half Loop","showId":3,"showTitle":"Severance","season":1,"episode":2,"addedAt":1},
 "prev":null}
""".trimIndent()

/** A preferences store that holds nothing (the player never writes settings). */
private object NoopDataStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
    override suspend fun updateData(
        transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ) = androidx.datastore.preferences.core.emptyPreferences()
}
