package app.pixelpantry.tv.settings

import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.Settings
import app.pixelpantry.tv.api.SettingsPatch
import app.pixelpantry.tv.server.ServerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsRepo(
    private val api: ApiClient,
    private val store: ServerStore,
    scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow(DEFAULTS)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // Cache is read first; refresh/update wait for it, so a fresher value is never overwritten by the cache.
    // A storage error must not escape into the app scope: the defaults simply stay in place.
    private val seed = scope.launch {
        runCatching { store.cachedSettings.first() }.getOrNull()?.let { _settings.value = it }
    }

    suspend fun refresh() = publish(api.settings())

    suspend fun update(patch: SettingsPatch) = publish(api.updateSettings(patch))

    private suspend fun publish(s: Settings) {
        seed.join()
        _settings.value = s
        store.setCachedSettings(s)
    }

    private companion object {
        val DEFAULTS = Settings(skipBackSeconds = 10, skipForwardSeconds = 10, autoplayNext = true)
    }
}
