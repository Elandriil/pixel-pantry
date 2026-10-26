package app.pixelpantry.tv.server

import app.pixelpantry.tv.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/** Finds the Pixel Pantry server; the seam the connect screen depends on. */
fun interface ServerLocator {
    suspend fun resolve(): ServerResolver.Result
}

/**
 * [ServerLocator.resolve] that never throws but for cancellation: a failure (e.g. the address store cannot be
 * read, an `IOException` from DataStore) counts as nothing found, with nothing tried.
 */
suspend fun ServerLocator.resolveOrNotFound(): ServerResolver.Result = try {
    resolve()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ServerResolver.Result.NotFound(tried = emptyList())
}

class ServerResolver(
    private val store: ServerStore,
    private val discovery: Discovery,
    private val probe: suspend (baseUrl: String) -> Boolean,
) : ServerLocator {
    enum class Source { MANUAL, LAST_KNOWN, MDNS }

    sealed class Result {
        data class Found(val baseUrl: String, val source: Source) : Result()
        data class NotFound(val tried: List<String>) : Result()
    }

    private suspend fun remember(found: Result.Found?): Result.Found? {
        found?.let { store.setLastKnownAddress(it.baseUrl.removePrefix("http://")) }
        return found
    }

    override suspend fun resolve(): Result {
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
        val seen = mutableSetOf<String>()
        store.lastKnownAddress.first()?.let { seen += it }
        withTimeoutOrNull(MDNS_TIMEOUT_MS) {
            discovery.candidates(MDNS_TIMEOUT_MS).firstOrNull { c ->
                val address = "${c.host}:${c.port}"
                if (!seen.add(address)) return@firstOrNull false
                found = attempt(address, Source.MDNS)
                found != null
            }
        }
        return remember(found) ?: Result.NotFound(tried)
    }

    companion object {
        const val MDNS_TIMEOUT_MS = 5_000L

        /**
         * Production probe: the candidate must answer /api/info as "Pixel Pantry". It asks the candidate directly
         * and never changes [ApiClient.baseUrl].
         */
        fun apiProbe(api: ApiClient): suspend (String) -> Boolean = { url ->
            try {
                api.info(url).name == "Pixel Pantry"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }
}
