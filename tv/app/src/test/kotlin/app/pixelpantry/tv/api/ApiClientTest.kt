package app.pixelpantry.tv.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ApiClient(OkHttpClient(), json).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun reply(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body))

    private val item = """{"id":7,"type":"episode","category":"tv","title":"Pilot","showId":1,"showTitle":"Show","season":1,"episode":1,"episodeEnd":null,"year":null,"runtime":42,"overview":null,"poster":null,"backdrop":null,"still":"/img/s.jpg","airDate":null,"progress":{"position":10.5,"duration":2500,"watched":false},"addedAt":1}"""
    private val show = """{"id":1,"category":"tv","title":"Show","year":2020,"overview":null,"poster":"/img/p.jpg","backdrop":null,"rating":8.1,"genres":["Drama"],"episodeCount":10,"unwatchedCount":9,"matched":true,"addedAt":1}"""

    @Test fun resolvePrefixesRelativeAndKeepsAbsolute() {
        client.baseUrl = "http://host:8765/"
        assertEquals("http://host:8765/img/p.jpg", client.resolve("/img/p.jpg"))
        assertEquals("https://other/x.jpg", client.resolve("https://other/x.jpg"))
        assertEquals("http://other/x.jpg", client.resolve("http://other/x.jpg"))
    }

    @Test fun homeParsesShowAndItemCards() = runTest {
        reply("""{"category":"home","rows":[{"key":"continue","title":"Continue","cards":[
            {"kind":"show","show":$show,"nextUp":$item},
            {"kind":"item","item":$item,"reason":"resume"}]}]}""")
        val page = client.home()
        assertEquals("/api/home", server.takeRequest().path)
        val cards = page.rows.single().cards
        val sc = cards[0] as Card.ShowCard
        assertEquals("Show", sc.show.title)
        assertEquals(7, sc.nextUp?.id)
        val ic = cards[1] as Card.ItemCard
        assertEquals("resume", ic.reason)
        assertEquals(10.5, ic.item.progress?.position ?: 0.0, 0.0)
    }

    @Test fun itemParsesAudioAndSubtitles() = runTest {
        reply("""{"item":$item,"streamUrl":"/api/items/7/stream","fileName":"a.mkv","fileSize":123,
            "audio":[{"index":1,"codec":"aac","language":"eng","title":null,"channels":6,"default":true,"forced":false}],
            "subtitles":[{"id":"embedded:3","label":"English","lang":"en","url":null,"format":"embedded"}],
            "next":null,"prev":null,"subtitleChoice":"embedded:3","audioChoice":null}""")
        val d = client.item(7)
        assertEquals("/api/items/7", server.takeRequest().path)
        assertTrue(d.audio.single().isDefault)
        assertEquals(6, d.audio.single().channels)
        assertEquals("embedded", d.subtitles.single().format)
        assertNull(d.subtitles.single().url)
        assertEquals("embedded:3", d.subtitleChoice)
        assertNull(d.next)
    }

    @Test fun progressPostsExactBodyAndParsesSessionId() = runTest {
        reply("""{"sessionId":42,"watched":false}""")
        val r = client.progress(7, 12.5, 100.0, 3)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/items/7/progress", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("position", "duration", "sessionId"), body.keys)
        assertEquals(12.5, body["position"]!!.jsonPrimitive.double, 0.0)
        assertEquals(100.0, body["duration"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3, body["sessionId"]!!.jsonPrimitive.int)
        assertEquals(42, r.sessionId)
    }

    @Test fun progressOmitsNullSessionId() = runTest {
        reply("""{"sessionId":1,"watched":false}""")
        client.progress(7, 1.0, 2.0, null)
        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("sessionId"))
    }

    @Test fun notFoundThrowsApiExceptionWithServerMessage() = runTest {
        reply("""{"error":"Not found"}""", 404)
        try {
            client.show(99)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.status)
            assertEquals("Not found", e.message)
        }
    }

    @Test fun unreachableServerThrowsStatusZeroWithBaseUrl() = runTest {
        server.shutdown()
        try {
            client.info()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(0, e.status)
            assertTrue(e.message!!.contains(client.baseUrl))
        }
    }

    @Test fun updateSettingsSendsOnlyPatchedKey() = runTest {
        reply("""{"skipBackSeconds":15,"skipForwardSeconds":30,"autoplayNext":true}""")
        val s = client.updateSettings(SettingsPatch(skipBackSeconds = 15))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/settings", req.path)
        val body: JsonObject = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("skipBackSeconds"), body.keys)
        assertEquals(15, body["skipBackSeconds"]!!.jsonPrimitive.int)
        assertEquals(30, s.skipForwardSeconds)
    }

    @Test fun scanParsesStatusFromAccepted() = runTest {
        reply("""{"status":{"state":"scanning","startedAt":5,"finishedAt":null,"filesSeen":2,"filesProcessed":1,"phase":"probing","lastError":null}}""", 202)
        val s = client.scan()
        assertEquals("POST", server.takeRequest().method)
        assertEquals("scanning", s.state)
        assertEquals("probing", s.phase)
        assertEquals(1, s.filesProcessed)
    }

    @Test fun watchedAndTracksBodies() = runTest {
        reply("{}"); reply("{}"); reply("{}")
        client.setWatched(7, true)
        client.setShowWatched(1, false, season = 2)
        client.setTracks(7, "embedded:3", null)
        var r = server.takeRequest()
        assertEquals("/api/items/7/watched", r.path)
        assertTrue(json.parseToJsonElement(r.body.readUtf8()).jsonObject["watched"]!!.jsonPrimitive.boolean)
        r = server.takeRequest()
        assertEquals("/api/shows/1/watched", r.path)
        val b = json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals(2, b["season"]!!.jsonPrimitive.int)
        assertFalse(b["watched"]!!.jsonPrimitive.boolean)
        r = server.takeRequest()
        assertEquals("/api/items/7/tracks", r.path)
        assertEquals(setOf("subtitle"), json.parseToJsonElement(r.body.readUtf8()).jsonObject.keys)
    }

    @Test fun queryAndDeletePaths() = runTest {
        reply("""{"shows":[],"movies":[]}""")
        reply("""{"trashed":["a"]}""")
        reply("""{"entries":[]}""")
        client.search("star wars")
        assertEquals("/api/search?q=star%20wars", server.takeRequest().path)
        assertEquals(listOf("a"), client.deleteShow(3).trashed)
        val d = server.takeRequest()
        assertEquals("DELETE", d.method)
        assertEquals("/api/shows/3", d.path)
        client.history()
        assertEquals("/api/history?limit=200", server.takeRequest().path)
    }
}
