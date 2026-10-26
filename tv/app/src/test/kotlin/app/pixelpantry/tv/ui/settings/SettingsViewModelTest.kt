package app.pixelpantry.tv.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.Settings
import app.pixelpantry.tv.server.ServerLocator
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dir: File = Files.createTempDirectory("pantry-settings-vm").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ServerStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pantry.preferences_pb") })
    private val toastScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var toaster: Toaster
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var repo: SettingsRepo

    /** PUT /api/settings bodies, in order. */
    private val puts = ConcurrentLinkedQueue<String>()
    private val infoCalls = ConcurrentLinkedQueue<String>()
    @Volatile private var current = Settings(skipBackSeconds = 115, skipForwardSeconds = 5, autoplayNext = true)
    @Volatile private var failPut = false

    private val strings = SettingsViewModel.Strings(
        connected = { "Connected to $it" },
        noServerFound = "No server found",
        invalidAddress = "Enter host:port",
        unreachable = { "Cannot reach $it" },
        saveFailed = { "Could not save: $it" },
    )

    private class FakeLocator(vararg results: ServerResolver.Result) : ServerLocator {
        private val queue = ArrayDeque(results.toList())
        @Volatile var calls = 0
        override suspend fun resolve(): ServerResolver.Result { calls++; return queue.removeFirst() }
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        toaster = Toaster(toastScope)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.path}") {
                "GET /api/settings" -> json(encode(current))
                "PUT /api/settings" -> {
                    val body = request.body.readUtf8()
                    puts += body
                    if (failPut) {
                        MockResponse().setResponseCode(500).setBody("""{"error":"disk full"}""")
                    } else {
                        val patch = ApiClient.defaultJson.decodeFromString(app.pixelpantry.tv.api.SettingsPatch.serializer(), body)
                        current = Settings(
                            patch.skipBackSeconds ?: current.skipBackSeconds,
                            patch.skipForwardSeconds ?: current.skipForwardSeconds,
                            patch.autoplayNext ?: current.autoplayNext,
                        )
                        json(encode(current))
                    }
                }
                "GET /api/info" -> { infoCalls += request.requestUrl.toString(); json("""{"name":"Pixel Pantry","version":"1"}""") }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        api = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = "http://10.0.0.5:8765" }
        repo = SettingsRepo(api, store, scope)
    }

    /** View models made by [vm]; their scopes (which collect the store) are cancelled before Main is reset. */
    private val vms = mutableListOf<SettingsViewModel>()

    @After fun tearDown() {
        extraServers.forEach { runCatching { it.shutdown() } }
        vms.forEach { it.viewModelScope.cancel() }
        scope.cancel()
        toastScope.cancel()
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
        dir.deleteRecursively()
    }

    private val serverUrl get() = server.url("/").toString().trimEnd('/')
    private val serverAddress get() = "${server.hostName}:${server.port}"

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun encode(s: Settings) = ApiClient.defaultJson.encodeToString(Settings.serializer(), s)

    private suspend fun awaitRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    /** A view model talking to the mock server, with the server's settings loaded. */
    private suspend fun vm(locator: ServerLocator = FakeLocator()): SettingsViewModel {
        api.baseUrl = serverUrl
        val vm = SettingsViewModel(repo, store, locator, api, toaster, strings, version = "0.1.0").also { vms += it }
        awaitRealTime { vm.settings.value == current }
        return vm
    }

    @Test fun exposesSettingsAddressAndVersion() = runTest {
        val vm = vm()
        assertEquals(Settings(115, 5, true), vm.settings.value)
        assertEquals(serverUrl, vm.serverAddress.value)
        assertEquals("0.1.0", vm.version)
    }

    /** Lets the save debounce elapse (virtual time) and waits for [count] PUTs to arrive (real time). */
    private suspend fun TestScope.flushSaves(count: Int) {
        advanceTimeBy(SettingsViewModel.SAVE_DEBOUNCE_MS + 1)
        runCurrent()
        awaitRealTime { puts.size == count }
    }

    @Test fun stepperClampsTo5To120AndPutsOnlyTheChangedKey() = runTest {
        val vm = vm()
        vm.adjustSkipBack(+5)
        assertEquals("shown at once", 120, vm.settings.value.skipBackSeconds)
        assertTrue("not saved before the debounce", puts.isEmpty())
        flushSaves(1)
        assertEquals(listOf("""{"skipBackSeconds":120}"""), puts.toList())

        vm.adjustSkipBack(+5) // already at the top: nothing to save
        vm.adjustSkipForward(-5) // already at the bottom
        advanceTimeBy(1_000)
        runCurrent()
        delay(100)
        assertEquals(1, puts.size)

        vm.adjustSkipForward(+5)
        flushSaves(2)
        assertEquals("""{"skipForwardSeconds":10}""", puts.last())
        awaitRealTime { vm.settings.value == Settings(120, 10, true) }
    }

    @Test fun rapidPressesShowAtOnceAndSaveOnceWithTheLatestValue() = runTest {
        val vm = vm()
        vm.adjustSkipForward(+5)
        advanceTimeBy(200)
        vm.adjustSkipForward(+5)
        advanceTimeBy(200)
        vm.adjustSkipForward(+5)
        assertEquals(20, vm.settings.value.skipForwardSeconds)
        advanceTimeBy(SettingsViewModel.SAVE_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue("still within the debounce of the last press", puts.isEmpty())
        flushSaves(1)
        assertEquals(listOf("""{"skipForwardSeconds":20}"""), puts.toList())
        awaitRealTime { current.skipForwardSeconds == 20 }
        assertEquals(20, vm.settings.value.skipForwardSeconds)
    }

    @Test fun toggleAutoplayPutsOnlyAutoplay() = runTest {
        val vm = vm()
        vm.toggleAutoplay()
        awaitRealTime { !vm.settings.value.autoplayNext }
        assertEquals(listOf("""{"autoplayNext":false}"""), puts.toList())
    }

    @Test fun failedSaveToastsAndKeepsTheOldValue() = runTest {
        val vm = vm()
        failPut = true
        vm.adjustSkipBack(-5)
        assertEquals(110, vm.settings.value.skipBackSeconds)
        flushSaves(1)
        awaitRealTime { toaster.current.value != null }
        assertEquals("Could not save: disk full", toaster.current.value)
        awaitRealTime { vm.settings.value.skipBackSeconds == 115 }
        assertEquals(listOf("""{"skipBackSeconds":110}"""), puts.toList())
    }

    @Test fun manualAddressIsValidatedProbedStoredAndUsed() = runTest {
        val vm = vm()
        vm.startEditingAddress()
        assertTrue(vm.editingAddress.value)
        vm.setManualAddress("not an address")
        assertEquals("Enter host:port", toaster.current.value)
        assertTrue(infoCalls.isEmpty())
        assertTrue(vm.editingAddress.value)

        api.baseUrl = "http://10.0.0.5:8765"
        vm.setManualAddress(" $serverAddress ")
        awaitRealTime { vm.manualAddress.value == serverAddress }
        assertEquals(serverAddress, store.manualAddress.first())
        assertEquals(serverUrl, api.baseUrl)
        assertEquals(serverUrl, vm.serverAddress.value)
        assertEquals("Connected to $serverAddress", toaster.current.value)
        assertFalse(vm.editingAddress.value)
        assertEquals(1, infoCalls.size)
    }

    @Test fun unreachableManualAddressKeepsTheCurrentServer() = runTest {
        val vm = vm()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
        }
        vm.setManualAddress(serverAddress)
        awaitRealTime { toaster.current.value != null }
        assertEquals("Cannot reach $serverAddress", toaster.current.value)
        assertNull(store.manualAddress.first())
        assertEquals(serverUrl, api.baseUrl)
    }

    @Test fun automaticDiscoveryClearsTheManualAddressAndReResolves() = runTest {
        store.setManualAddress("10.0.0.9:8765")
        val locator = FakeLocator(ServerResolver.Result.Found(serverUrl, ServerResolver.Source.MDNS))
        val vm = vm(locator)
        awaitRealTime { vm.manualAddress.value == "10.0.0.9:8765" }
        api.baseUrl = "http://10.0.0.9:8765"
        vm.useAutomaticDiscovery()
        awaitRealTime { locator.calls == 1 && vm.serverAddress.value == serverUrl }
        assertNull(store.manualAddress.first())
        awaitRealTime { vm.manualAddress.value == null }
        assertEquals(serverUrl, api.baseUrl)
        assertEquals("Connected to $serverAddress", toaster.current.value)
    }

    @Test fun automaticDiscoveryWhoseResolverFailsKeepsTheCurrentAddress() = runTest {
        store.setManualAddress(serverAddress)
        val vm = vm(ServerLocator { throw java.io.IOException("DataStore read failed") })
        awaitRealTime { vm.manualAddress.value == serverAddress }
        vm.useAutomaticDiscovery()
        awaitRealTime { toaster.current.value != null }
        assertEquals("No server found", toaster.current.value)
        assertEquals("the manual address is put back", serverAddress, store.manualAddress.first())
        assertEquals(serverUrl, api.baseUrl)
    }

    @Test fun automaticDiscoveryFindingNothingKeepsTheCurrentAddress() = runTest {
        store.setManualAddress(serverAddress)
        val locator = FakeLocator(ServerResolver.Result.NotFound(listOf("http://10.0.0.20:8765")))
        val vm = vm(locator)
        awaitRealTime { vm.manualAddress.value == serverAddress }
        vm.useAutomaticDiscovery()
        awaitRealTime { toaster.current.value != null }
        assertEquals("No server found", toaster.current.value)
        assertEquals(1, locator.calls)
        assertEquals("the manual address is put back", serverAddress, store.manualAddress.first())
        awaitRealTime { vm.manualAddress.value == serverAddress }
        assertEquals(serverUrl, api.baseUrl)
        assertEquals(serverUrl, vm.serverAddress.value)
    }

    /** A second server that answers /api/info after [delayMs], with [code] (200 = a valid Pixel Pantry reply). */
    private fun slowServer(code: Int, delayMs: Long = 500): MockWebServer = MockWebServer().also { slow ->
        slow.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val r = if (code == 200) json("""{"name":"Pixel Pantry","version":"1"}""") else MockResponse().setResponseCode(code)
                return r.setHeadersDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        slow.start()
        extraServers += slow
    }

    private val extraServers = mutableListOf<MockWebServer>()

    @Test fun overlappingFailingProbesNeverMoveTheBaseUrl() = runTest {
        val locator = FakeLocator()
        val vm = vm(locator)
        val slow = slowServer(404)
        val slowAddress = "${slow.hostName}:${slow.port}"

        // Two raw probes at once against a failing server, as Connect + Settings could start them.
        val probe = ServerResolver.apiProbe(api)
        val results = withContext(Dispatchers.Default) {
            listOf(async { probe("http://$slowAddress") }, async { probe("http://$slowAddress") }).awaitAll()
        }
        assertEquals(listOf(false, false), results)
        assertEquals(serverUrl, api.baseUrl)

        // In Settings: while one probe runs, further address actions are ignored and other requests still go
        // to the current server.
        vm.setManualAddress(slowAddress)
        assertTrue(vm.connecting.value)
        vm.setManualAddress(slowAddress)
        vm.useAutomaticDiscovery()
        vm.adjustSkipForward(+5)
        flushSaves(1)
        assertEquals("""{"skipForwardSeconds":10}""", puts.single())
        awaitRealTime { toaster.current.value == "Cannot reach $slowAddress" }
        awaitRealTime { !vm.connecting.value }
        assertEquals(3, slow.requestCount) // two raw probes + one from Settings
        assertEquals(0, locator.calls)
        assertEquals(serverUrl, api.baseUrl)
        assertEquals(serverUrl, vm.serverAddress.value)
        assertNull(store.manualAddress.first())
    }

    @Test fun cancelledProbeThatLaterSucceedsDoesNotSwitchServers() = runTest {
        val vm = vm()
        val slow = slowServer(200)
        val slowAddress = "${slow.hostName}:${slow.port}"
        vm.startEditingAddress()
        vm.setManualAddress(slowAddress)
        assertTrue(vm.connecting.value)
        awaitRealTime { slow.requestCount == 1 } // the probe is in flight
        vm.cancelEditingAddress()
        assertFalse(vm.connecting.value)
        assertFalse(vm.editingAddress.value)
        delay(800) // the probe's reply (a valid server) arrives after the cancel
        assertEquals(serverUrl, api.baseUrl)
        assertEquals(serverUrl, vm.serverAddress.value)
        assertNull(store.manualAddress.first())
        assertNull(toaster.current.value)
    }
}
