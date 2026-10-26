package app.pixelpantry.tv.di

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pixelpantry.tv.server.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** I6: a page restored after process death skips the connect screen, so the container starts on the stored address. */
class AppContainerTest {
    private val dir: File = Files.createTempDirectory("pantry-container").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pantry.preferences_pb") }
    private val store = ServerStore(dataStore)

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun container() = AppContainer(Application(), dataStoreOverride = dataStore)

    @Test fun startsWithTheStoredManualAddress() {
        runBlocking {
            store.setLastKnownAddress("192.168.1.20:8765")
            store.setManualAddress("10.0.2.2:8765")
        }
        assertEquals("http://10.0.2.2:8765", container().api.baseUrl)
    }

    @Test fun startsWithTheLastKnownAddressWithoutAManualOne() {
        runBlocking { store.setLastKnownAddress("192.168.1.20:8765") }
        assertEquals("http://192.168.1.20:8765", container().api.baseUrl)
    }

    @Test fun startsBlankWithoutAStoredAddress() {
        assertEquals("", container().api.baseUrl)
    }
}
