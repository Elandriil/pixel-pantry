package app.pixelpantry.tv.ui.settings

import app.pixelpantry.tv.server.resolveOrNotFound
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The settings page.
 *
 * Skip lengths change on screen at once; rapid presses on one stepper are coalesced into a single save of the
 * latest value [SAVE_DEBOUNCE_MS] after the last press. A failed save toasts and the row goes back to the
 * server's value. Autoplay saves on each toggle. Every save sends only the changed key.
 *
 * The server address can be typed (`host:port`, probed before use) or handed back to automatic discovery.
 * Probes never touch [ApiClient.baseUrl]; it switches only after a probe succeeds and was not cancelled. While
 * one address operation runs ([connecting]) new ones are ignored; cancelling the editor cancels it.
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

    /** Values not yet confirmed by the server (null = none), shown over the saved settings. */
    private val pending = MutableStateFlow(SettingsPatch())

    /** The saved settings with any pending stepper values on top. */
    val settings: StateFlow<Settings> = combine(settingsRepo.settings, pending) { s, p -> s.overlaid(p) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepo.settings.value)

    private val _serverAddress = MutableStateFlow(api.baseUrl)
    /** The base URL the app talks to right now. */
    val serverAddress: StateFlow<String> = _serverAddress.asStateFlow()

    val manualAddress: StateFlow<String?> = store.manualAddress
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _editingAddress = MutableStateFlow(false)
    /** Whether the manual address field is open. */
    val editingAddress: StateFlow<Boolean> = _editingAddress.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    /** An address probe or discovery is running; address actions are ignored meanwhile. */
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    // Saves run one at a time, so a stepper save and a toggle never race on the server or the cache.
    private val saveLock = Mutex()
    private var skipBackSave: Job? = null
    private var skipForwardSave: Job? = null
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

    fun adjustSkipBack(delta: Int) {
        val target = stepped(currentSkipBack(), delta) ?: return
        pending.update { it.copy(skipBackSeconds = target) }
        skipBackSave?.cancel()
        skipBackSave = saveLater(SettingsPatch(skipBackSeconds = target)) { p ->
            if (p.skipBackSeconds == target) p.copy(skipBackSeconds = null) else p
        }
    }

    fun adjustSkipForward(delta: Int) {
        val target = stepped(currentSkipForward(), delta) ?: return
        pending.update { it.copy(skipForwardSeconds = target) }
        skipForwardSave?.cancel()
        skipForwardSave = saveLater(SettingsPatch(skipForwardSeconds = target)) { p ->
            if (p.skipForwardSeconds == target) p.copy(skipForwardSeconds = null) else p
        }
    }

    fun toggleAutoplay() {
        viewModelScope.launch {
            saveLock.withLock {
                // From the latest saved value, so quick double presses toggle twice.
                save(SettingsPatch(autoplayNext = !settingsRepo.settings.value.autoplayNext))
            }
        }
    }

    fun startEditingAddress() {
        _editingAddress.value = true
    }

    /** Closes the editor and cancels a running probe; the current server stays. */
    fun cancelEditingAddress() {
        // The cancelled job may still be waiting on its HTTP call, but it can no longer switch servers (it
        // checks for cancellation first), so the row is free again at once.
        addressJob?.cancel()
        addressJob = null
        _connecting.value = false
        _editingAddress.value = false
    }

    /** Validates and probes [address]; on success stores it as the manual address and switches to it. */
    fun setManualAddress(address: String) {
        if (_connecting.value) return
        val trimmed = address.trim()
        if (!ServerAddress.isValid(trimmed)) {
            toaster.show(strings.invalidAddress)
            return
        }
        val url = "http://$trimmed"
        runAddressJob {
            val ok = probe(url)
            ensureActive() // cancelled meanwhile: never switch
            if (!ok) {
                toaster.show(strings.unreachable(trimmed))
                return@runAddressJob
            }
            // Storing is best-effort: a storage error must not undo a working connection.
            try { store.setManualAddress(trimmed) } catch (e: IOException) { }
            switchTo(url)
            _editingAddress.value = false
        }
    }

    /**
     * Forgets the manual address and resolves again. With nothing found, the manual address is put back and
     * the current server stays.
     */
    fun useAutomaticDiscovery() {
        if (_connecting.value) return
        val previousManual = manualAddress.value
        _editingAddress.value = false
        runAddressJob {
            try { store.setManualAddress(null) } catch (e: IOException) { }
            val r = resolver.resolveOrNotFound()
            ensureActive()
            when (r) {
                is ServerResolver.Result.Found -> switchTo(r.baseUrl)
                is ServerResolver.Result.NotFound -> {
                    try { store.setManualAddress(previousManual) } catch (e: IOException) { }
                    toaster.show(strings.noServerFound)
                }
            }
        }
    }

    private fun runAddressJob(block: suspend CoroutineScope.() -> Unit) {
        _connecting.value = true
        // Lazy, so the job is recorded before it can run (and possibly finish) on an immediate dispatcher.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY, block = block)
        addressJob = job
        job.invokeOnCompletion { if (addressJob === job) _connecting.value = false }
        job.start()
    }

    private suspend fun switchTo(url: String) {
        api.baseUrl = url
        _serverAddress.value = url
        toaster.show(strings.connected(url.removePrefix("http://")))
        try { settingsRepo.refresh() } catch (e: ApiException) { } catch (e: IOException) { }
    }

    /**
     * Saves [patch] after [SAVE_DEBOUNCE_MS] unless cancelled by a newer press first; then [clear] drops the
     * pending value it covered (success shows the saved value, failure falls back to the server's).
     */
    private fun saveLater(patch: SettingsPatch, clear: (SettingsPatch) -> SettingsPatch): Job =
        viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            try {
                saveLock.withLock { save(patch) }
            } finally {
                pending.update(clear)
            }
        }

    private suspend fun save(patch: SettingsPatch) {
        try {
            settingsRepo.update(patch)
        } catch (e: ApiException) {
            toaster.show(strings.saveFailed(e.message.orEmpty()))
        } catch (e: IOException) {
            // Saved on the server; only the local cache write failed.
        }
    }

    // Read straight from the sources: the combined flow may not have caught up with a press made a moment ago.
    private fun currentSkipBack() = pending.value.skipBackSeconds ?: settingsRepo.settings.value.skipBackSeconds
    private fun currentSkipForward() = pending.value.skipForwardSeconds ?: settingsRepo.settings.value.skipForwardSeconds

    /** [current] moved by [delta] within [MIN_SKIP]..[MAX_SKIP], or null when that changes nothing. */
    private fun stepped(current: Int, delta: Int): Int? =
        (current + delta).coerceIn(MIN_SKIP, MAX_SKIP).takeIf { it != current }

    private fun Settings.overlaid(p: SettingsPatch) = Settings(
        skipBackSeconds = p.skipBackSeconds ?: skipBackSeconds,
        skipForwardSeconds = p.skipForwardSeconds ?: skipForwardSeconds,
        autoplayNext = p.autoplayNext ?: autoplayNext,
    )

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
        const val SAVE_DEBOUNCE_MS = 300L
    }
}
