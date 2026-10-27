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

    private fun resolver(discovery: Discovery, okUrls: Set<String>, probed: MutableList<String> = mutableListOf()) =
        ServerResolver(store, discovery) { url -> probed += url; url in okUrls }

    @Test fun manualReachableIsFound() = runTest {
        store.setManualAddress("10.0.0.5:8765")
        val d = FakeDiscovery(emptyList())
        val r = resolver(d, setOf("http://10.0.0.5:8765")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.5:8765", ServerResolver.Source.MANUAL), r)
    }

    @Test fun manualUnreachableFallsBackToMdnsButNotToLastKnown() = runTest {
        store.setManualAddress("10.0.0.5:8765")
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.5", 8765), Candidate("10.0.0.7", 8765)))
        val probed = mutableListOf<String>()
        val r = resolver(d, setOf("http://10.0.0.9:8765", "http://10.0.0.7:8765"), probed).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.7:8765", ServerResolver.Source.MDNS), r)
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
        assertEquals(ServerResolver.Result.Found("http://10.0.0.9:8765", ServerResolver.Source.LAST_KNOWN), r)
    }

    @Test fun nonPantryDeviceIsRejectedAndNextCandidateUsed() = runTest {
        store.setLastKnownAddress("10.0.0.9:8765")
        val d = FakeDiscovery(listOf(Candidate("10.0.0.20", 8765), Candidate("10.0.0.21", 8765)))
        val r = resolver(d, setOf("http://10.0.0.21:8765")).resolve()
        assertEquals(ServerResolver.Result.Found("http://10.0.0.21:8765", ServerResolver.Source.MDNS), r)
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
}
