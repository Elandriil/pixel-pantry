package app.pixelpantry.tv.ui.search

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.Card
import app.pixelpantry.tv.api.ItemSummary
import app.pixelpantry.tv.api.ShowSummary
import app.pixelpantry.tv.ui.home.HomeViewModel.NavEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val requests = ConcurrentLinkedQueue<String>()

    private val severance = """{"id":3,"category":"tv","title":"Severance","genres":[],"episodeCount":6,"unwatchedCount":5,"matched":true,"addedAt":0}"""
    private val seven = """{"id":9,"type":"movie","category":"movies","title":"Se7en","addedAt":0}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                val q = request.requestUrl?.queryParameter("q")
                val body = when (q) {
                    "sev" -> """{"shows":[$severance],"movies":[$seven]}"""
                    else -> """{"shows":[],"movies":[]}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        api = ApiClient(OkHttpClient(), ApiClient.defaultJson).also { it.baseUrl = server.url("/").toString().trimEnd('/') }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        runCatching { server.shutdown() }
    }

    private suspend fun awaitRealTime(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    @Test fun debouncesTypingAndSearchesOnlyTheSettledQuery() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = SearchViewModel(api)
        vm.setQuery("se")
        advanceTimeBy(200)
        vm.setQuery("sev")
        advanceTimeBy(299)
        runCurrent()
        assertEquals("sev", vm.query.value)
        assertTrue("no request before 300 ms of quiet", requests.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        awaitRealTime { vm.results.value != null }
        assertEquals(listOf("GET /api/search?q=sev"), requests.toList())
        assertEquals(listOf("Severance"), vm.results.value!!.shows.map { it.title })
        assertEquals(listOf("Se7en"), vm.results.value!!.movies.map { it.title })
    }

    @Test fun oneCharacterQueriesNeverSearchAndClearResults() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = SearchViewModel(api)
        vm.setQuery("sev")
        advanceTimeBy(301)
        runCurrent()
        awaitRealTime { vm.results.value != null }
        assertNotNull(vm.results.value)

        vm.setQuery("s")
        assertNull("a 1-char query clears the results at once", vm.results.value)
        advanceTimeBy(1_000)
        runCurrent()
        vm.setQuery(" s ")
        advanceTimeBy(1_000)
        runCurrent()
        delay(0)
        assertNull(vm.results.value)
        assertEquals(listOf("GET /api/search?q=sev"), requests.toList())
    }

    @Test fun emptyResultsAreAnEmptyResponseNotNull() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = SearchViewModel(api)
        vm.setQuery("zz")
        advanceTimeBy(301)
        runCurrent()
        awaitRealTime { vm.results.value != null }
        assertTrue(vm.results.value!!.shows.isEmpty() && vm.results.value!!.movies.isEmpty())
    }

    @Test fun openingCardsEmitsShowAndMoviePages() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val vm = SearchViewModel(api)
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navEvents.toList(events) }
        val show = ShowSummary(id = 3, category = "tv", title = "Severance")
        val movie = ItemSummary(id = 9, type = "movie", category = "movies", title = "Se7en")
        vm.open(Card.ShowCard(show))
        vm.open(Card.ItemCard(movie))
        assertEquals(listOf(NavEvent.Show(3), NavEvent.Movie(9)), events)
    }
}
