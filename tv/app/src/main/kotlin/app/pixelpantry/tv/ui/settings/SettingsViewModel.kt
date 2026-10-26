package app.pixelpantry.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.ApiException
import app.pixelpantry.tv.api.Settings
import app.pixelpantry.tv.api.SettingsPatch
import app.pixelpantry.tv.server.ServerAddress
import app.pixelpantry.tv.server.ServerLocator
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The settings page. Skip lengths and autoplay save to the server immediately, one key per change; a failed
 * save toasts and the old value stays. The server address can be typed (`host:port`, probed before use) or
 * handed back to automatic discovery.
 */
class SettingsViewModel(
    private val settingsRepo: SettingsRepo,
    private val store: ServerStore,
    private val resolver: ServerLocator,
    private val api: ApiClient,
    private val toaster: Toaster,
    private val strings: Strings,
    val version: String,
    private val probe: suspend (baseUrl: String) -> Boolean = ServerResolver.apiProbe(api),
) : ViewModel() {

    data class Strings(
        val connected: (address: String) -> String,
        val noServerFound: String,
        val invalidAddress: String,
        val unreachable: (address: String) -> String,
        val saveFailed: (message: String) -> String,
    )

    val settings: StateFlow<Settings> = settingsRepo.settings

    private val _serverAddress = MutableStateFlow(api.baseUrl)
    /** The base URL the app talks to right now. */
    val serverAddress: StateFlow<String> = _serverAddress.asStateFlow()

    val manualAddress: StateFlow<String?> = store.manualAddress
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _editingAddress = MutableStateFlow(false)
    /** Whether the manual address field is open. */
    val editingAddress: StateFlow<Boolean> = _editingAddress.asStateFlow()

    // Saves run one at a time and each computes its value from the latest saved settings, so quick repeated
    // presses accumulate (10 → 15 → 20) instead of all sending the same value.
    private val saveLock = Mutex()
    private var addressJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                settingsRepo.refresh()
            } catch (e: ApiException) {
                // The cached values stay on screen; a change will report its own failure.
            } catch (e: IOException) {
                // Cache write failed; the fetched values are already published.
            }
        }
    }

    fun adjustSkipBack(delta: Int) = save { s ->
        stepped(s.skipBackSeconds, delta)?.let { SettingsPatch(skipBackSeconds = it) }
    }

    fun adjustSkipForward(delta: Int) = save { s ->
        stepped(s.skipForwardSeconds, delta)?.let { SettingsPatch(skipForwardSeconds = it) }
    }

    fun toggleAutoplay() = save { s -> SettingsPatch(autoplayNext = !s.autoplayNext) }

    fun startEditingAddress() {
        _editingAddress.value = true
    }

    fun cancelEditingAddress() {
        _editingAddress.value = false
    }

    /** Validates and probes [address]; on success stores it as the manual address and switches to it. */
    fun setManualAddress(address: String) {
        val trimmed = address.trim()
        if (!ServerAddress.isValid(trimmed)) {
            toaster.show(strings.invalidAddress)
            return
        }
        val url = "http://$trimmed"
        addressJob?.cancel()
        addressJob = viewModelScope.launch {
            if (!probe(url)) {
                toaster.show(strings.unreachable(trimmed))
                return@launch
            }
            // Storing is best-effort: a storage error must not undo a working connection.
            try { store.setManualAddress(trimmed) } catch (e: IOException) { }
            switchTo(url)
            _editingAddress.value = false
        }
    }

    /** Forgets the manual address and resolves again; with nothing found the current address stays. */
    fun useAutomaticDiscovery() {
        addressJob?.cancel()
        addressJob = viewModelScope.launch {
            try { store.setManualAddress(null) } catch (e: IOException) { }
            _editingAddress.value = false
            when (val r = resolver.resolve()) {
                is ServerResolver.Result.Found -> switchTo(r.baseUrl)
                is ServerResolver.Result.NotFound -> toaster.show(strings.noServerFound)
            }
        }
    }

    private suspend fun switchTo(url: String) {
        api.baseUrl = url
        _serverAddress.value = url
        toaster.show(strings.connected(url.removePrefix("http://")))
        try { settingsRepo.refresh() } catch (e: ApiException) { } catch (e: IOException) { }
    }

    /** Saves the patch [change] derives from the current settings; null means nothing to change. */
    private fun save(change: (Settings) -> SettingsPatch?) {
        viewModelScope.launch {
            saveLock.withLock {
                val patch = change(settings.value) ?: return@withLock
                try {
                    settingsRepo.update(patch)
                } catch (e: ApiException) {
                    toaster.show(strings.saveFailed(e.message.orEmpty()))
                } catch (e: IOException) {
                    // Saved on the server; only the local cache write failed.
                }
            }
        }
    }

    /** [current] moved by [delta] within [MIN_SKIP]..[MAX_SKIP], or null when that changes nothing. */
    private fun stepped(current: Int, delta: Int): Int? =
        (current + delta).coerceIn(MIN_SKIP, MAX_SKIP).takeIf { it != current }

    class Factory(
        private val settingsRepo: SettingsRepo,
        private val store: ServerStore,
        private val resolver: ServerLocator,
        private val api: ApiClient,
        private val toaster: Toaster,
        private val strings: Strings,
        private val version: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(settingsRepo, store, resolver, api, toaster, strings, version) as T
    }

    companion object {
        const val STEP = 5
        const val MIN_SKIP = 5
        const val MAX_SKIP = 120
    }
}
