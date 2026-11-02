package app.pixelpantry.tv.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import app.pixelpantry.tv.api.InfoResponse
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerResolverTest {
    private val dir: File = Files.createTempDirectory("pantry-resolver").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ServerStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pantry.preferences_pb") })

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private class FakeDiscovery(private val found: List<Candidate>) : Discovery {
        var consulted = false
        override fun candidates(timeoutMs: Long): Flow<Candidate> { consulted = true; return flowOf(*found.toTypedArray()) }
    }

    /** Each reachable Pixel Pantry server answers with its server id (by default every one is "srv"). */
    private fun resolver(
        discovery: Discovery,
        okUrls: Set<String>,
        probed: MutableList<String> = mutableListOf(),
        serverIds: Map<String, String> = emptyMap(),
    ) = ServerResolver(store, discovery) { url ->
        probed += url
        if (url in okUrls) InfoResponse("Pixel Pantry", "1", serverIds[url] ?: "srv") else null
    }

    @Test fun manualReachableIsFound() = runTest {
        store.setManualAddress("10.0.0.5:8765")
        val d = FakeDiscovery(emptyList())
        val r = resolver(d, setOf("http://10.0.0.5:8765")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.5:8765", ServerResolver.Source.MANUAL, "srv"), r)
    }

    @Test fun manualUnreachableFallsBackToMdnsButNotToLastKnown() = runTest {
        store.setManualAddress("10.0.0.5:8765")
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.5", 8765), Candidate("10.0.0.7", 8765)))
        val probed = mutableListOf<String>()
        val r = resolver(d, setOf("http://10.0.0.9:8765", "http://10.0.0.7:8765"), probed).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.7:8765", ServerResolver.Source.MDNS, "srv"), r)
        // the failed manual address is not probed twice when mDNS announces the same one
        assertEquals(listOf("http://10.0.0.5:8765", "http://10.0.0.7:8765"), probed)
        assertEquals("10.0.0.7:8765", store.lastKnownAddress.first())
    }

    @Test fun manualUnreachableAndNothingAnnouncedIsNotFound() = runTest {
        store.setManualAddress("10.0.0.5:8765")
        val r = resolver(FakeDiscovery(emptyList()), emptySet()).resolve()
        assertEquals(ServerResolver.Result.NotFound(listOf("http://10.0.0.5:8765")), r)
    }

    @Test fun lastKnownReachableIsFound() = runTest {
        store.setLastKnownAddress("10.0.0.9:8765")
        val r = resolver(FakeDiscovery(emptyList()), setOf("http://10.0.0.9:8765")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.9:8765", ServerResolver.Source.LAST_KNOWN, "srv"), r)
    }

    @Test fun nonPantryDeviceIsRejectedAndNextCandidateUsed() = runTest {
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.20", 8765), Candidate("10.0.0.21", 8765)))
        val r = resolver(d, setOf("http://10.0.0.21:8765")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.21:8765", ServerResolver.Source.MDNS, "srv"), r)
        assertEquals("10.0.0.21:8765", store.lastKnownAddress.first())
    }

    @Test fun nothingFoundListsEverythingTried() = runTest {
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.20", 8765), Candidate("10.0.0.21", 8765)))
        val r = resolver(d, emptySet()).resolve()
        assertEquals(
            ServerResolver.Result.NotFound(listOf("http://10.0.0.9:8765", "http://10.0.0.20:8765", "http://10.0.0.21:8765")),
            r,
        )
    }

    @Test fun neverCompletingDiscoveryTimesOutToNotFound() = runTest {
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = object : Discovery {
            override fun candidates(timeoutMs: Long): Flow<Candidate> = flow { awaitCancellation() }
        }
        val r = resolver(d, emptySet()).resolve()
        assertEquals(ServerResolver.Result.NotFound(listOf("http://10.0.0.9:8765")), r)
    }

    @Test fun duplicateAndLastKnownCandidatesAreProbedOnce() = runTest {
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.9", 8765), Candidate("10.0.0.20", 8765), Candidate("10.0.0.20", 8765)))
        val r = resolver(d, emptySet()).resolve()
        assertEquals(ServerResolver.Result.NotFound(listOf("http://10.0.0.9:8765", "http://10.0.0.20:8765")), r)
    }

    // With a pairing stored, only the paired server is adopted automatically (a rogue "Pixel Pantry" on the LAN
    // cannot become the last known address); a typed address is the owner's choice and always wins.

    @Test fun pairedTvSkipsALastKnownAddressNowHostingAnotherServer() = runTest {
        store.setPairing(Pairing("tok", "A"))
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.7", 8765)))
        val r = resolver(d, setOf("http://10.0.0.9:8765", "http://10.0.0.7:8765"),
            serverIds = mapOf("http://10.0.0.9:8765" to "B", "http://10.0.0.7:8765" to "A")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.7:8765", ServerResolver.Source.MDNS, "A"), r)
        assertEquals("10.0.0.7:8765", store.lastKnownAddress.first())
    }

    @Test fun pairedTvSkipsAnnouncedServersWithAnotherId() = runTest {
        store.setPairing(Pairing("tok", "A"))
        val d = FakeDiscovery(listOf(Candidate("10.0.0.20", 8765), Candidate("10.0.0.21", 8765)))
        val probed = mutableListOf<String>()
        val r = resolver(d, setOf("http://10.0.0.20:8765", "http://10.0.0.21:8765"), probed,
            serverIds = mapOf("http://10.0.0.20:8765" to "B", "http://10.0.0.21:8765" to "A")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.21:8765", ServerResolver.Source.MDNS, "A"), r)
        assertEquals(listOf("http://10.0.0.20:8765", "http://10.0.0.21:8765"), probed)
    }

    @Test fun pairedTvFindingOnlyOtherServersIsNotFound() = runTest {
        store.setPairing(Pairing("tok", "A"))
        val d = FakeDiscovery(listOf(Candidate("10.0.0.20", 8765)))
        val r = resolver(d, setOf("http://10.0.0.20:8765"), serverIds = mapOf("http://10.0.0.20:8765" to "B")).resolve()
        assertEquals(ServerResolver.Result.NotFound(listOf("http://10.0.0.20:8765")), r)
    }

    @Test fun aManualAddressIsFoundEvenWhenItIsAnotherServer() = runTest {
        store.setPairing(Pairing("tok", "A"))
        store.setManualAddress("10.0.0.5:8765")
        val r = resolver(FakeDiscovery(emptyList()), setOf("http://10.0.0.5:8765"), serverIds = mapOf("http://10.0.0.5:8765" to "B")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.5:8765", ServerResolver.Source.MANUAL, "B"), r)
    }

    @Test fun withoutAPairingTheFirstPixelPantryWins() = runTest {
        val d = FakeDiscovery(listOf(Candidate("10.0.0.20", 8765)))
        val r = resolver(d, setOf("http://10.0.0.20:8765"), serverIds = mapOf("http://10.0.0.20:8765" to "B")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.20:8765", ServerResolver.Source.MDNS, "B"), r)
    }
}
