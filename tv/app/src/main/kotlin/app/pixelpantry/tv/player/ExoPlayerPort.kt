package app.pixelpantry.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.ParserException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Plain-words texts for playback failures; supplied from string resources. */
class PlaybackErrorTexts(
    val connectionLost: String,
    val serverError: (code: Int) -> String,
    val notFound: String,
    val unsupportedFormat: String,
    val other: (message: String, code: String) -> String,
)

/**
 * [PlayerPort] over ExoPlayer, direct-playing the server's stream URL.
 *
 * A lost server must surface as an error within seconds (Review Focus 3), but ExoPlayer only reports a failed
 * load once its buffer has drained, which can be most of a minute. So the load error policy retries every
 * 1–2 s and gives up once a load has kept failing for [GIVE_UP_AFTER_MS] (see [LoadErrorBudget] for when a new
 * run of failures starts), then reports the failure itself. A Wi-Fi blip of a few seconds is ridden out; a dead server shows in about 10 s.
 *
 * Sidecar subtitle files never end playback: a file deleted after the scan, a failed `?shift=` or a malformed
 * download only drops that subtitle. [SidecarTolerantSourceFactory] loads each sidecar as a
 * `SingleSampleMediaSource` that treats load errors as end of stream, and the policy gives up on a sidecar load at
 * once (outside the budget) and reports it through [PlayerListener.onSidecarFailed]. Sidecars then reach the
 * text renderer unparsed, so it decodes them itself (legacy decoding); embedded tracks are still parsed during
 * extraction.
 */
@OptIn(UnstableApi::class)
class ExoPlayerPort(context: Context, private val texts: PlaybackErrorTexts) : PlayerPort {
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val main = Handler(Looper.getMainLooper())
    private var failed = false

    private val budget = LoadErrorBudget(giveUpAfterMs = GIVE_UP_AFTER_MS)

    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val uri = info.loadEventInfo.dataSpec.uri.toString()
            if (isSidecar(uri)) {
                main.post { listeners.forEach { it.onSidecarFailed(uri) } }
                return C.TIME_UNSET // end of stream for that sidecar (treatLoadErrorsAsEndOfStream), not an error
            }
            val fatal = super.getRetryDelayMsFor(info) == C.TIME_UNSET
            val decision = budget.onError(info.loadEventInfo.bytesLoaded, SystemClock.elapsedRealtime())
            if (!fatal && decision is LoadErrorBudget.Decision.Retry) return decision.delayMs
            val message = describe(info.exception)
            main.post { fail(message) }
            return C.TIME_UNSET
        }
    }

    override val exoPlayer: ExoPlayer = run {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(10_000)
        val data = DefaultDataSource.Factory(context, http)
        val sources = SidecarTolerantSourceFactory(DefaultMediaSourceFactory(data), data)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        val renderers = object : DefaultRenderersFactory(context) {
            // Legacy decoding is deprecated with SingleSampleMediaSource; it is what decodes the sidecars.
            @Suppress("DEPRECATION")
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                outputLooper: Looper,
                extensionRendererMode: Int,
                out: ArrayList<Renderer>,
            ) {
                out += TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) }
            }
        }
        ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = listeners.forEach { it.onIsPlayingChanged(isPlaying) }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, reason) }
            override fun onPlaybackStateChanged(playbackState: Int) =
                listeners.forEach { it.onPlaybackStateChanged(playbackState) }

            override fun onPlayerError(error: PlaybackException) = fail(describe(error))

            override fun onTracksChanged(tracks: Tracks) = listeners.forEach { it.onTracksChanged() }
        })
    }

    override val currentPositionMs: Long get() = exoPlayer.currentPosition
    override val durationMs: Long? get() = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
    override val isPlaying: Boolean get() = exoPlayer.isPlaying
    override val playWhenReady: Boolean get() = exoPlayer.playWhenReady
    override val playbackState: Int get() = exoPlayer.playbackState
    override val textTracks: List<PlayerTrack> get() = tracksOf(C.TRACK_TYPE_TEXT)
    override val audioTracks: List<PlayerTrack> get() = tracksOf(C.TRACK_TYPE_AUDIO)
    override val tracksKnown: Boolean get() = !exoPlayer.currentTracks.isEmpty

    override fun prepare(mediaItem: MediaItem, startPositionMs: Long?) {
        // A failure posted for the previous item must not end this one.
        main.removeCallbacksAndMessages(null)
        budget.reset()
        failed = false
        if (startPositionMs != null) exoPlayer.setMediaItem(mediaItem, startPositionMs) else exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
    }

    override fun setTextTrack(groupIndex: Int?) {
        val group = groupIndex?.let { exoPlayer.currentTracks.groups.getOrNull(it) }
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, group == null)
            .apply { if (group != null) addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0)) }
            .build()
    }

    override fun setAudioTrack(groupIndex: Int) {
        val group = exoPlayer.currentTracks.groups.getOrNull(groupIndex) ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }

    private fun tracksOf(type: Int): List<PlayerTrack> =
        exoPlayer.currentTracks.groups.mapIndexedNotNull { i, g ->
            if (g.type != type) return@mapIndexedNotNull null
            val f = g.getTrackFormat(0)
            PlayerTrack(
                groupIndex = i,
                id = f.id,
                language = f.language,
                label = f.label,
                isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                selected = g.isSelected,
            )
        }

    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun seekTo(ms: Long) = exoPlayer.seekTo(ms)
    override fun addListener(listener: PlayerListener) { listeners += listener }
    override fun removeListener(listener: PlayerListener) { listeners -= listener }

    override fun release() {
        main.removeCallbacksAndMessages(null)
        listeners.clear()
        exoPlayer.release()
    }

    /** Main thread. Reports one failure per prepared item and stops the player. */
    private fun fail(message: String) {
        if (failed) return
        failed = true
        exoPlayer.stop()
        listeners.forEach { it.onPlayerError(message) }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> texts.connectionLost
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> error.cause?.let(::describe) ?: texts.serverError(0)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> texts.notFound
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> texts.unsupportedFormat
        else -> texts.other(error.message.orEmpty(), error.errorCodeName)
    }

    private fun describe(error: Throwable): String = when (error) {
        is HttpDataSource.InvalidResponseCodeException ->
            if (error.responseCode == 404) texts.notFound else texts.serverError(error.responseCode)
        is HttpDataSource.HttpDataSourceException -> texts.connectionLost
        is FileNotFoundException -> texts.notFound
        is ParserException -> texts.unsupportedFormat
        is IOException -> texts.other(error.message ?: error.javaClass.simpleName, error.javaClass.simpleName)
        else -> texts.other(error.message.orEmpty(), error.javaClass.simpleName)
    }

    /**
     * Builds the stream through [main] and each sidecar as its own `SingleSampleMediaSource` whose load errors
     * end that sidecar instead of the playback (`DefaultMediaSourceFactory` offers no such setting for sidecars).
     */
    // SingleSampleMediaSource is deprecated in favour of parsing subtitles during extraction, but only it can treat
    // a sidecar's load errors as end of stream (Media3 1.5.1).
    @Suppress("DEPRECATION")
    private class SidecarTolerantSourceFactory(
        private val main: DefaultMediaSourceFactory,
        private val data: DataSource.Factory,
    ) : MediaSource.Factory {
        private var policy: LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy()

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
            apply { main.setDrmSessionManagerProvider(provider) }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
            this.policy = policy
            main.setLoadErrorHandlingPolicy(policy)
        }

        override fun getSupportedTypes(): IntArray = main.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val sidecars = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
            if (sidecars.isEmpty()) return main.createMediaSource(mediaItem)
            val video = main.createMediaSource(mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build())
            val subtitleSources = sidecars.map {
                androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(data)
                    .setLoadErrorHandlingPolicy(policy)
                    .setTreatLoadErrorsAsEndOfStream(true)
                    .createMediaSource(it, C.TIME_UNSET)
            }
            return MergingMediaSource(video, *subtitleSources.toTypedArray())
        }
    }

    private companion object {
        /** Sidecar URLs are `/api/items/<id>/subtitles/sidecar/<name>` (see `tv-client-notes.md`). */
        fun isSidecar(uri: String) = uri.contains("/subtitles/sidecar/")

        /** How long one load may keep failing before playback stops with an error. */
        const val GIVE_UP_AFTER_MS = 8_000L
    }
}
