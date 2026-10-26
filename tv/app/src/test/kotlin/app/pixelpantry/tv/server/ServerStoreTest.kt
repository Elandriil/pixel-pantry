package app.pixelpantry.tv.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pixelpantry.tv.api.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServerStoreTest {
    private val dir: File = Files.createTempDirectory("pantry-store").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ServerStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pantry.preferences_pb") })

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun emptyStoreYieldsNulls() = runTest {
        assertNull(store.manualAddress.first())
        assertNull(store.lastKnownAddress.first())
        assertNull(store.cachedSettings.first())
    }

    @Test fun roundTripsAddressesAndSettings() = runTest {
        store.setManualAddress("10.0.0.5:8765")
        store.setLastKnownAddress("10.0.0.6:8765")
        val s = Settings(15, 30, false)
        store.setCachedSettings(s)
        assertEquals("10.0.0.5:8765", store.manualAddress.first())
        assertEquals("10.0.0.6:8765", store.lastKnownAddress.first())
        assertEquals(s, store.cachedSettings.first())
    }

    @Test fun settingManualToNullClearsIt() = runTest {
        store.setManualAddress("a:1")
        store.setManualAddress(null)
        assertNull(store.manualAddress.first())
    }
}
