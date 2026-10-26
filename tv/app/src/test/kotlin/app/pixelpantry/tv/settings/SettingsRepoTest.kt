package app.pixelpantry.tv.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.Settings
import app.pixelpantry.tv.api.SettingsPatch
import app.pixelpantry.tv.server.ServerStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SettingsRepoTest {
    private val dir: File = Files.createTempDirectory("pantry-settings").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = ServerStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "pantry.preferences_pb") })
    private lateinit var server: MockWebServer
    private lateinit var repo: SettingsRepo

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val api = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
        repo = SettingsRepo(api, store, scope)
    }

    @After fun tearDown() { runCatching { server.shutdown() }; scope.cancel(); dir.deleteRecursively() }

    private fun reply(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    @Test fun initialValueIsDefaults() {
        assertEquals(Settings(10, 10, true), repo.settings.value)
    }

    @Test fun refreshEmitsServerValuesAndCaches() = runTest {
        reply("""{"skipBackSeconds":5,"skipForwardSeconds":45,"autoplayNext":false}""")
        repo.refresh()
        assertEquals(Settings(5, 45, false), repo.settings.value)
        assertEquals(Settings(5, 45, false), store.cachedSettings.first())
    }

    @Test fun updateEmitsReturnedObjectAndCaches() = runTest {
        reply("""{"skipBackSeconds":20,"skipForwardSeconds":10,"autoplayNext":true}""")
        repo.update(SettingsPatch(skipBackSeconds = 20))
        assertEquals(Settings(20, 10, true), repo.settings.value)
        assertEquals(Settings(20, 10, true), store.cachedSettings.first())
    }

    @Test fun failedUpdateLeavesFlowUnchangedAndRethrows() = runTest {
        reply("""{"error":"bad"}""", 400)
        try {
            repo.update(SettingsPatch(skipBackSeconds = 999))
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
        }
        assertEquals(Settings(10, 10, true), repo.settings.value)
    }

    @Test fun cachedSettingsAreFirstNonDefaultValue() = runTest {
        val cached = Settings(7, 8, false)
        store.setCachedSettings(cached)
        val r = SettingsRepo(repoApi(), store, scope)
        assertEquals(cached, r.settings.first { it != Settings(10, 10, true) })
    }

    @Test fun refreshIsNotOverwrittenByCache() = runTest {
        store.setCachedSettings(Settings(7, 8, false))
        val r = SettingsRepo(repoApi(), store, scope)
        reply("""{"skipBackSeconds":5,"skipForwardSeconds":45,"autoplayNext":true}""")
        r.refresh()
        assertEquals(Settings(5, 45, true), r.settings.value)
        assertEquals(Settings(5, 45, true), store.cachedSettings.first())
    }

    @Test fun unreadableCacheFallsBackToDefaultsWithoutCrashing() = runTest {
        val failures = mutableListOf<Throwable>()
        val guarded = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> failures += t })
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk unreadable")
        }
        val r = SettingsRepo(repoApi(), ServerStore(broken), guarded)
        reply("""{"skipBackSeconds":5,"skipForwardSeconds":45,"autoplayNext":true}""")
        // refresh waits for the seed; the cache write fails, but the in-memory value is published first.
        runCatching { r.refresh() }
        assertEquals(Settings(5, 45, true), r.settings.value)
        assertEquals(emptyList<Throwable>(), failures)
        guarded.cancel()
    }

    @Test fun aFailedCacheWriteDoesNotEscapeRefreshOrUpdate() = runTest {
        val readOnly = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        val r = SettingsRepo(repoApi(), ServerStore(readOnly), scope)
        reply("""{"skipBackSeconds":5,"skipForwardSeconds":45,"autoplayNext":true}""")
        r.refresh() // must not throw
        assertEquals(Settings(5, 45, true), r.settings.value)
        reply("""{"skipBackSeconds":20,"skipForwardSeconds":45,"autoplayNext":true}""")
        r.update(SettingsPatch(skipBackSeconds = 20))
        assertEquals(Settings(20, 45, true), r.settings.value)
    }

    private fun repoApi() = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
}
