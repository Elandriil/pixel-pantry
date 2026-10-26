package app.pixelpantry.tv.server

import app.pixelpantry.tv.api.ApiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

class ServerResolver(
    private val store: ServerStore,
    private val discovery: Discovery,
    private val probe: suspend (baseUrl: String) -> Boolean,
) {
    enum class Source { MANUAL, LAST_KNOWN, MDNS }

    sealed class Result {
        data class Found(val baseUrl: String, val source: Source) : Result()
        data class NotFound(val tried: List<String>) : Result()
    }

    private suspend fun remember(found: Result.Found?): Result.Found? {
        found?.let { store.setLastKnownAddress(it.baseUrl.removePrefix("http://")) }
        return found
    }

    suspend fun resolve(): Result {
        val tried = mutableListOf<String>()

        suspend fun attempt(address: String, source: Source): Result.Found? {
            val url = "http://$address"
            tried += url
            return if (probe(url)) Result.Found(url, source) else null
        }

        store.manualAddress.first()?.takeIf { it.isNotBlank() }?.let { manual ->
            return remember(attempt(manual, Source.MANUAL)) ?: Result.NotFound(tried)
        }
        store.lastKnownAddress.first()?.let { last ->
            remember(attempt(last, Source.LAST_KNOWN))?.let { return it }
        }

        var found: Result.Found? = null
        withTimeoutOrNull(MDNS_TIMEOUT_MS) {
            discovery.candidates(MDNS_TIMEOUT_MS).firstOrNull { c ->
                found = attempt("${c.host}:${c.port}", Source.MDNS)
                found != null
            }
        }
        return remember(found) ?: Result.NotFound(tried)
    }

    companion object {
        const val MDNS_TIMEOUT_MS = 5_000L

        /** Production probe: the candidate must answer /api/info as "Pixel Pantry". */
        fun apiProbe(api: ApiClient): suspend (String) -> Boolean = { url ->
            val previous = api.baseUrl
            api.baseUrl = url
            try {
                api.info().name == "Pixel Pantry"
            } catch (e: Exception) {
                false
            } finally {
                api.baseUrl = previous
            }
        }
    }
}
