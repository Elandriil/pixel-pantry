package app.pixelpantry.tv.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val http: OkHttpClient, private val json: Json) {
    @Volatile var baseUrl: String = ""

    /** Turns a server-relative path (e.g. "/img/p.jpg") into an absolute URL; absolute URLs pass through. */
    fun resolve(relative: String): String =
        if (relative.startsWith("http")) relative else baseUrl.trimEnd('/') + relative

    suspend fun info(): InfoResponse = get("/api/info")

    /**
     * `GET /api/info` on [candidate] instead of [baseUrl], which is left untouched: probing a server never
     * redirects requests made meanwhile (or after a cancelled probe) away from the current one.
     */
    suspend fun info(candidate: String): InfoResponse =
        execute("GET", "/api/info", null, emptyList(), InfoResponse.serializer(), candidate)
    suspend fun home(): CategoryPage = get("/api/home")
    suspend fun category(c: String): CategoryPage = get("/api/categories/$c")
    suspend fun show(id: Int): ShowDetail = get("/api/shows/$id")
    suspend fun item(id: Int): ItemDetail = get("/api/items/$id")
    suspend fun search(q: String): SearchResponse = get("/api/search", "q" to q)

    suspend fun progress(itemId: Int, position: Double, duration: Double, sessionId: Int?): ProgressResponse =
        send("POST", "/api/items/$itemId/progress", body {
            put("position", position)
            put("duration", duration)
            if (sessionId != null) put("sessionId", sessionId)
        })

    suspend fun setWatched(itemId: Int, watched: Boolean) {
        send<JsonElement>("POST", "/api/items/$itemId/watched", body { put("watched", watched) })
    }

    suspend fun setShowWatched(showId: Int, watched: Boolean, season: Int? = null) {
        send<JsonElement>("POST", "/api/shows/$showId/watched", body {
            put("watched", watched)
            if (season != null) put("season", season)
        })
    }

    suspend fun setTracks(itemId: Int, subtitle: String?, audio: String?) {
        send<JsonElement>("POST", "/api/items/$itemId/tracks", body {
            if (subtitle != null) put("subtitle", subtitle)
            if (audio != null) put("audio", audio)
        })
    }

    suspend fun subtitles(itemId: Int): SubtitleListResponse = get("/api/items/$itemId/subtitles")
    suspend fun searchSubtitles(itemId: Int): SubtitleSearchResponse = get("/api/items/$itemId/subtitles/search")

    suspend fun downloadSubtitle(itemId: Int, fileId: Long): SubtitleTrack =
        send("POST", "/api/items/$itemId/subtitles/download", body { put("fileId", fileId) })

    suspend fun deleteShow(id: Int): DeleteResponse = send("DELETE", "/api/shows/$id")
    suspend fun deleteMovie(id: Int): DeleteResponse = send("DELETE", "/api/movies/$id")
    suspend fun history(limit: Int = 200): HistoryResponse = get("/api/history", "limit" to limit.toString())
    suspend fun stats(): Stats = get("/api/stats")
    suspend fun settings(): Settings = get("/api/settings")

    suspend fun updateSettings(p: SettingsPatch): Settings =
        send("PUT", "/api/settings", json.encodeToString(SettingsPatch.serializer(), p).toRequestBody(JSON_TYPE))

    suspend fun scan(): ScanStatus = send<ScanEnvelope>("POST", "/api/scan").status
    suspend fun scanStatus(): ScanStatus = get("/api/scan/status")

    @Serializable
    private data class ScanEnvelope(val status: ScanStatus)

    private inline fun body(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): RequestBody =
        buildJsonObject(build).toString().toRequestBody(JSON_TYPE)

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String>): T =
        send("GET", path, null, query.toList())

    private suspend inline fun <reified T> send(
        method: String,
        path: String,
        body: RequestBody? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): T = execute(method, path, body, query, json.serializersModule.serializer<T>(), baseUrl)

    private suspend fun <T> execute(
        method: String,
        path: String,
        body: RequestBody?,
        query: List<Pair<String, String>>,
        serializer: KSerializer<T>,
        base: String,
    ): T = withContext(Dispatchers.IO) {
        val url = try {
            (base.trimEnd('/') + path).toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()
        } catch (e: IllegalArgumentException) {
            throw ApiException(0, "Cannot reach $base")
        }
        val request = Request.Builder().url(url).method(method, body ?: if (method == "GET") null else EMPTY_BODY).build()
        try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, errorMessage(text, resp.message, resp.code))
                json.decodeFromString(serializer, text)
            }
        } catch (e: IOException) {
            throw ApiException(0, "Cannot reach $base")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ApiException(0, "Unexpected response from $base")
        }
    }

    private fun errorMessage(text: String, reason: String, code: Int): String {
        val fromBody = runCatching {
            (json.parseToJsonElement(text) as JsonObject)["error"]?.jsonPrimitive?.content
        }.getOrNull()
        return fromBody ?: reason.ifBlank { "HTTP $code" }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        val defaultJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
