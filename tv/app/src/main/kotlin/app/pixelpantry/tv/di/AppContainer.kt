package app.pixelpantry.tv.di

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.library.LibraryScanner
import app.pixelpantry.tv.server.NsdDiscovery
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.show.ShowViewModel
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
    val toaster = Toaster(scope)
    val libraryScanner: LibraryScanner by lazy {
        LibraryScanner(
            api, toaster, scope,
            LibraryScanner.Strings(
                scanning = app.getString(R.string.scan_started),
                updated = app.getString(R.string.scan_done),
                scanFailed = { app.getString(R.string.scan_failed, it) },
            ),
        )
    }
    val showStrings: ShowViewModel.Strings by lazy {
        ShowViewModel.Strings(
            play = app.getString(R.string.show_play),
            resume = app.getString(R.string.show_resume),
            playEpisode = { app.getString(R.string.show_play_episode, it) },
            resumeEpisode = { tag, left -> app.getString(R.string.show_resume_episode, tag, left) },
            markSeasonWatched = app.getString(R.string.show_mark_season_watched),
            markSeasonUnwatched = app.getString(R.string.show_mark_season_unwatched),
            movedToRecycleBin = app.getString(R.string.delete_moved),
        )
    }
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
