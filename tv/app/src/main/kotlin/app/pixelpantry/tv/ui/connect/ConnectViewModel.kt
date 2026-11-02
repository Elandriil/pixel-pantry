package app.pixelpantry.tv.ui.connect

import app.pixelpantry.tv.server.resolveOrNotFound
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.InfoResponse
import app.pixelpantry.tv.server.Pairing
import app.pixelpantry.tv.server.ServerAddress
import app.pixelpantry.tv.server.ServerLocator
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Finds the server and makes sure the TV is paired with it: at startup, from an Unreachable screen's "Change
 * server" ([changeServer]) and from Settings when a typed address turns out to be a server this TV has not paired
 * with ([pairAddress]).
 *
 * At startup a stored address (the manual one, else the last known) is used at once when a pairing is stored
 * too — the app goes straight to Home — and verified in the background on [verifyScope] (it outlives this
 * screen); a failed check only calls [onCachedUnreachable] (a toast), the pages handle a server that really is
 * gone. Otherwise the resolver runs (manual → last known → mDNS); when it finds nothing the user types `host:port`.
 *
 * A found server is adopted ([UiState.Ready], [ApiClient.baseUrl] and [ApiClient.token] set) only when its id is
 * the one the stored pairing names. Any other server — the first one ever, a replaced PC, a reset pairing — asks
 * for the PIN from its admin page ([UiState.Pairing]); the right PIN stores the new pairing and adopts the server.
 * With [changeServer] nothing is tried: the form shows, prefilled with the current address.
 */
class ConnectViewModel(
    private val locator: ServerLocator,
    private val api: ApiClient,
    private val store: ServerStore,
    private val probe: suspend (baseUrl: String) -> InfoResponse? = ServerResolver.apiProbe(api),
    private val verifyScope: CoroutineScope? = null,
    /** Called with `host:port` when the stored address used at startup does not answer as Pixel Pantry. */
    private val onCachedUnreachable: (address: String) -> Unit = {},
    private val changeServer: Boolean = false,
    /** `host:port` of a server to pair with right away (from Settings); nothing else is tried first. */
    private val pairAddress: String? = null,
) : ViewModel() {

    sealed interface ConnectError {
        data object InvalidAddress : ConnectError
        data class Unreachable(val address: String) : ConnectError
    }

    sealed interface PairError {
        data object InvalidPin : PairError
        data object WrongPin : PairError
        /** Too many wrong PINs: the server refuses pairing for a minute. */
        data object Locked : PairError
        data object Unreachable : PairError
        data class Other(val message: String) : PairError
    }

    sealed interface UiState {
        data class Resolving(val triedSoFar: List<String> = emptyList()) : UiState
        data class Failed(
            val tried: List<String>,
            val manualDraft: String,
            val error: ConnectError? = null,
            /** Opened by "Change server" (titled so), not because discovery found nothing. */
            val changingServer: Boolean = false,
        ) : UiState
        /** A Pixel Pantry at [baseUrl] that this TV is not paired with: it wants the PIN from its admin page. */
        data class Pairing(
            val baseUrl: String,
            val serverId: String?,
            val pinDraft: String = "",
            val error: PairError? = null,
            /** The PIN is being checked. */
            val busy: Boolean = false,
        ) : UiState
        data class Ready(val baseUrl: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Resolving())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastTried: List<String> = emptyList()
    private var draft: String = ""
    private var pin: String = ""
    private var job: Job? = null

    init {
        when {
            pairAddress != null -> {
                draft = pairAddress
                job = viewModelScope.launch { probeAndAdopt(pairAddress, storeManual = false) }
            }
            changeServer -> {
                val current = api.baseUrl.removePrefix("http://")
                draft = current
                lastTried = listOfNotNull(api.baseUrl.takeIf { it.isNotBlank() })
                _state.value = UiState.Failed(tried = lastTried, manualDraft = current, changingServer = true)
            }
            else -> start()
        }
    }

    /** Startup: the stored address when there is one and a pairing to go with it, else the resolver. */
    private fun start() {
        job = viewModelScope.launch {
            val (cached, pairing) = try {
                val address = store.manualAddress.first()?.takeIf { it.isNotBlank() } ?: store.lastKnownAddress.first()
                address to store.pairing.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to null // unreadable storage: discover instead
            }
            if (cached == null || pairing == null) {
                resolveNow()
                return@launch
            }
            val url = "http://$cached"
            _state.value = ready(url, pairing.token)
            (verifyScope ?: viewModelScope).launch {
                if (probe(url) == null) onCachedUnreachable(cached)
            }
        }
    }

    fun retry() {
        job?.cancel()
        _state.value = UiState.Resolving()
        job = viewModelScope.launch { resolveNow() }
    }

    private suspend fun resolveNow() {
        coroutineScope {
            val r = locator.resolveOrNotFound()
            ensureActive() // a retry or manual submit superseded this search; do not switch servers
            when (r) {
                is ServerResolver.Result.Found -> adopt(r.baseUrl, r.serverId)
                is ServerResolver.Result.NotFound -> {
                    lastTried = r.tried
                    _state.value = UiState.Failed(tried = r.tried, manualDraft = draft)
                }
            }
        }
    }

    /** A Pixel Pantry answered at [baseUrl]: Ready when it is the paired server, else the PIN screen. */
    private suspend fun adopt(baseUrl: String, serverId: String?) {
        val pairing = try {
            store.pairing.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // unreadable storage: pair again, the PIN is on the admin page
        }
        _state.value = if (pairing != null && serverId != null && pairing.serverId == serverId) {
            ready(baseUrl, pairing.token)
        } else {
            lastTried = listOf(baseUrl)
            pin = ""
            UiState.Pairing(baseUrl, serverId)
        }
    }

    fun onDraftChange(text: String) {
        draft = text
        _state.update { if (it is UiState.Failed) it.copy(manualDraft = text, error = null) else it }
    }

    /** Validates, probes and (on success) stores the current draft address, then adopts or pairs. */
    fun submitManual() {
        val address = draft
        val trimmed = address.trim()
        if (!ServerAddress.isValid(trimmed)) {
            _state.value = UiState.Failed(lastTried, manualDraft = address, error = ConnectError.InvalidAddress, changingServer = changeServer)
            return
        }
        job?.cancel()
        _state.value = UiState.Resolving(triedSoFar = listOf("http://$trimmed"))
        job = viewModelScope.launch { probeAndAdopt(trimmed, storeManual = true) }
    }

    private suspend fun probeAndAdopt(address: String, storeManual: Boolean) {
        val url = "http://$address"
        val info = probe(url)
        coroutineScope { ensureActive() } // superseded meanwhile; do not switch servers
        if (info == null) {
            lastTried = lastTried.ifEmpty { listOf(url) }
            _state.value = UiState.Failed(lastTried, manualDraft = address, error = ConnectError.Unreachable(address), changingServer = changeServer)
            return
        }
        // Storing is best-effort: a storage error must not undo a working connection.
        if (storeManual) {
            try {
                store.setManualAddress(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Connected anyway; the next start discovers the server again.
            }
        }
        adopt(url, info.serverId)
    }

    fun onPinChange(text: String) {
        pin = text
        _state.update { if (it is UiState.Pairing) it.copy(pinDraft = text, error = null) else it }
    }

    /** Sends the PIN to the server shown; the right one stores the pairing and adopts the server. */
    fun submitPin() {
        val current = _state.value as? UiState.Pairing ?: return
        if (current.busy) return
        val entered = pin
        if (!PIN.matches(entered)) {
            _state.value = current.copy(pinDraft = entered, error = PairError.InvalidPin)
            return
        }
        _state.value = current.copy(pinDraft = entered, error = null, busy = true)
        job?.cancel()
        job = viewModelScope.launch {
            val error: PairError? = try {
                val r = api.pair(current.baseUrl, entered)
                coroutineScope { ensureActive() }
                try {
                    store.setPairing(Pairing(r.token, r.serverId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Paired for this run; the next start asks for the PIN again.
                }
                _state.value = ready(current.baseUrl, r.token)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                when (e.status) {
                    403 -> PairError.WrongPin
                    429 -> PairError.Locked
                    0 -> PairError.Unreachable
                    else -> PairError.Other(e.message.orEmpty())
                }
            }
            if (error != null) _state.value = current.copy(pinDraft = entered, error = error, busy = false)
        }
    }

    /** Back to the address form, prefilled with the server that asked for the PIN. */
    fun cancelPairing() {
        val current = _state.value as? UiState.Pairing ?: return
        job?.cancel()
        draft = current.baseUrl.removePrefix("http://")
        _state.value = UiState.Failed(tried = lastTried, manualDraft = draft, changingServer = changeServer)
    }

    private fun ready(baseUrl: String, token: String): UiState.Ready {
        api.token = token
        api.baseUrl = baseUrl
        return UiState.Ready(baseUrl)
    }

    class Factory(
        private val locator: ServerLocator,
        private val api: ApiClient,
        private val store: ServerStore,
        private val verifyScope: CoroutineScope,
        private val onCachedUnreachable: (address: String) -> Unit,
        private val changeServer: Boolean,
        private val pairAddress: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(
            locator, api, store,
            verifyScope = verifyScope, onCachedUnreachable = onCachedUnreachable, changeServer = changeServer, pairAddress = pairAddress,
        ) as T
    }

    private companion object {
        val PIN = Regex("^\\d{6}$")
    }
}
