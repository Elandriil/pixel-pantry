package app.pixelpantry.tv.ui.connect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.server.ServerLocator
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.ui.connect.ConnectViewModel.ConnectError
import app.pixelpantry.tv.ui.connect.ConnectViewModel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val dir: File = Files.createTempDirectory("pantry-connect").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ServerStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pantry.preferences_pb") })
    private val api = ApiClient(OkHttpClient(), ApiClient.defaultJson)
    private lateinit var server: MockWebServer

    /** Answers each resolve() with the next queued result; counts calls. */
    private class FakeLocator(vararg results: ServerResolver.Result) : ServerLocator {
        private val queue = ArrayDeque(results.toList())
        var calls = 0
        override suspend fun resolve(): ServerResolver.Result { calls++; return queue.removeFirst() }
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
        scope.cancel()
        dir.deleteRecursively()
    }

    private val serverAddress get() = "${server.hostName}:${server.port}"
    private val notFound = ServerResolver.Result.NotFound(listOf("http://10.0.0.9:8765", "http://10.0.0.20:8765"))

    private fun replyInfo(name: String = "Pixel Pantry") = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"name":"$name","version":"0.1.0"}"""),
    )

    @Test fun foundServerSetsBaseUrlAndIsReady() = runTest {
        val vm = ConnectViewModel(FakeLocator(ServerResolver.Result.Found("http://10.0.0.5:8765", ServerResolver.Source.MDNS)), api, store)
        assertEquals(UiState.Ready("http://10.0.0.5:8765"), vm.state.value)
        assertEquals("http://10.0.0.5:8765", api.baseUrl)
    }

    @Test fun notFoundFailsWithTriedList() = runTest {
        val vm = ConnectViewModel(FakeLocator(notFound), api, store)
        assertEquals(UiState.Failed(tried = notFound.tried, manualDraft = ""), vm.state.value)
        assertEquals("", api.baseUrl)
    }

    @Test fun manualAddressIsProbedStoredAndReady() = runTest {
        val vm = ConnectViewModel(FakeLocator(notFound), api, store)
        replyInfo()
        vm.submitManual(serverAddress)
        val ready = vm.state.first { it !is UiState.Resolving }
        assertEquals(UiState.Ready("http://$serverAddress"), ready)
        assertEquals("http://$serverAddress", api.baseUrl)
        assertEquals(serverAddress, store.manualAddress.first())
        assertEquals("/api/info", server.takeRequest().path)
    }

    @Test fun invalidAddressStaysFailedWithErrorAndProbesNothing() = runTest {
        val vm = ConnectViewModel(FakeLocator(notFound), api, store)
        for (bad in listOf("", "192.168.1.20", "http://192.168.1.20:8765", ":8765", "host:0", "host:70000", "host:abc")) {
            vm.submitManual(bad)
            assertEquals(
                UiState.Failed(tried = notFound.tried, manualDraft = bad, error = ConnectError.InvalidAddress),
                vm.state.value,
            )
        }
        assertEquals(0, server.requestCount)
        assertNull(store.manualAddress.first())
        assertEquals("", api.baseUrl)
    }

    @Test fun unreachableManualAddressFailsWithAddressAndKeepsBaseUrl() = runTest {
        val vm = ConnectViewModel(FakeLocator(notFound), api, store)
        replyInfo(name = "Some Other Device")
        vm.submitManual(serverAddress)
        val failed = vm.state.first { it !is UiState.Resolving }
        assertEquals(
            UiState.Failed(tried = notFound.tried, manualDraft = serverAddress, error = ConnectError.Unreachable(serverAddress)),
            failed,
        )
        assertEquals("", api.baseUrl)
        assertNull(store.manualAddress.first())
    }

    @Test fun retryResolvesAgain() = runTest {
        val locator = FakeLocator(notFound, ServerResolver.Result.Found("http://10.0.0.5:8765", ServerResolver.Source.LAST_KNOWN))
        val vm = ConnectViewModel(locator, api, store)
        vm.retry()
        assertEquals(2, locator.calls)
        assertEquals(UiState.Ready("http://10.0.0.5:8765"), vm.state.value)
        assertEquals("http://10.0.0.5:8765", api.baseUrl)
    }

    @Test fun draftEditsAreKeptInFailedState() = runTest {
        val vm = ConnectViewModel(FakeLocator(notFound), api, store)
        vm.onDraftChange("10.0.0.")
        assertEquals(UiState.Failed(tried = notFound.tried, manualDraft = "10.0.0."), vm.state.value)
    }
}
