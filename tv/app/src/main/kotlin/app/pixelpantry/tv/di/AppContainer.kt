package app.pixelpantry.tv.di

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.pixelpantry.tv.R
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.library.LibraryScanner
import app.pixelpantry.tv.player.PlaybackErrorTexts
import app.pixelpantry.tv.player.PlayerViewModel
import app.pixelpantry.tv.player.ProgressReporter
import app.pixelpantry.tv.server.NsdDiscovery
import app.pixelpantry.tv.server.ServerResolver
import app.pixelpantry.tv.ui.connect.ConnectViewModel
import app.pixelpantry.tv.server.ServerStore
import app.pixelpantry.tv.settings.SettingsRepo
import app.pixelpantry.tv.ui.components.Toaster
import app.pixelpantry.tv.ui.history.HistoryViewModel
import app.pixelpantry.tv.ui.settings.SettingsViewModel
import app.pixelpantry.tv.ui.show.ShowViewModel
import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

private val Application.pantryDataStore: DataStore<Preferences> by preferencesDataStore(name = "pantry")

/** [dataStoreOverride]: tests only; the app uses its `pantry` preferences file. */
class AppContainer(private val app: Application, private val dataStoreOverride: DataStore<Preferences>? = null) {
    val okHttp: OkHttpClient by lazy { ApiClient.defaultHttpClient() }
    val json: Json get() = ApiClient.defaultJson

    /**
     * Starts on the stored server address, so a page restored after process death (which skips the connect
     * screen) has a server to ask; the connect screen and Retry switch it as usual.
     */
    val api: ApiClient by lazy { ApiClient(okHttp, json).also { it.baseUrl = storedBaseUrl(serverStore) } }
    val dataStore: DataStore<Preferences> get() = dataStoreOverride ?: app.pantryDataStore
    val serverStore: ServerStore by lazy { ServerStore(dataStore) }
    val discovery: NsdDiscovery by lazy { NsdDiscovery(app) }
    val resolver: ServerResolver by lazy { ServerResolver(serverStore, discovery, ServerResolver.apiProbe(api)) }
    val scope: CoroutineScope = appScope { Log.e("PixelPantry", "Uncaught failure in the app scope", it) }
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
            couldNotRefresh = couldNotRefresh,
        )
    }
    val playerStrings: PlayerViewModel.Strings by lazy {
        PlayerViewModel.Strings(
            playbackFailed = app.getString(R.string.player_playback_failed),
            subtitlesOff = app.getString(R.string.player_subtitles_off),
            audioDefault = app.getString(R.string.player_audio_default),
            nextEpisode = { app.getString(R.string.player_next_episode, it) },
            previousEpisode = { app.getString(R.string.player_previous_episode, it) },
            noNextEpisode = app.getString(R.string.player_no_next_episode),
            noPreviousEpisode = app.getString(R.string.player_no_previous_episode),
            delaySidecarOnly = app.getString(R.string.player_delay_sidecar_only),
            sidecarFailed = app.getString(R.string.player_sidecar_failed),
            saveChoiceFailed = { app.getString(R.string.player_save_choice_failed, it) },
            audioUnsupported = app.getString(R.string.player_audio_unsupported),
            noCuesInFile = app.getString(R.string.player_no_cues_in_file),
        )
    }
    val historyStrings: HistoryViewModel.Strings by lazy {
        HistoryViewModel.Strings(
            today = app.getString(R.string.history_today),
            yesterday = app.getString(R.string.history_yesterday),
            couldNotRefresh = couldNotRefresh,
        )
    }
    val settingsStrings: SettingsViewModel.Strings by lazy {
        SettingsViewModel.Strings(
            connected = { app.getString(R.string.settings_connected, it) },
            noServerFound = app.getString(R.string.settings_no_server),
            invalidAddress = app.getString(R.string.connect_error_invalid),
            unreachable = { app.getString(R.string.connect_error_unreachable, it) },
            saveFailed = { app.getString(R.string.settings_save_failed, it) },
        )
    }
    val playbackErrorTexts: PlaybackErrorTexts by lazy {
        PlaybackErrorTexts(
            connectionLost = app.getString(R.string.player_error_connection),
            serverError = { app.getString(R.string.player_error_http, it) },
            notFound = app.getString(R.string.player_error_not_found),
            unsupportedFormat = app.getString(R.string.player_error_format),
            other = { message, code -> app.getString(R.string.player_error_other, message, code) },
        )
    }

    /** Toast when a page could not reload content it already shows. */
    val couldNotRefresh: String get() = app.getString(R.string.could_not_refresh)

    /**
     * The Unreachable screen's Retry: runs the resolver once (manual address, last known, mDNS) and switches
     * [ApiClient.baseUrl] when it finds the server; the page then reloads and shows Unreachable again if not.
     */
    suspend fun reconnect() {
        try {
            (resolver.resolve() as? ServerResolver.Result.Found)?.let { api.baseUrl = it.baseUrl }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PixelPantry", "Reconnect failed", e)
        }
    }

    /** The connect screen: at startup ([changeServer] false) or from an Unreachable screen's "Change server". */
    fun connectFactory(changeServer: Boolean) = ConnectViewModel.Factory(
        resolver, api, serverStore, scope,
        onCachedUnreachable = { toaster.show(app.getString(R.string.server_not_reachable, it)) },
        changeServer = changeServer,
    )

    /** One reporter per playback screen. */
    fun progressReporter(): ProgressReporter =
        ProgressReporter(api, clock = SystemClock::elapsedRealtime, onError = { Log.w("ProgressReporter", it) })

    companion object {
        /**
         * `http://` + the stored manual address, else the last known one; "" when there is none or the store
         * cannot be read. One blocking read, at startup.
         */
        internal fun storedBaseUrl(store: ServerStore): String = runCatching {
            runBlocking { store.manualAddress.first()?.takeIf { it.isNotBlank() } ?: store.lastKnownAddress.first() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "http://$it" } ?: ""
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
