package app.pixelpantry.tv.ui.connect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.InfoResponse
import app.pixelpantry.tv.server.Pairing
import app.pixelpantry.tv.server.ServerLocator
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.ui.connect.ConnectViewModel.ConnectError
import app.pixelpantry.tv.ui.connect.ConnectViewModel.PairError
import app.pixelpantry.tv.ui.connect.ConnectViewModel.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
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
        verifyScope.cancel()
        dir.deleteRecursively()
    }

    private val serverAddress get() = "${server.hostName}:${server.port}"
    private val notFound = ServerResolver.Result.NotFound(listOf("http://10.0.0.9:8765", "http://10.0.0.20:8765"))

    /** Builds the view model and waits until startup has decided (cached address, resolve, or change-server). */
    private suspend fun connect(
        locator: ServerLocator,
        store: ServerStore = this.store,
        probe: (suspend (String) -> InfoResponse?)? = null,
        changeServer: Boolean = false,
        pairAddress: String? = null,
    ): ConnectViewModel {
        val vm = if (probe == null) {
            ConnectViewModel(locator, api, store, verifyScope = verifyScope, onCachedUnreachable = { unreachableToasts += it }, changeServer = changeServer, pairAddress = pairAddress)
        } else {
            ConnectViewModel(locator, api, store, probe, verifyScope, { unreachableToasts += it }, changeServer, pairAddress)
        }
        vm.state.first { it !is UiState.Resolving }
        return vm
    }

    /** The TV is paired with the server whose id is [serverId] ("srv": the one the fakes and [replyInfo] answer with). */
    private suspend fun paired(serverId: String = "srv") = store.setPairing(Pairing("tok", serverId))
    private val pantry = InfoResponse("Pixel Pantry", "1", "srv")
    private fun found(url: String, source: ServerResolver.Source = ServerResolver.Source.MDNS, serverId: String? = "srv") =
        ServerResolver.Result.Found(url, source, serverId)

    private val verifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unreachableToasts = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private fun replyInfo(name: String = "Pixel Pantry", serverId: String = "srv") = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"name":"$name","version":"0.1.0","serverId":"$serverId"}"""),
    )
    private fun replyPair(token: String = "new", serverId: String = "srv") = server.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"token":"$token","serverId":"$serverId"}"""),
    )
    private fun replyError(code: Int, message: String) = server.enqueue(
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody("""{"error":"$message"}"""),
    )

    @Test fun foundPairedServerSetsBaseUrlAndTokenAndIsReady() = runTest {
        paired()
        val vm = connect(FakeLocator(found("http://10.0.0.5:8765")))
        assertEquals(UiState.Ready("http://10.0.0.5:8765"), vm.state.value)
        assertEquals("http://10.0.0.5:8765", api.baseUrl)
        assertEquals("tok", api.token)
    }

    @Test fun notFoundFailsWithTriedList() = runTest {
        val vm = connect(FakeLocator(notFound))
        assertEquals(UiState.Failed(tried = notFound.tried, manualDraft = ""), vm.state.value)
        assertEquals("", api.baseUrl)
    }

    @Test fun aResolverFailureIsNotFoundWithNothingTried() = runTest {
        val vm = connect(ServerLocator { throw IOException("DataStore read failed") })
        assertEquals(UiState.Failed(tried = emptyList(), manualDraft = ""), vm.state.value)
    }

    @Test fun manualAddressIsProbedStoredAndReady() = runTest {
        paired()
        val vm = connect(FakeLocator(notFound))
        replyInfo()
        vm.onDraftChange(serverAddress)
        vm.submitManual()
        val ready = vm.state.first { it !is UiState.Resolving }
        assertEquals(UiState.Ready("http://$serverAddress"), ready)
        assertEquals("http://$serverAddress", api.baseUrl)
        assertEquals(serverAddress, store.manualAddress.first())
        assertEquals("/api/info", server.takeRequest().path)
    }

    @Test fun invalidAddressStaysFailedWithErrorAndProbesNothing() = runTest {
        val vm = connect(FakeLocator(notFound))
        for (bad in listOf("", "192.168.1.20", "http://192.168.1.20:8765", ":8765", "host:0", "host:70000", "host:abc")) {
            vm.onDraftChange(bad)
            vm.submitManual()
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
        val vm = connect(FakeLocator(notFound))
        replyInfo(name = "Some Other Device")
        vm.onDraftChange(serverAddress)
        vm.submitManual()
        val failed = vm.state.first { it !is UiState.Resolving }
        assertEquals(
            UiState.Failed(tried = notFound.tried, manualDraft = serverAddress, error = ConnectError.Unreachable(serverAddress)),
            failed,
        )
        assertEquals("", api.baseUrl)
        assertNull(store.manualAddress.first())
    }

    @Test fun storageFailureStillConnectsAndPairs() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk unreadable")
        }
        val vm = connect(FakeLocator(notFound), ServerStore(broken))
        replyInfo()
        vm.onDraftChange(serverAddress)
        vm.submitManual()
        // the pairing cannot be read, so the server asks for its PIN; the pairing cannot be stored either, but works
        val pairing = vm.state.first { it !is UiState.Resolving }
        assertTrue(pairing.toString(), pairing is UiState.Pairing)
        replyPair()
        vm.onPinChange("123456")
        vm.submitPin()
        assertEquals(UiState.Ready("http://$serverAddress"), vm.state.first { it !is UiState.Pairing })
        assertEquals("http://$serverAddress", api.baseUrl)
        assertEquals("new", api.token)
    }

    @Test fun submitUsesLatestDraftAndRetryKeepsIt() = runTest {
        val vm = connect(FakeLocator(notFound, notFound))
        vm.onDraftChange("10.0.0.")
        vm.onDraftChange("bad")
        vm.submitManual()
        assertEquals(UiState.Failed(notFound.tried, manualDraft = "bad", error = ConnectError.InvalidAddress), vm.state.value)
        vm.retry()
        assertEquals(UiState.Failed(notFound.tried, manualDraft = "bad"), vm.state.value)
    }

    @Test fun retryResolvesAgain() = runTest {
        paired()
        val locator = FakeLocator(notFound, found("http://10.0.0.5:8765", ServerResolver.Source.LAST_KNOWN))
        val vm = connect(locator)
        vm.retry()
        // adopting a found server reads the stored pairing, so Ready arrives a moment later
        assertEquals(UiState.Ready("http://10.0.0.5:8765"), vm.state.first { it !is UiState.Resolving })
        assertEquals(2, locator.calls)
        assertEquals("http://10.0.0.5:8765", api.baseUrl)
    }

    @Test fun draftEditsAreKeptInFailedState() = runTest {
        val vm = connect(FakeLocator(notFound))
        vm.onDraftChange("10.0.0.")
        assertEquals(UiState.Failed(tried = notFound.tried, manualDraft = "10.0.0."), vm.state.value)
    }

    // Startup with a cached address, and "Change server" (Task 13)

    @Test fun aCachedAddressGoesStraightToReadyAndVerifiesInTheBackground() = runTest {
        paired()
        store.setLastKnownAddress(serverAddress)
        replyInfo()
        val locator = FakeLocator()
        val vm = connect(locator)
        assertEquals(UiState.Ready("http://$serverAddress"), vm.state.value)
        assertEquals("http://$serverAddress", api.baseUrl)
        assertEquals("tok", api.token)
        assertEquals("no resolve: the cached address is used as is", 0, locator.calls)
        assertEquals("/api/info", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.path)
        Thread.sleep(100)
        assertEquals(emptyList<String>(), unreachableToasts.toList())
    }

    @Test fun aFailedBackgroundVerifyToastsTheAddressAndStaysReady() = runTest {
        paired()
        store.setLastKnownAddress("10.0.0.99:8765")
        val probed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val vm = connect(FakeLocator(), probe = { probed += it; null })
        assertEquals(UiState.Ready("http://10.0.0.99:8765"), vm.state.value)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (unreachableToasts.isEmpty()) delay(10) } }
        assertEquals(listOf("10.0.0.99:8765"), unreachableToasts.toList())
        assertEquals(listOf("http://10.0.0.99:8765"), probed.toList())
        assertEquals("keeps working with the cached address", "http://10.0.0.99:8765", api.baseUrl)
    }

    @Test fun aStoredManualAddressIsTheCachedAddress() = runTest {
        paired()
        store.setLastKnownAddress("10.0.0.1:8765")
        store.setManualAddress("10.0.0.2:8765")
        val vm = connect(FakeLocator(), probe = { pantry })
        assertEquals(UiState.Ready("http://10.0.0.2:8765"), vm.state.value)
    }

    @Test fun withoutACachedAddressTheResolverRuns() = runTest {
        val locator = FakeLocator(found("http://10.0.0.5:8765"))
        connect(locator)
        assertEquals(1, locator.calls)
    }

    @Test fun changeServerShowsTheFormPrefilledWithTheCurrentAddress() = runTest {
        paired()
        store.setLastKnownAddress("10.0.0.5:8765")
        api.baseUrl = "http://10.0.0.5:8765"
        val locator = FakeLocator()
        val vm = connect(locator, changeServer = true)
        assertEquals(
            UiState.Failed(tried = listOf("http://10.0.0.5:8765"), manualDraft = "10.0.0.5:8765", changingServer = true),
            vm.state.value,
        )
        assertEquals(0, locator.calls)
        assertEquals(0, server.requestCount)

        vm.onDraftChange("bad")
        vm.submitManual()
        assertEquals("still titled Change server", true, (vm.state.value as UiState.Failed).changingServer)

        replyInfo()
        vm.onDraftChange(serverAddress)
        vm.submitManual()
        assertEquals(UiState.Ready("http://$serverAddress"), vm.state.first { it !is UiState.Resolving })
    }

    // Pairing: a server that is not the paired one asks for the PIN from its admin page

    @Test fun aFoundServerWithoutAPairingAsksForThePin() = runTest {
        val vm = connect(FakeLocator(found("http://10.0.0.5:8765")))
        assertEquals(UiState.Pairing(baseUrl = "http://10.0.0.5:8765", serverId = "srv"), vm.state.value)
        assertEquals("not adopted before the PIN is accepted", "", api.baseUrl)
        assertNull(api.token)
    }

    @Test fun aFoundServerWithAnotherIdThanThePairingAsksForThePin() = runTest {
        paired("other-server")
        val vm = connect(FakeLocator(found("http://10.0.0.5:8765", ServerResolver.Source.MANUAL)))
        assertEquals(UiState.Pairing(baseUrl = "http://10.0.0.5:8765", serverId = "srv"), vm.state.value)
    }

    @Test fun theRightPinPairsStoresThePairingAndIsReady() = runTest {
        val vm = connect(FakeLocator(found("http://$serverAddress")))
        replyPair(token = "new")
        vm.onPinChange("123456")
        assertEquals(UiState.Pairing("http://$serverAddress", "srv", pinDraft = "123456"), vm.state.value)
        vm.submitPin()
        assertEquals(UiState.Ready("http://$serverAddress"), vm.state.first { it !is UiState.Pairing })
        assertEquals("http://$serverAddress", api.baseUrl)
        assertEquals("new", api.token)
        assertEquals(Pairing("new", "srv"), store.pairing.first())
        val req = server.takeRequest()
        assertEquals("/api/pair", req.path)
        assertEquals("""{"pin":"123456"}""", req.body.readUtf8())
    }

    @Test fun aWrongPinStaysOnPairingWithTheError() = runTest {
        val vm = connect(FakeLocator(found("http://$serverAddress")))
        replyError(403, "Wrong PIN")
        vm.onPinChange("000000")
        vm.submitPin()
        val s = vm.state.first { it is UiState.Pairing && !it.busy }
        assertEquals(UiState.Pairing("http://$serverAddress", "srv", pinDraft = "000000", error = PairError.WrongPin), s)
        assertNull(api.token)
        assertNull(store.pairing.first())
        // typing again clears the error
        vm.onPinChange("000001")
        assertEquals(UiState.Pairing("http://$serverAddress", "srv", pinDraft = "000001"), vm.state.value)
    }

    @Test fun tooManyWrongPinsShowTheLockout() = runTest {
        val vm = connect(FakeLocator(found("http://$serverAddress")))
        replyError(429, "Too many attempts, try again in a minute")
        vm.onPinChange("000000")
        vm.submitPin()
        val s = vm.state.first { it is UiState.Pairing && !it.busy } as UiState.Pairing
        assertEquals(PairError.Locked, s.error)
    }

    @Test fun aServerGoneDuringPairingShowsUnreachable() = runTest {
        val vm = connect(FakeLocator(found("http://$serverAddress")))
        server.shutdown()
        vm.onPinChange("123456")
        vm.submitPin()
        val s = vm.state.first { it is UiState.Pairing && !it.busy } as UiState.Pairing
        assertEquals(PairError.Unreachable, s.error)
    }

    @Test fun thePinMustBeSixDigitsBeforeAnythingIsSent() = runTest {
        val vm = connect(FakeLocator(found("http://$serverAddress")))
        for (bad in listOf("", "12", "12345", "1234567", "12345a", "123 456")) {
            vm.onPinChange(bad)
            vm.submitPin()
            assertEquals(bad, PairError.InvalidPin, (vm.state.value as UiState.Pairing).error)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellingPairingReturnsToTheAddressFormPrefilled() = runTest {
        val vm = connect(FakeLocator(found("http://10.0.0.5:8765")))
        vm.cancelPairing()
        assertEquals(UiState.Failed(tried = listOf("http://10.0.0.5:8765"), manualDraft = "10.0.0.5:8765"), vm.state.value)
    }

    @Test fun aCachedAddressWithoutAPairingResolvesAndAsksForThePin() = runTest {
        store.setLastKnownAddress("10.0.0.5:8765")
        val locator = FakeLocator(found("http://10.0.0.5:8765", ServerResolver.Source.LAST_KNOWN))
        val vm = connect(locator)
        assertEquals(1, locator.calls)
        assertEquals(UiState.Pairing("http://10.0.0.5:8765", "srv"), vm.state.value)
    }

    @Test fun aManualAddressOfAnUnpairedServerIsStoredAndAsksForThePin() = runTest {
        val vm = connect(FakeLocator(notFound))
        replyInfo()
        vm.onDraftChange(serverAddress)
        vm.submitManual()
        assertEquals(UiState.Pairing("http://$serverAddress", "srv"), vm.state.first { it !is UiState.Resolving })
        assertEquals(serverAddress, store.manualAddress.first())
        assertEquals("", api.baseUrl)
    }

    @Test fun openedToPairAnAddressProbesItAndAsksForThePin() = runTest {
        replyInfo()
        val vm = connect(FakeLocator(), pairAddress = serverAddress)
        assertEquals(UiState.Pairing("http://$serverAddress", "srv"), vm.state.value)
        assertEquals("/api/info", server.takeRequest().path)
    }

    @Test fun openedToPairAnAddressThatDoesNotAnswerShowsTheForm() = runTest {
        server.shutdown()
        val vm = connect(FakeLocator(), pairAddress = serverAddress)
        assertEquals(
            UiState.Failed(tried = listOf("http://$serverAddress"), manualDraft = serverAddress, error = ConnectError.Unreachable(serverAddress)),
            vm.state.value,
        )
    }
}
