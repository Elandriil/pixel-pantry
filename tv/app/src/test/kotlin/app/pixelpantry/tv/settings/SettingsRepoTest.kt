package app.pixelpantry.tv.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.Settings
import app.pixelpantry.tv.api.SettingsPatch
import app.pixelpantry.tv.server.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    private fun repoApi() = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
}
