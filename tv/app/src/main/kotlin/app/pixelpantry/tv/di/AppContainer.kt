package app.pixelpantry.tv.di

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.server.NsdDiscovery
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.settings.SettingsRepo
import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

private val Application.pantryDataStore: DataStore<Preferences> by preferencesDataStore(name = "pantry")

class AppContainer(private val app: Application) {
    val okHttp: OkHttpClient by lazy { ApiClient.defaultHttpClient() }
    val json: Json get() = ApiClient.defaultJson
    val api: ApiClient by lazy { ApiClient(okHttp, json) }
    val dataStore: DataStore<Preferences> get() = app.pantryDataStore
    val serverStore: ServerStore by lazy { ServerStore(dataStore) }
    val discovery: NsdDiscovery by lazy { NsdDiscovery(app) }
    val resolver: ServerResolver by lazy { ServerResolver(serverStore, discovery, ServerResolver.apiProbe(api)) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepo: SettingsRepo by lazy { SettingsRepo(api, serverStore, scope) }
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .okHttpClient(okHttp)
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("images"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
