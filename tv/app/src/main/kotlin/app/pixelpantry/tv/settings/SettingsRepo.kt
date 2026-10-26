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

    init {
        scope.launch {
            // Seed from cache only if nothing fresher has been emitted meanwhile.
            val cached = store.cachedSettings.first() ?: return@launch
            _settings.compareAndSet(DEFAULTS, cached)
        }
    }

    suspend fun refresh() = publish(api.settings())

    suspend fun update(patch: SettingsPatch) = publish(api.updateSettings(patch))

    private suspend fun publish(s: Settings) {
        _settings.value = s
        store.setCachedSettings(s)
    }

    private companion object {
        val DEFAULTS = Settings(skipBackSeconds = 10, skipForwardSeconds = 10, autoplayNext = true)
    }
}
