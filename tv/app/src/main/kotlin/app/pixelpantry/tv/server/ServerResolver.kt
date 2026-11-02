package app.pixelpantry.tv.server

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.InfoResponse
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

/**
 * The three places a server can come from, in order. With a pairing stored, the last known address and mDNS
 * announcements are only adopted when they answer with the paired server's id, so another "Pixel Pantry" on the
 * network cannot take the place of the owner's server; a typed (manual) address is always adopted and leads to
 * pairing when it is a different server.
 */
class ServerResolver(
    private val store: ServerStore,
    private val discovery: Discovery,
    /** `/api/info` of the candidate, or null when nothing (or something else) answers there. */
    private val probe: suspend (baseUrl: String) -> InfoResponse?,
) : ServerLocator {
    enum class Source { MANUAL, LAST_KNOWN, MDNS }

    sealed class Result {
        /** [serverId] is null for a server from before pairing existed. */
        data class Found(val baseUrl: String, val source: Source, val serverId: String?) : Result()
        data class NotFound(val tried: List<String>) : Result()
    }

    private suspend fun remember(found: Result.Found?): Result.Found? {
        found?.let { store.setLastKnownAddress(it.baseUrl.removePrefix("http://")) }
        return found
    }

    override suspend fun resolve(): Result {
        val tried = mutableListOf<String>()
        val pairedId = store.pairing.first()?.serverId

        suspend fun attempt(address: String, source: Source): Result.Found? {
            val url = "http://$address"
            tried += url
            val info = probe(url) ?: return null
            // automatic sources must be the paired server; the owner's typed address is taken as it is
            if (pairedId != null && source != Source.MANUAL && info.serverId != pairedId) return null
            return Result.Found(url, source, info.serverId)
        }

        // A manual address wins while it answers. When it stops answering (the PC got a new DHCP address),
        // discovery still runs, so the owner is not stuck re-typing an address the server can announce itself.
        val manual = store.manualAddress.first()?.takeIf { it.isNotBlank() }
        if (manual != null) {
            remember(attempt(manual, Source.MANUAL))?.let { return it }
        } else {
            store.lastKnownAddress.first()?.let { last ->
                remember(attempt(last, Source.LAST_KNOWN))?.let { return it }
            }
        }

        var found: Result.Found? = null
        val seen = mutableSetOf<String>()
        manual?.let { seen += it }
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
        /** Android's NSD takes a few seconds to start and resolve on some TVs; 5 s missed the Bravia's answer. */
        const val MDNS_TIMEOUT_MS = 12_000L

        /**
         * Production probe: the candidate must answer /api/info as "Pixel Pantry". It asks the candidate directly
         * and never changes [ApiClient.baseUrl].
         */
        fun apiProbe(api: ApiClient): suspend (String) -> InfoResponse? = { url ->
            try {
                api.info(url).takeIf { it.name == "Pixel Pantry" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}
