package app.pixelpantry.tv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import app.pixelpantry.tv.api.ApiClient
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

    /** Every request as `METHOD /path`, in arrival order. */
    private val requests = ConcurrentLinkedQueue<String>()

    /** Progress posts for item 5 (the next episode) in arrival order. */
    private val progressPosts5 = ConcurrentLinkedQueue<JsonObject>()
    private var settingsBody: () -> MockResponse = { MockResponse().setResponseCode(404) }
    private var item5DelayMs = 0L
    private var item5Body: () -> MockResponse = { json(item5Fixture()) }
    private lateinit var settings: SettingsRepo

    /** Bodies of `POST /api/items/4/tracks`, `.../subtitles/download`, in arrival order. */
    private val trackPosts = ConcurrentLinkedQueue<JsonObject>()
    private val downloadPosts = ConcurrentLinkedQueue<JsonObject>()
    private var downloadDelayMs = 0L
    private var searchBody: () -> MockResponse = {
        MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
            .setBody("""{"error":"OpenSubtitles not configured"}""")
    }

    /** Stands in for the app scope the STOP report runs on; real threads, like the app's. */
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var progressDelayMs = 0L
    private var itemDelayMs = 0L

    /** Toasts clear on a real-time scope (see ShowViewModelTest). */
    private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toaster = Toaster(toastScope)

    private val strings = PlayerViewModel.Strings(
        playbackFailed = "Playback failed",
        subtitlesOff = "Off",
        audioDefault = "Default",
        nextEpisode = { "Next episode: $it" },
        previousEpisode = { "Previous episode: $it" },
        noNextEpisode = "No next episode",
        noPreviousEpisode = "No previous episode",
        delaySidecarOnly = "Delay works for subtitle files only",
        sidecarFailed = "Subtitle file could not be loaded",
        saveChoiceFailed = { "Could not save the track choice: $it" },
        audioUnsupported = "Audio track not supported on this TV",
    )

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val line = "${request.method} ${request.path}"
                requests += line
                return when (line) {
                    "GET /api/settings" -> settingsBody()
                    "GET /api/items/5" -> item5Body().setHeadersDelay(item5DelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    "POST /api/items/5/progress" -> {
                        progressPosts5 += Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        json("""{"sessionId":8,"watched":false}""")
                    }
                    "GET /api/items/4" -> itemBody().setHeadersDelay(itemDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    "POST /api/items/4/progress" -> {
                        progressPosts += Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        json("""{"sessionId":7,"watched":false}""")
                            .setHeadersDelay(progressDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    "POST /api/items/4/tracks" -> {
                        trackPosts += Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        json("""{"ok":true}""")
                    }
                    "GET /api/items/4/subtitles/search" -> searchBody()
                    "POST /api/items/4/subtitles/download" -> {
                        downloadPosts += Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                        json(DOWNLOADED).setHeadersDelay(downloadDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    "GET /api/items/4/subtitles" -> json("""{"tracks":[$EMBEDDED_3,$SIDECAR_EN,$DOWNLOADED]}""")
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
        exitScope.cancel()
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
        settings = SettingsRepo(api, ServerStore(NoopDataStore), backgroundScope)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PlayerViewModel(
                    api, settings, reporter, toaster, strings, itemId = 4, fromStart = fromStart, port = port,
                    exitScope = exitScope,
                    reconnect = { reconnects.incrementAndGet(); onReconnect() },
                ) as T
        }
        return ViewModelProvider(store, factory)[PlayerViewModel::class.java]
    }

    private suspend fun PlayerViewModel.playing(): UiState.Playing =
        state.first { it !is UiState.Loading } as UiState.Playing

    private fun PlayerViewModel.current(): UiState.Playing = state.value as UiState.Playing

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
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals("exactly one PAUSE report", 1, progressPosts.size)

        advanceTimeBy(10_000)
        assertTrue("overlay stays while paused", vm.current().overlayVisible)

        vm.togglePause()
        assertEquals("play", port.calls.last())
        assertFalse(vm.current().paused)
    }

    @Test fun stopAndExitAsksToLeaveAtOnceAndReportsOnTheAppScope() = playerTest {
        progressDelayMs = 1_000 // a slow server must not hold the user on the player
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.stopAndExit()
        assertEquals("pause", port.calls.last())
        assertTrue("exit requested before the server answered", vm.exitRequested.value)
        assertTrue(progressPosts.none { it["position"]!!.jsonPrimitive.double == 900.0 })

        // The report survives the view model.
        store.clear()
        awaitRealTime { progressPosts.any { it["position"]!!.jsonPrimitive.double == 900.0 } }

        vm.stopAndExit() // a second BACK does nothing more
        vm.onHostStopped() // and leaving the app afterwards sends no PAUSE report
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, progressPosts.size)
    }

    @Test fun stopAndExitWhileLoadingLeavesAndNeverStartsPlayback() = playerTest {
        itemDelayMs = 300
        val vm = vm()
        assertEquals(UiState.Loading, vm.state.value)
        vm.stopAndExit()
        assertTrue(vm.exitRequested.value)
        withContext(Dispatchers.Default) { delay(600) }
        runCurrent()
        assertTrue(port.calls.none { it == "prepare" || it == "play" })
        assertTrue(vm.exitRequested.value)
    }

    @Test fun aPauseByThePlayerItselfShowsPausedAndReportsOnce() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 820_000
        port.playing = false
        port.whenReady = false
        port.fire { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) }
        assertTrue(vm.current().paused)
        assertTrue(vm.current().overlayVisible)
        awaitRealTime { progressPosts.isNotEmpty() }
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, progressPosts.size)
        assertEquals(820.0, progressPosts.single()["position"]!!.jsonPrimitive.double, 0.0)

        advanceTimeBy(10_000)
        assertTrue("overlay stays while paused", vm.current().overlayVisible)

        vm.togglePause() // one OK resumes
        assertEquals("play", port.calls.last())
        assertFalse(vm.current().paused)
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
        vm.stopAndExit()
        assertTrue(vm.exitRequested.value)
    }

    @Test fun aPlayerErrorAfterBackIsIgnored() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.stopAndExit()
        port.fire { it.onPlayerError("Lost connection to the server") }
        assertTrue(vm.state.value is UiState.Playing)
        awaitRealTime { progressPosts.isNotEmpty() }
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals("only the STOP report", 1, progressPosts.size)
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

    private val reconnects = java.util.concurrent.atomic.AtomicInteger(0)
    private var onReconnect: () -> Unit = {}

    @Test fun anUnreachableServerOnTheFirstLoadShowsUnreachableWithTheBaseUrl() = playerTest {
        val live = api.baseUrl
        api.baseUrl = "http://127.0.0.1:1"
        val vm = vm()
        assertEquals(UiState.Unreachable("http://127.0.0.1:1"), vm.state.first { it !is UiState.Loading })
        assertEquals(0, reconnects.get())
        assertTrue(port.prepared.isEmpty())
        api.baseUrl = live
    }

    @Test fun retryReconnectsOnceThenLoadsTheItemAgainAndPlays() = playerTest {
        val live = api.baseUrl
        api.baseUrl = "http://127.0.0.1:1"
        val vm = vm()
        vm.state.first { it is UiState.Unreachable }
        onReconnect = { api.baseUrl = live }

        vm.retry()
        val s = vm.playing()

        assertEquals(1, reconnects.get())
        assertEquals(1, requests.count { it == "GET /api/items/4" })
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
        assertFalse(s.paused)
    }

    @Test fun aRealLoadErrorIsNotRetried() = playerTest {
        itemBody = { MockResponse().setResponseCode(404).setBody("""{"error":"Item not found"}""") }
        val vm = vm()
        vm.state.first { it is UiState.Error }
        vm.retry()
        assertTrue(vm.state.value is UiState.Error)
        assertEquals(0, reconnects.get())
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

    // Remote mapping (Task 9)

    private suspend fun PlayerViewModel.playingItem(id: Int): UiState.Playing =
        state.first { it is UiState.Playing && it.detail.item.id == id } as UiState.Playing

    private fun JsonObject.position() = this["position"]!!.jsonPrimitive.double

    @Test fun nextSavesTheOldItemThenPlaysTheNextFromItsSavedPositionInANewSession() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 760_000
        advanceTimeBy(501) // a first report opens session 7 for item 4
        awaitRealTime { progressPosts.isNotEmpty() }
        port.position = 900_000

        vm.next()
        val s = vm.playingItem(5)

        // The old item's position went out as a SWITCH report, in its session.
        // (Virtual time may run on while the test waits for HTTP, so more 760 s ticks may precede it.)
        awaitRealTime { progressPosts.any { it.position() == 900.0 } }
        val switch = progressPosts.single { it.position() == 900.0 }
        assertEquals(7, switch["sessionId"]!!.jsonPrimitive.double.toInt())

        // The next one plays from its saved position.
        assertEquals(listOf("4", "5"), port.prepared.map { it.mediaId })
        assertEquals(120_000L, port.seeks.last())
        assertEquals("play", port.calls.last())
        assertFalse(s.paused)
        assertFalse(s.overlayVisible)
        assertEquals("Half Loop", s.detail.item.title)
        assertEquals(5, vm.currentItemId)
        assertEquals("Next episode: 2. Half Loop", toaster.current.value)

        // And reports into a new session: the first report for item 5 carries no sessionId.
        advanceTimeBy(501)
        awaitRealTime { progressPosts5.isNotEmpty() }
        assertEquals("from item 5's own position", 120.0, progressPosts5.first().position(), 0.0)
        assertNull(progressPosts5.first()["sessionId"])
        assertEquals("item 4 gets nothing more", progressPosts.last(), switch)
    }

    @Test fun prevGoesBackToThePreviousEpisode() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        vm.next()
        vm.playingItem(5)
        vm.prev()
        val s = vm.state.first { it is UiState.Playing && it.detail.item.id == 4 && port.prepared.size == 3 } as UiState.Playing
        assertEquals(listOf("4", "5", "4"), port.prepared.map { it.mediaId })
        assertEquals(754_500L, port.seeks.last())
        assertEquals("Previous episode: 1. Good News About Hell", toaster.current.value)
        assertEquals(4, s.detail.item.id)
    }

    /** Review Focus 5. */
    @Test fun rapidNextPressesSwitchOnceAndKeepTheOldPosition() = playerTest {
        item5DelayMs = 300
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.next()
        vm.next()
        vm.next()
        vm.playingItem(5)
        withContext(Dispatchers.Default) { delay(500) }
        runCurrent()
        assertEquals(1, requests.count { it == "GET /api/items/5" })
        assertTrue(requests.none { it == "GET /api/items/6" })
        assertEquals(listOf("4", "5"), port.prepared.map { it.mediaId })
        assertEquals("one SWITCH report, at the position left", listOf(900.0), progressPosts.map { it.position() })
    }

    @Test fun nextWithoutANextEpisodeOnlyToasts() = playerTest {
        itemBody = { json(itemFixture(progress = null, next = null)) }
        val vm = vm()
        vm.playing() // duration unknown: no tick reports either
        val calls = port.calls.toList()
        vm.next()
        assertEquals("No next episode", toaster.current.value)
        vm.prev()
        assertEquals("No previous episode", toaster.current.value)
        withContext(Dispatchers.Default) { delay(200) }
        runCurrent()
        assertEquals(calls, port.calls)
        assertTrue(progressPosts.isEmpty())
        assertTrue(vm.state.value is UiState.Playing)
    }

    @Test fun backDuringASwitchLeavesWithoutStartingTheNextEpisode() = playerTest {
        item5DelayMs = 300
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.next()
        vm.onAction(PlayerAction.StopAndExit)
        assertTrue(vm.exitRequested.value)
        withContext(Dispatchers.Default) { delay(600) }
        runCurrent()
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
        awaitRealTime { progressPosts.size == 2 }
        assertEquals("SWITCH then STOP, both for item 4", listOf(900.0, 900.0), progressPosts.map { it.position() })
    }

    @Test fun skipsUseTheServerSettings() = playerTest {
        settingsBody = { json("""{"skipBackSeconds":5,"skipForwardSeconds":15,"autoplayNext":true}""") }
        val vm = vm()
        vm.playing()
        awaitRealTime { settings.settings.value.skipForwardSeconds == 15 }
        port.duration = 3_000_000
        port.position = 100_000
        vm.onAction(PlayerAction.SkipForward)
        assertEquals(115_000L, port.seeks.last())
        vm.onAction(PlayerAction.SkipBack)
        assertEquals(110_000L, port.seeks.last())
        assertTrue(vm.current().overlayVisible)
    }

    @Test fun skipsDefaultToTenSecondsWithoutServerSettings() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 100_000
        vm.onAction(PlayerAction.SkipForward)
        assertEquals(110_000L, port.seeks.last())
        vm.onAction(PlayerAction.SkipBack)
        assertEquals(100_000L, port.seeks.last())
    }

    /** Review Focus 2 (keys half): BACK hides a visible overlay first, then stops and saves the position. */
    @Test fun backHidesTheOverlayThenStopsAndSaves() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        fun back() = PlayerKeys.map(
            KeyEventLike(KeyCode.BACK, isDown = true, repeatCount = 0),
            overlayVisible = vm.current().overlayVisible, focusOnOverlay = true,
            countdownActive = vm.countdownActive.value,
        )!!.let(vm::onAction)
        vm.showOverlay()
        back()
        assertFalse(vm.current().overlayVisible)
        assertFalse(vm.exitRequested.value)
        back()
        assertTrue(vm.exitRequested.value)
        awaitRealTime { progressPosts.any { it.position() == 900.0 } }
    }

    @Test fun okTogglesPauseAndShowsTheOverlay() = playerTest {
        val vm = vm()
        vm.playing()
        vm.onAction(PlayerAction.TogglePause)
        assertTrue(vm.current().paused)
        assertTrue(vm.current().overlayVisible)
        assertFalse(vm.countdownActive.value)
    }

    // Review round 1

    @Test fun playKeyResumesWhenPausedAndOnlyShowsTheOverlayWhenPlaying() = playerTest {
        val vm = vm()
        vm.playing()
        val before = port.calls.size
        vm.onAction(PlayerAction.Play) // already playing
        assertEquals("no player call", before, port.calls.size)
        assertFalse(vm.current().paused)
        assertTrue(vm.current().overlayVisible)
        advanceTimeBy(4_001)
        assertFalse("hide timer runs while playing", vm.current().overlayVisible)

        vm.onAction(PlayerAction.Pause)
        vm.onAction(PlayerAction.Play) // paused: resumes and restarts the hide timer
        assertEquals("play", port.calls.last())
        assertFalse(vm.current().paused)
        advanceTimeBy(3_999)
        assertTrue(vm.current().overlayVisible)
        advanceTimeBy(2)
        assertFalse(vm.current().overlayVisible)
    }

    @Test fun pauseKeyPausesOnceAndIsANoOpWhenPaused() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 800_000
        vm.onAction(PlayerAction.Pause)
        assertEquals("pause", port.calls.last())
        assertTrue(vm.current().paused)
        assertTrue(vm.current().overlayVisible)
        val calls = port.calls.size
        vm.onAction(PlayerAction.Pause) // already paused: never resumes
        assertEquals(calls, port.calls.size)
        assertTrue(vm.current().paused)
        awaitRealTime { progressPosts.isNotEmpty() }
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals("one PAUSE report", listOf(800.0), progressPosts.map { it.position() })
    }

    @Test fun playbackKeysAreIgnoredWhileSwitching() = playerTest {
        item5DelayMs = 300
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.next()
        val calls = port.calls.toList()
        vm.onAction(PlayerAction.TogglePause)
        vm.onAction(PlayerAction.Play)
        vm.onAction(PlayerAction.Pause)
        vm.onAction(PlayerAction.SkipForward)
        vm.onAction(PlayerAction.SkipBack)
        vm.seekTo(10_000)
        assertEquals("the item being left stays paused where it was", calls, port.calls)
        vm.playingItem(5)
        assertEquals(listOf("4", "5"), port.prepared.map { it.mediaId })
    }

    @Test fun switchWithUnknownDurationStillReportsThePosition() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = null
        port.position = 42_000
        vm.next()
        vm.playingItem(5)
        awaitRealTime { progressPosts.isNotEmpty() }
        val switch = progressPosts.single()
        assertEquals(42.0, switch.position(), 0.0)
        assertEquals(0.0, switch["duration"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun aFailedSwitchStaysOnTheOldItemAndCanSwitchAgain() = playerTest {
        item5Body = { MockResponse().setResponseCode(500).setBody("""{"error":"Database is locked"}""") }
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        port.position = 900_000
        vm.next()
        awaitRealTime { toaster.current.value?.startsWith("Playback failed") == true }
        runCurrent()
        assertEquals(4, vm.currentItemId)
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
        assertEquals(4, vm.current().detail.item.id)

        vm.togglePause() // the old item resumes
        assertEquals("play", port.calls.last())
        assertFalse(vm.current().paused)

        item5Body = { json(item5Fixture()) }
        vm.next() // the switch flag was cleared
        vm.playingItem(5)
        assertEquals(2, requests.count { it == "GET /api/items/5" })
    }

    @Test fun switchToAWatchedEpisodeStartsAtZero() = playerTest {
        item5Body = { json(item5Fixture(watched = true)) }
        val vm = vm()
        vm.playing()
        vm.next()
        vm.playingItem(5)
        assertEquals(0L, port.seeks.last())
    }

    @Test fun backAfterASwitchStopsTheNewItem() = playerTest {
        val vm = vm()
        vm.playing()
        port.duration = 3_000_000
        vm.next()
        vm.playingItem(5)
        port.position = 200_000
        vm.onAction(PlayerAction.StopAndExit)
        assertTrue(vm.exitRequested.value)
        awaitRealTime { progressPosts5.any { it.position() == 200.0 } }
        assertTrue("nothing at the new position for the old item", progressPosts.none { it.position() == 200.0 })
    }

    @Test fun clearingTheViewModelReleasesThePlayer() = playerTest {
        vm().playing()
        store.clear()
        assertTrue(port.released)
    }

    // Autoplay next (Task 11)

    /** The player reached the end of item 4 (3000 s). */
    private fun endItem() {
        port.duration = 3_000_000
        port.position = 3_000_000
        port.playing = false
        port.state = Player.STATE_ENDED
        port.fire { it.onPlaybackStateChanged(Player.STATE_ENDED) }
    }

    private fun PlayerViewModel.back() = PlayerKeys.map(
        KeyEventLike(KeyCode.BACK, isDown = true, repeatCount = 0),
        overlayVisible = current().overlayVisible, focusOnOverlay = current().overlayVisible,
        countdownActive = countdownActive.value,
    )!!.let(::onAction)

    @Test fun endedWithANextEpisodeCountsDownTenSecondsThenPlaysIt() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        assertTrue(vm.countdownActive.value)
        assertEquals(10, vm.current().countdownSeconds)
        assertTrue(vm.current().paused)
        assertTrue(vm.current().ended)
        // (No real-time wait here: virtual time would run on through the countdown meanwhile.)

        advanceTimeBy(1_001)
        assertEquals(9, vm.current().countdownSeconds)
        advanceTimeBy(8_000)
        assertEquals(1, vm.current().countdownSeconds)
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
        assertTrue(requests.none { it == "GET /api/items/5" })

        advanceTimeBy(1_000)
        val s = vm.playingItem(5)
        assertFalse(vm.countdownActive.value)
        assertNull(s.countdownSeconds)
        assertFalse(s.ended)
        assertEquals(listOf("4", "5"), port.prepared.map { it.mediaId })
        assertEquals("play", port.calls.last())
        assertEquals("Next episode: 2. Half Loop", toaster.current.value)
        awaitRealTime { progressPosts.isNotEmpty() }
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals("only the STOP at the end for item 4, no SWITCH after it", listOf(3000.0), progressPosts.map { it.position() })
    }

    @Test fun backDuringTheCountdownCancelsItAndALaterTickDoesNotSwitch() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        advanceTimeBy(3_001)
        assertEquals(7, vm.current().countdownSeconds)
        vm.back()
        assertFalse(vm.countdownActive.value)
        assertNull(vm.current().countdownSeconds)
        assertTrue(vm.current().overlayVisible)
        assertFalse(vm.exitRequested.value)

        advanceTimeBy(20_000)
        runCurrent()
        withContext(Dispatchers.Default) { delay(200) }
        runCurrent()
        assertTrue(requests.none { it == "GET /api/items/5" })
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
        assertTrue("stays on the ended frame with the overlay up", vm.current().overlayVisible)
        assertTrue(vm.current().ended)
        assertFalse(vm.countdownActive.value)
        assertNull(vm.current().countdownSeconds)
        assertEquals(4, vm.currentItemId)
        assertEquals(4, vm.current().detail.item.id)
    }

    @Test fun autoplayOffShowsTheOverlayWithoutACountdown() = playerTest {
        settingsBody = { json("""{"skipBackSeconds":10,"skipForwardSeconds":10,"autoplayNext":false}""") }
        val vm = vm()
        vm.playing()
        awaitRealTime { !settings.settings.value.autoplayNext }
        endItem()
        assertFalse(vm.countdownActive.value)
        assertNull(vm.current().countdownSeconds)
        assertTrue(vm.current().overlayVisible)
        assertTrue(vm.current().ended)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(vm.current().overlayVisible)
        assertTrue(requests.none { it == "GET /api/items/5" })
        assertFalse(vm.countdownActive.value)
        assertEquals(4, vm.currentItemId)
        assertTrue(vm.current().ended)
    }

    @Test fun noNextEpisodeShowsTheOverlayWithoutACountdown() = playerTest {
        itemBody = { json(itemFixture(progress = null, next = null)) }
        val vm = vm()
        vm.playing()
        endItem()
        assertFalse(vm.countdownActive.value)
        assertTrue(vm.current().overlayVisible)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
        assertFalse(vm.countdownActive.value)
        assertNull(vm.current().countdownSeconds)
        assertEquals(4, vm.currentItemId)
        assertTrue(vm.current().overlayVisible)
    }

    /** Review Focus 2: BACK early in an autoplayed episode stops it, saves its position, and autoplays nothing. */
    @Test fun backSoonAfterAnAutoplayedEpisodeStopsItWithoutASecondAutoplay() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        advanceTimeBy(10_001)
        vm.playingItem(5)
        port.state = Player.STATE_READY
        port.position = 125_000
        assertFalse(vm.countdownActive.value)
        vm.back()
        assertTrue(vm.exitRequested.value)
        awaitRealTime { progressPosts5.any { it.position() == 125.0 } }

        advanceTimeBy(20_000)
        runCurrent()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(listOf("4", "5"), port.prepared.map { it.mediaId })
        assertTrue(requests.none { it == "GET /api/items/6" })
        assertEquals("one STOP for item 5", listOf(125.0), progressPosts5.map { it.position() })
        assertEquals("item 4 only got its end", listOf(3000.0), progressPosts.map { it.position() })
    }

    @Test fun okAfterACancelledCountdownReplaysFromTheStart() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        vm.back()
        assertTrue(vm.current().paused)
        vm.togglePause()
        assertEquals(listOf("seekTo(0)", "play"), port.calls.takeLast(2))
        assertFalse(vm.current().paused)
        assertFalse(vm.current().ended)
        assertEquals(0L, vm.current().positionMs)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
    }

    @Test fun nextDuringTheCountdownSwitchesOnceWithoutASwitchReport() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        advanceTimeBy(2_001)
        vm.next()
        vm.playingItem(5)
        advanceTimeBy(20_000)
        runCurrent()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, requests.count { it == "GET /api/items/5" })
        assertTrue(requests.none { it == "GET /api/items/6" })
        assertEquals(listOf(3000.0), progressPosts.map { it.position() })
    }

    @Test fun okDuringTheCountdownPlaysTheNextEpisodeNow() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        advanceTimeBy(2_001)
        PlayerKeys.map(
            KeyEventLike(KeyCode.DPAD_CENTER, isDown = true), overlayVisible = false, focusOnOverlay = false,
            countdownActive = vm.countdownActive.value,
        )!!.let(vm::onAction)
        assertFalse(vm.countdownActive.value)
        val s = vm.playingItem(5)
        assertTrue("no replay of the ended item", port.calls.none { it == "seekTo(0)" })
        assertEquals(listOf("4", "5"), port.prepared.map { it.mediaId })
        assertFalse(s.paused)
        assertEquals("Next episode: 2. Half Loop", toaster.current.value)
        advanceTimeBy(20_000)
        runCurrent()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, requests.count { it == "GET /api/items/5" })
        assertTrue(requests.none { it == "GET /api/items/6" })
        assertEquals(listOf(3000.0), progressPosts.map { it.position() })
    }

    @Test fun openingAMenuDuringTheCountdownCancelsIt() = playerTest {
        val vm = vm()
        vm.playing()
        endItem()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        assertFalse(vm.countdownActive.value)
        assertNull(vm.current().countdownSeconds)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(requests.none { it == "GET /api/items/5" })
        assertEquals(4, vm.currentItemId)
        assertEquals(PlayerViewModel.PlayerMenu.Subtitles, vm.current().menu)
    }

    @Test fun aFailedAutoplayLoadStaysOnTheEndWithTheOverlayUp() = playerTest {
        item5Body = { MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""") }
        val vm = vm()
        vm.playing()
        endItem()
        advanceTimeBy(10_001)
        awaitRealTime { toaster.current.value?.startsWith("Playback failed") == true }
        runCurrent()
        assertTrue(vm.current().overlayVisible)
        assertTrue(vm.current().ended)
        assertFalse(vm.countdownActive.value)
        assertEquals(4, vm.currentItemId)
        assertEquals(listOf("4"), port.prepared.map { it.mediaId })
    }

    @Test fun leavingTheAppDuringAnAutoplayLoadDoesNotPlayTheNextEpisodeUnseen() = playerTest {
        item5DelayMs = 300
        val vm = vm()
        vm.playing()
        endItem()
        advanceTimeBy(10_001)
        vm.onHostStopped()
        val s = vm.playingItem(5)
        assertTrue(s.paused)
        assertTrue(s.overlayVisible)
        assertFalse(port.whenReady)
        assertEquals("prepared and positioned, not played", "seekTo(120000)", port.calls.last())
        // OK on return plays it.
        vm.togglePause()
        assertEquals("play", port.calls.last())
        assertFalse(vm.current().paused)
    }

    // Subtitles, audio, sub delay, online search (Task 10)

    private fun subtitledItem(subtitleChoice: String? = null, audioChoice: String? = null) = {
        json(itemFixture(progress = """{"position":754.5,"duration":3000,"watched":false}""", subtitles = "[$EMBEDDED_3,$SIDECAR_EN]",
            subtitleChoice = subtitleChoice, audioChoice = audioChoice))
    }

    private fun track(group: Int, id: String?, default: Boolean = false) =
        PlayerTrack(groupIndex = group, id = id, language = null, label = null, isDefault = default, selected = false)

    /** The player learnt the item's tracks: the embedded subtitle, the sidecars in the MediaItem, two audio tracks. */
    private fun tracksArrive(vararg sidecars: String) {
        // Ids as ExoPlayer reports them for an item merged with sidecars: prefixed with the period index.
        port.textTracks = listOf(track(2, "0:4")) + sidecars.mapIndexed { i, id -> track(3 + i, "${i + 1}:$id") }
        port.audioTracks = listOf(track(1, "0:2"), track(5, "0:3"))
        port.fire { it.onTracksChanged() }
    }

    private val sidecarEn = "sidecar:Severance%20S01E01.en.srt"
    private val downloaded = "sidecar:Severance%20S01E01.os.srt"

    @Test fun loadConfiguresEverySidecarAndSelectsNoneWithoutAChoice() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        val s = vm.playing()
        // MediaItem URIs are null under unit tests (android.net.Uri is a stub), so the plan stands in for them.
        val configs = vm.currentPlan!!.subtitleConfigs
        assertEquals(listOf(sidecarEn), configs.map { it.id })
        assertEquals("application/x-subrip", configs.single().mimeType)
        assertEquals(server.url("/api/items/4/subtitles/sidecar/Severance%20S01E01.en.srt").toString(), configs.single().uri)
        assertFalse(configs.single().selected)
        assertEquals("off", s.subtitleChoice)
        assertEquals("Off", s.subtitleLabel)
        tracksArrive(sidecarEn)
        assertEquals("text off once the tracks are known", listOf<Int?>(null), port.textSelections)
    }

    @Test fun aStoredSidecarChoiceIsRestoredOnLoad() = playerTest {
        itemBody = subtitledItem(subtitleChoice = "sidecar:Severance%20S01E01.en.srt", audioChoice = "audio:2")
        val vm = vm()
        val s = vm.playing()
        assertTrue(vm.currentPlan!!.subtitleConfigs.single().selected)
        assertEquals(sidecarEn, s.subtitleChoice)
        assertEquals("English", s.subtitleLabel)
        assertEquals("audio:2", s.audioChoice)
        tracksArrive(sidecarEn)
        assertEquals(listOf<Int?>(3), port.textSelections)
        assertEquals(listOf(5), port.audioSelections)
        assertTrue("restoring is not a new choice", trackPosts.isEmpty())
    }

    @Test fun aStoredEmbeddedChoiceIsSelectedByStreamOrderOnceTracksAreKnown() = playerTest {
        itemBody = subtitledItem(subtitleChoice = "embedded:3")
        val vm = vm()
        assertEquals("embedded:3", vm.playing().subtitleChoice)
        assertTrue(port.textSelections.isEmpty())
        tracksArrive(sidecarEn)
        assertEquals(listOf<Int?>(2), port.textSelections)
        port.fire { it.onTracksChanged() }
        assertEquals("applied once", 1, port.textSelections.size)
    }

    @Test fun withoutAChoiceThePlayersDefaultEmbeddedTrackIsSelected() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        port.textTracks = listOf(track(2, "3", default = true), track(3, sidecarEn))
        port.fire { it.onTracksChanged() }
        assertEquals(listOf<Int?>(2), port.textSelections)
        assertEquals("embedded:3", vm.current().subtitleChoice)
        assertEquals("English", vm.current().subtitleLabel)
    }

    @Test fun selectSubtitleSidecarRepreparesAtThePositionAndPersists() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        tracksArrive(sidecarEn)
        port.duration = 3_000_000
        port.position = 812_000
        val before = port.calls.size
        vm.selectSubtitle(sidecarEn)
        assertEquals(listOf("prepare", "play"), port.calls.drop(before))
        assertEquals(812_000L, port.preparedAt.last())
        assertTrue("no seek to the start", port.seeks.none { it == 0L })
        assertEquals(sidecarEn, vm.currentPlan!!.subtitleConfigs.single { it.selected }.id)
        assertEquals(server.url("/api/items/4/subtitles/sidecar/Severance%20S01E01.en.srt").toString(), vm.currentPlan!!.selectedSidecarUri)
        assertEquals(sidecarEn, vm.current().subtitleChoice)
        assertEquals("English", vm.current().subtitleLabel)
        assertEquals(4, vm.currentItemId)

        tracksArrive(sidecarEn) // the re-prepared item's tracks
        assertEquals(3, port.textSelections.last())
        awaitRealTime { trackPosts.isNotEmpty() }
        val body = trackPosts.single()
        assertEquals(sidecarEn, body["subtitle"]!!.jsonPrimitive.content)
        assertEquals("audio:1", body["audio"]!!.jsonPrimitive.content)
    }

    @Test fun selectSubtitleWhilePausedStaysPaused() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.togglePause()
        val before = port.calls.size
        vm.selectSubtitle(sidecarEn)
        assertEquals(listOf("prepare"), port.calls.drop(before))
        assertTrue(vm.current().paused)
    }

    @Test fun selectEmbeddedOrOffOnlyChangesTheTrackSelection() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        tracksArrive(sidecarEn)
        vm.selectSubtitle("embedded:3")
        assertEquals(2, port.textSelections.last())
        assertEquals("English", vm.current().subtitleLabel)
        vm.selectSubtitle("off")
        assertNull(port.textSelections.last())
        assertEquals("Off", vm.current().subtitleLabel)
        assertEquals("never re-prepared", 1, port.prepared.size)
        awaitRealTime { trackPosts.size == 2 }
        assertEquals(listOf("embedded:3", "off"), trackPosts.map { it["subtitle"]!!.jsonPrimitive.content })
    }

    @Test fun adjustSubDelayRebuildsTheSidecarWithTheShift() = playerTest {
        itemBody = subtitledItem(subtitleChoice = sidecarEn)
        val vm = vm()
        vm.playing()
        port.position = 900_000
        vm.adjustSubDelay(+500)
        assertEquals("the label follows at once", 500, vm.current().subDelayMs)
        assertEquals("not yet re-prepared", 1, port.prepared.size)
        advanceTimeBy(PlayerViewModel.SUB_DELAY_DEBOUNCE_MS + 1)
        assertEquals(2, port.prepared.size)
        assertEquals(900_000L, port.preparedAt.last())
        assertTrue(vm.currentPlan!!.selectedSidecarUri!!.endsWith("?shift=500"))
        vm.adjustSubDelay(-500)
        advanceTimeBy(PlayerViewModel.SUB_DELAY_DEBOUNCE_MS + 1)
        assertEquals(0, vm.current().subDelayMs)
        assertFalse(vm.currentPlan!!.selectedSidecarUri!!.contains("shift"))
        assertEquals(3, port.prepared.size)
    }

    @Test fun quickSubDelayPressesReprepareOnce() = playerTest {
        itemBody = subtitledItem(subtitleChoice = sidecarEn)
        val vm = vm()
        vm.playing()
        repeat(3) {
            vm.adjustSubDelay(+500)
            advanceTimeBy(100)
        }
        assertEquals(1500, vm.current().subDelayMs)
        assertEquals(1, port.prepared.size)
        advanceTimeBy(PlayerViewModel.SUB_DELAY_DEBOUNCE_MS)
        assertEquals(2, port.prepared.size)
        assertTrue(vm.currentPlan!!.selectedSidecarUri!!.endsWith("?shift=1500"))
    }

    @Test fun okOnSubDelayWithASidecarDoesNothing() = playerTest {
        itemBody = subtitledItem(subtitleChoice = sidecarEn)
        val vm = vm()
        vm.playing()
        vm.adjustSubDelay(0)
        advanceTimeBy(PlayerViewModel.SUB_DELAY_DEBOUNCE_MS + 1)
        assertEquals(1, port.prepared.size)
        assertNull(toaster.current.value)
    }

    @Test fun aFailedSidecarIsDroppedAndPlaybackGoesOnWithSubtitlesOff() = playerTest {
        itemBody = subtitledItem(subtitleChoice = sidecarEn)
        val vm = vm()
        vm.playing()
        port.position = 640_000
        val uri = vm.currentPlan!!.selectedSidecarUri!!
        port.fire { it.onSidecarFailed(uri) }
        val s = vm.state.value
        assertTrue("no error screen", s is UiState.Playing)
        s as UiState.Playing
        assertEquals("off", s.subtitleChoice)
        assertEquals("Off", s.subtitleLabel)
        assertEquals(listOf("embedded:3"), vm.subtitleTracks.map { it.id })
        assertTrue(vm.currentPlan!!.subtitleConfigs.isEmpty())
        assertEquals("re-prepared at the position", 640_000L, port.preparedAt.last())
        assertEquals("play", port.calls.last())
        assertEquals("Subtitle file could not be loaded", toaster.current.value)
        tracksArrive()
        assertNull(port.textSelections.last())
        assertTrue("the stored choice is left alone", trackPosts.isEmpty())
    }

    @Test fun aFailedSidecarThatIsNotChosenKeepsTheChoice() = playerTest {
        itemBody = subtitledItem(subtitleChoice = "embedded:3")
        val vm = vm()
        vm.playing()
        val uri = vm.currentPlan!!.subtitleConfigs.single().uri
        port.fire { it.onSidecarFailed(uri) }
        assertEquals("embedded:3", vm.current().subtitleChoice)
        assertTrue(vm.currentPlan!!.subtitleConfigs.isEmpty())
        tracksArrive()
        assertEquals(2, port.textSelections.last())
    }

    @Test fun aFailureForAnUnknownUriIsIgnored() = playerTest {
        itemBody = subtitledItem(subtitleChoice = sidecarEn)
        val vm = vm()
        vm.playing()
        port.fire { it.onSidecarFailed("http://elsewhere/api/items/9/subtitles/sidecar/x.srt") }
        assertEquals(sidecarEn, vm.current().subtitleChoice)
        assertEquals(1, port.prepared.size)
    }

    @Test fun adjustSubDelayIsIgnoredForEmbeddedTracks() = playerTest {
        itemBody = subtitledItem(subtitleChoice = "embedded:3")
        val vm = vm()
        vm.playing()
        vm.adjustSubDelay(+500)
        assertEquals(0, vm.current().subDelayMs)
        assertEquals(1, port.prepared.size)
        assertEquals("Delay works for subtitle files only", toaster.current.value)
    }

    @Test fun theSubDelayResetsOnTheNextItem() = playerTest {
        itemBody = subtitledItem(subtitleChoice = sidecarEn)
        val vm = vm()
        vm.playing()
        vm.adjustSubDelay(+500)
        vm.next()
        val s = vm.playingItem(5)
        assertEquals(0, s.subDelayMs)
        assertEquals("off", s.subtitleChoice)
    }

    @Test fun selectAudioSelectsTheGroupAndPersists() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        tracksArrive(sidecarEn)
        vm.selectAudio("audio:2")
        assertEquals(5, port.audioSelections.last())
        assertEquals("audio:2", vm.current().audioChoice)
        awaitRealTime { trackPosts.isNotEmpty() }
        assertEquals("audio:2", trackPosts.single()["audio"]!!.jsonPrimitive.content)
        assertEquals("off", trackPosts.single()["subtitle"]!!.jsonPrimitive.content)
    }

    @Test fun withoutAStoredAudioChoiceThePlayersSelectionStands() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        port.textTracks = listOf(track(2, "0:4"))
        port.audioTracks = listOf(track(1, "0:2"), track(5, "0:3").copy(selected = true))
        port.fire { it.onTracksChanged() }
        assertTrue("no audio override", port.audioSelections.isEmpty())
        // The label and the menu's tick follow the track the player chose.
        assertEquals("audio:2", vm.current().audioChoice)
        assertEquals("GER · ac3", vm.current().audioLabel)
    }

    @Test fun aStoredChoiceOfAnUnsupportedAudioTrackIsNotForced() = playerTest {
        itemBody = subtitledItem(audioChoice = "audio:2")
        val vm = vm()
        vm.playing()
        port.textTracks = listOf(track(2, "0:4"))
        port.audioTracks = listOf(track(1, "0:2").copy(selected = true), track(5, "0:3").copy(supported = false))
        port.fire { it.onTracksChanged() }
        assertTrue("no audio override", port.audioSelections.isEmpty())
        assertEquals("Audio track not supported on this TV", toaster.current.value)
        assertEquals("audio:1", vm.current().audioChoice)
    }

    @Test fun choosingAnUnsupportedAudioTrackOnlyToasts() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        port.textTracks = listOf(track(2, "0:4"))
        port.audioTracks = listOf(track(1, "0:2").copy(selected = true), track(5, "0:3").copy(supported = false))
        port.fire { it.onTracksChanged() }
        vm.selectAudio("audio:2")
        assertTrue(port.audioSelections.isEmpty())
        assertEquals("Audio track not supported on this TV", toaster.current.value)
        assertEquals("audio:1", vm.current().audioChoice)
        assertTrue("nothing saved", trackPosts.isEmpty())
    }

    @Test fun aSubtitleReprepareDoesNotForceAudioWithoutAChoice() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        tracksArrive(sidecarEn)
        vm.selectSubtitle(sidecarEn)
        tracksArrive(sidecarEn)
        assertTrue(port.audioSelections.isEmpty())
    }

    @Test fun searchOnlineShowsTheServersMessage() = playerTest {
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.searchOnline()
        assertEquals(PlayerViewModel.OnlineState.Searching, vm.current().online)
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Error }
        assertEquals("OpenSubtitles not configured", (vm.current().online as PlayerViewModel.OnlineState.Error).message)
    }

    @Test fun searchOnlineListsTheResultsAsReturned() = playerTest {
        searchBody = { json("""{"results":[$OS_RESULT,{"fileId":7,"release":"Other","hashMatch":false,"downloads":3,"language":"en"}]}""") }
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.searchOnline()
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Results }
        val r = vm.current().online as PlayerViewModel.OnlineState.Results
        assertEquals(listOf(42L, 7L), r.results.map { it.fileId })
        assertNull(r.applied)
    }

    @Test fun downloadAppliesTheNewSidecarAndRefreshesTheTracks() = playerTest {
        searchBody = { json("""{"results":[$OS_RESULT]}""") }
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        vm.searchOnline()
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Results }
        port.position = 700_000
        vm.download(42)
        awaitRealTime { (vm.current().online as? PlayerViewModel.OnlineState.Results)?.applied != null }
        assertEquals(42L, downloadPosts.single()["fileId"]!!.jsonPrimitive.content.toLong())
        assertTrue(requests.contains("GET /api/items/4/subtitles"))
        val s = vm.current()
        assertEquals(listOf("embedded:3", sidecarEn, downloaded), vm.subtitleTracks.map { it.id })
        assertEquals(downloaded, s.subtitleChoice)
        assertEquals("OpenSubtitles", (s.online as PlayerViewModel.OnlineState.Results).applied)
        assertEquals("the menu stays open", PlayerViewModel.PlayerMenu.Subtitles, s.menu)
        assertEquals(700_000L, port.preparedAt.last())
        assertEquals(listOf(sidecarEn, downloaded), vm.currentPlan!!.subtitleConfigs.map { it.id })
        assertEquals(downloaded, vm.currentPlan!!.subtitleConfigs.single { it.selected }.id)
        awaitRealTime { trackPosts.isNotEmpty() }
        assertEquals(downloaded, trackPosts.single()["subtitle"]!!.jsonPrimitive.content)
    }

    @Test fun aSecondDownloadWhileOneIsInFlightIsIgnored() = playerTest {
        searchBody = { json("""{"results":[$OS_RESULT]}""") }
        downloadDelayMs = 300
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        vm.searchOnline()
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Results }
        vm.download(42)
        vm.download(42)
        awaitRealTime { vm.current().subtitleChoice == downloaded }
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(1, downloadPosts.size)
    }

    @Test fun aDownloadFinishesAndAppliesAfterTheMenuIsClosed() = playerTest {
        searchBody = { json("""{"results":[$OS_RESULT]}""") }
        downloadDelayMs = 300
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        vm.searchOnline()
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Results }
        vm.download(42)
        vm.closeMenu()
        awaitRealTime { vm.current().subtitleChoice == downloaded }
        assertNull(vm.current().menu)
        assertEquals(PlayerViewModel.OnlineState.Idle, vm.current().online)
        awaitRealTime { trackPosts.isNotEmpty() }
    }

    @Test fun switchingEpisodesCancelsADownloadInFlight() = playerTest {
        searchBody = { json("""{"results":[$OS_RESULT]}""") }
        downloadDelayMs = 1_000
        itemBody = subtitledItem()
        val vm = vm()
        vm.playing()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        vm.searchOnline()
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Results }
        vm.download(42)
        awaitRealTime { downloadPosts.isNotEmpty() }
        vm.closeMenu()

        vm.next()
        vm.playingItem(5)
        // Not blocked by the old item's download: it was cancelled with the switch.
        vm.download(43)
        awaitRealTime { requests.contains("POST /api/items/5/subtitles/download") }
        withContext(Dispatchers.Default) { delay(1_200) }
        assertFalse("the cancelled download never refreshes item 4's tracks", requests.contains("GET /api/items/4/subtitles"))
        assertEquals("off", vm.current().subtitleChoice)
    }

    @Test fun anOpenMenuKeepsTheOverlayUpAndClosingRestartsTheHideTimer() = playerTest {
        val vm = vm()
        vm.playing()
        vm.showOverlay()
        vm.openMenu(PlayerViewModel.PlayerMenu.Audio)
        vm.onAction(PlayerAction.PassThrough) // moving through the rows
        advanceTimeBy(10_000)
        assertTrue(vm.current().overlayVisible)
        assertEquals("still playing", false, vm.current().paused)
        vm.onAction(PlayerAction.CloseMenu)
        assertNull(vm.current().menu)
        advanceTimeBy(PlayerViewModel.OVERLAY_HIDE_MS + 1)
        assertFalse(vm.current().overlayVisible)
    }

    @Test fun openingTheSubtitleMenuStartsWithTheList() = playerTest {
        val vm = vm()
        vm.playing()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        vm.searchOnline()
        awaitRealTime { vm.current().online is PlayerViewModel.OnlineState.Error }
        vm.closeMenu()
        vm.openMenu(PlayerViewModel.PlayerMenu.Subtitles)
        assertEquals(PlayerViewModel.OnlineState.Idle, vm.current().online)
    }
}

private const val NEXT_5 =
    """{"id":5,"type":"episode","category":"tv","title":"Half Loop","showId":3,"showTitle":"Severance","season":1,"episode":2,"addedAt":1}"""

private const val EMBEDDED_3 = """{"id":"embedded:3","label":"English","lang":"eng","url":null,"format":"embedded"}"""
private const val SIDECAR_EN =
    """{"id":"sidecar:Severance%20S01E01.en.srt","label":"English","lang":"en","url":"/api/items/4/subtitles/sidecar/Severance%20S01E01.en.srt","format":"srt"}"""
private const val DOWNLOADED =
    """{"id":"sidecar:Severance%20S01E01.os.srt","label":"OpenSubtitles","lang":"en","url":"/api/items/4/subtitles/sidecar/Severance%20S01E01.os.srt","format":"srt"}"""
private const val OS_RESULT = """{"fileId":42,"release":"Severance.S01E01.WEB","hashMatch":true,"downloads":1234,"language":"en"}"""

private fun itemFixture(
    progress: String?,
    next: String? = NEXT_5,
    subtitles: String = "[]",
    subtitleChoice: String? = null,
    audioChoice: String? = null,
): String = """
{"item":{"id":4,"type":"episode","category":"tv","title":"Good News About Hell","showId":3,"showTitle":"Severance",
 "season":1,"episode":1,"progress":${progress ?: "null"},"addedAt":1},
 "streamUrl":"/api/items/4/stream","fileName":"Severance S01E01.mkv","fileSize":880485,
 "audio":[{"index":1,"codec":"aac","language":"eng","default":true,"channels":2},{"index":2,"codec":"ac3","language":"ger","channels":6}],
 "subtitles":$subtitles,
 "next":${next ?: "null"},
 "prev":null,
 "subtitleChoice":${subtitleChoice?.let { "\"$it\"" } ?: "null"},
 "audioChoice":${audioChoice?.let { "\"$it\"" } ?: "null"}}
""".trimIndent()

private fun item5Fixture(watched: Boolean = false) = """
{"item":{"id":5,"type":"episode","category":"tv","title":"Half Loop","showId":3,"showTitle":"Severance",
 "season":1,"episode":2,"progress":{"position":120,"duration":3000,"watched":$watched},"addedAt":1},
 "streamUrl":"/api/items/5/stream","fileName":"Severance S01E02.mkv","fileSize":880485,
 "audio":[],"subtitles":[],
 "next":{"id":6,"type":"episode","category":"tv","title":"In Perpetuity","showId":3,"showTitle":"Severance","season":1,"episode":3,"addedAt":1},
 "prev":{"id":4,"type":"episode","category":"tv","title":"Good News About Hell","showId":3,"showTitle":"Severance","season":1,"episode":1,"addedAt":1}}
""".trimIndent()

/** A preferences store that holds nothing (the player never writes settings). */
private object NoopDataStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
    override suspend fun updateData(
        transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ) = androidx.datastore.preferences.core.emptyPreferences()
}
