package app.pixelpantry.tv.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
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
 * Finds the server: at startup, and from an Unreachable screen's "Change server" ([changeServer]).
 *
 * At startup a stored address (the manual one, else the last known) is used at once — the app goes straight to
 * Home — and verified in the background on [verifyScope] (it outlives this screen); a failed check only calls
 * [onCachedUnreachable] (a toast), the pages handle a server that really is gone. Without a stored address the
 * resolver runs (manual → last known → mDNS); when it finds nothing the user types `host:port`.
 * With [changeServer] nothing is tried: the form shows, prefilled with the current address.
 * On success [ApiClient.baseUrl] is set before the state turns [UiState.Ready].
 */
class ConnectViewModel(
    private val locator: ServerLocator,
    private val api: ApiClient,
    private val store: ServerStore,
    private val probe: suspend (baseUrl: String) -> Boolean = ServerResolver.apiProbe(api),
    private val verifyScope: CoroutineScope? = null,
    /** Called with `host:port` when the stored address used at startup does not answer as Pixel Pantry. */
    private val onCachedUnreachable: (address: String) -> Unit = {},
    private val changeServer: Boolean = false,
) : ViewModel() {

    sealed interface ConnectError {
        data object InvalidAddress : ConnectError
        data class Unreachable(val address: String) : ConnectError
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
        data class Ready(val baseUrl: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Resolving())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastTried: List<String> = emptyList()
    private var draft: String = ""
    private var job: Job? = null

    init {
        if (changeServer) {
            val current = api.baseUrl.removePrefix("http://")
            draft = current
            lastTried = listOfNotNull(api.baseUrl.takeIf { it.isNotBlank() })
            _state.value = UiState.Failed(tried = lastTried, manualDraft = current, changingServer = true)
        } else {
            start()
        }
    }

    /** Startup: the stored address when there is one, else the resolver. */
    private fun start() {
        job = viewModelScope.launch {
            val cached = try {
                store.manualAddress.first()?.takeIf { it.isNotBlank() } ?: store.lastKnownAddress.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // unreadable storage: discover instead
            }
            if (cached == null) {
                resolveNow()
                return@launch
            }
            val url = "http://$cached"
            _state.value = ready(url)
            (verifyScope ?: viewModelScope).launch {
                if (!probe(url)) onCachedUnreachable(cached)
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
            val r = locator.resolve()
            ensureActive() // a retry or manual submit superseded this search; do not switch servers
            _state.value = when (r) {
                is ServerResolver.Result.Found -> ready(r.baseUrl)
                is ServerResolver.Result.NotFound -> {
                    lastTried = r.tried
                    UiState.Failed(tried = r.tried, manualDraft = draft)
                }
            }
        }
    }

    fun onDraftChange(text: String) {
        draft = text
        _state.update { if (it is UiState.Failed) it.copy(manualDraft = text, error = null) else it }
    }

    /** Validates, probes and (on success) stores the current draft address. */
    fun submitManual() {
        val address = draft
        val trimmed = address.trim()
        if (!ServerAddress.isValid(trimmed)) {
            _state.value = UiState.Failed(lastTried, manualDraft = address, error = ConnectError.InvalidAddress, changingServer = changeServer)
            return
        }
        job?.cancel()
        val url = "http://$trimmed"
        _state.value = UiState.Resolving(triedSoFar = listOf(url))
        job = viewModelScope.launch {
            val ok = probe(url)
            ensureActive() // superseded meanwhile; do not switch servers
            _state.value = if (ok) {
                // Storing is best-effort: a storage error must not undo a working connection.
                try {
                    store.setManualAddress(trimmed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Connected anyway; the next start discovers the server again.
                }
                ready(url)
            } else {
                UiState.Failed(lastTried, manualDraft = address, error = ConnectError.Unreachable(trimmed), changingServer = changeServer)
            }
        }
    }

    private fun ready(baseUrl: String): UiState.Ready {
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(
            locator, api, store,
            verifyScope = verifyScope, onCachedUnreachable = onCachedUnreachable, changeServer = changeServer,
        ) as T
    }
}
