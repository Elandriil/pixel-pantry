package app.pixelpantry.tv.player

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.player.ProgressReporter.Reason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class ProgressReporterTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    /** Progress posts in arrival order: path and JSON body. */
    private val posts = ConcurrentLinkedQueue<Pair<String, JsonObject>>()
    private var nextSessionId = 7
    private var failNext = 0
    private var now = 1_000_000L
    private val errors = mutableListOf<String>()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()) as JsonObject
                posts += request.path.orEmpty() to body
                if (failNext > 0) {
                    failNext--
                    return MockResponse().setResponseCode(500).setBody("""{"error":"boom"}""")
                }
                // Like the server: a known session continues, none opens a new one.
                val id = body.sessionId() ?: nextSessionId++
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"sessionId":$id,"watched":false}""")
            }
        }
        server.start()
        api = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
    }

    @After fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun reporter() = ProgressReporter(api, clock = { now }, intervalMs = 30_000, onError = { errors += it })

    private fun JsonObject.sessionId(): Int? = this["sessionId"]?.jsonPrimitive?.int

    @Test fun ticksSendAtMostEveryIntervalAndCarryTheSessionId() = runTest {
        val r = reporter()
        r.start(4)

        r.tick(10.0, 3000.0)
        assertEquals(1, posts.size)
        val (path, first) = posts.last()
        assertEquals("/api/items/4/progress", path)
        assertNull(first.sessionId())
        assertEquals(10.0, first["position"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3000.0, first["duration"]!!.jsonPrimitive.double, 0.0)

        now += 29_999
        r.tick(40.0, 3000.0)
        assertEquals(1, posts.size)

        now += 1
        r.tick(40.0, 3000.0)
        assertEquals(2, posts.size)
        assertEquals(7, posts.last().second.sessionId())
    }

    @Test fun flushAlwaysSendsWithTheSessionId() = runTest {
        val r = reporter()
        r.start(4)
        r.tick(10.0, 3000.0)
        r.flush(11.0, 3000.0, Reason.PAUSE)
        r.flush(11.0, 3000.0, Reason.STOP)
        assertEquals(3, posts.size)
        assertEquals(listOf(null, 7, 7), posts.map { it.second.sessionId() })
    }

    @Test fun flushRestartsTheTickInterval() = runTest {
        val r = reporter()
        r.start(4)
        r.flush(11.0, 3000.0, Reason.PAUSE)
        now += 10_000
        r.tick(12.0, 3000.0)
        assertEquals(1, posts.size)
    }

    @Test fun startOnANewItemDropsTheOldSessionId() = runTest {
        val r = reporter()
        r.start(4)
        r.tick(10.0, 3000.0)
        r.flush(20.0, 3000.0, Reason.SWITCH)
        r.start(5)
        r.tick(0.0, 1500.0)
        assertEquals("/api/items/5/progress", posts.last().first)
        assertNull(posts.last().second.sessionId())
        // and the new session's id is used from then on
        r.flush(1.0, 1500.0, Reason.STOP)
        assertEquals(8, posts.last().second.sessionId())
    }

    @Test fun serverErrorDoesNotThrowAndTheNextReportHasNoSessionId() = runTest {
        val r = reporter()
        r.start(4)
        failNext = 1
        r.tick(10.0, 3000.0) // 500: swallowed
        assertEquals(1, errors.size)
        r.flush(12.0, 3000.0, Reason.PAUSE)
        assertEquals(2, posts.size)
        assertNull(posts.last().second.sessionId())
        r.flush(13.0, 3000.0, Reason.STOP)
        assertEquals(7, posts.last().second.sessionId())
    }

    @Test fun aFailureMidSessionKeepsTheSessionId() = runTest {
        val r = reporter()
        r.start(4)
        r.tick(10.0, 3000.0)
        failNext = 1
        r.flush(12.0, 3000.0, Reason.PAUSE)
        r.flush(13.0, 3000.0, Reason.STOP)
        assertEquals(listOf(null, 7, 7), posts.map { it.second.sessionId() })
    }

    @Test fun unreachableServerDoesNotThrow() = runTest {
        val r = reporter()
        r.start(4)
        server.shutdown()
        r.tick(10.0, 3000.0)
        r.flush(10.0, 3000.0, Reason.STOP)
        assertEquals(2, errors.size)
    }

    @Test fun nothingIsSentBeforeStart() = runTest {
        val r = reporter()
        r.tick(10.0, 3000.0)
        r.flush(10.0, 3000.0, Reason.STOP)
        assertEquals(0, posts.size)
    }
}
