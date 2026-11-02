package app.pixelpantry.tv.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Images and the video stream go through plain OkHttp / ExoPlayer; the interceptor adds the token for them. */
class BearerInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var other: MockWebServer
    private val api = ApiClient(OkHttpClient(), ApiClient.defaultJson)
    private val http = OkHttpClient.Builder().addInterceptor(BearerInterceptor(api)).build()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        other = MockWebServer().also { it.start() }
        api.baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun tearDown() { runCatching { server.shutdown() }; runCatching { other.shutdown() } }

    private fun get(url: String) = http.newCall(Request.Builder().url(url).build()).execute().close()

    @Test fun addsTheTokenToRequestsAimedAtTheServer() {
        api.token = "secret"
        server.enqueue(MockResponse())
        get(api.baseUrl + "/api/images/w500/p.jpg")
        assertEquals("Bearer secret", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun sendsNothingWithoutATokenOrToAnotherHost() {
        server.enqueue(MockResponse())
        get(api.baseUrl + "/api/images/w500/p.jpg")
        assertNull(server.takeRequest().getHeader("Authorization"))
        api.token = "secret"
        other.enqueue(MockResponse())
        get(other.url("/api/info").toString())
        assertNull("the token never leaves for another server", other.takeRequest().getHeader("Authorization"))
    }

    @Test fun keepsAnAuthorizationHeaderThatIsAlreadyThere() {
        api.token = "secret"
        server.enqueue(MockResponse())
        http.newCall(Request.Builder().url(api.baseUrl + "/api/home").header("Authorization", "Bearer older").build()).execute().close()
        assertEquals("Bearer older", server.takeRequest().getHeader("Authorization"))
    }
}
