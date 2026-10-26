package app.pixelpantry.tv.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.server.ServerLocator
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * First-run flow: find the server automatically, or let the user type `host:port`.
 * On success [ApiClient.baseUrl] is set before the state turns [UiState.Ready].
 */
class ConnectViewModel(
    private val locator: ServerLocator,
    private val api: ApiClient,
    private val store: ServerStore,
    private val probe: suspend (baseUrl: String) -> Boolean = ServerResolver.apiProbe(api),
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
        ) : UiState
        data class Ready(val baseUrl: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Resolving())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastTried: List<String> = emptyList()
    private var draft: String = ""
    private var job: Job? = null

    init {
        retry()
    }

    fun retry() {
        job?.cancel()
        _state.value = UiState.Resolving()
        job = viewModelScope.launch {
            _state.value = when (val r = locator.resolve()) {
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
        if (!isValidAddress(trimmed)) {
            _state.value = UiState.Failed(lastTried, manualDraft = address, error = ConnectError.InvalidAddress)
            return
        }
        job?.cancel()
        val url = "http://$trimmed"
        _state.value = UiState.Resolving(triedSoFar = listOf(url))
        job = viewModelScope.launch {
            _state.value = if (probe(url)) {
                // Storing is best-effort: a storage error must not undo a working connection.
                runCatching { store.setManualAddress(trimmed) }
                ready(url)
            } else {
                UiState.Failed(lastTried, manualDraft = address, error = ConnectError.Unreachable(trimmed))
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectViewModel(locator, api, store) as T
    }

    companion object {
        /** `host:port` with a non-empty host, no scheme or path, and a port in 1..65535. */
        fun isValidAddress(address: String): Boolean {
            val colon = address.lastIndexOf(':')
            if (colon <= 0) return false
            val host = address.substring(0, colon)
            val port = address.substring(colon + 1).toIntOrNull() ?: return false
            if (port !in 1..65535) return false
            return host.none { it.isWhitespace() || it == '/' || it == ':' || it == '@' }
        }
    }
}
