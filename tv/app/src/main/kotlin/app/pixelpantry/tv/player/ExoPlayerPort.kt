package app.pixelpantry.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.ParserException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
 * load once its buffer has drained, which can be most of a minute. So the load error policy gives up after
 * [MAX_LOAD_ERRORS] consecutive failures of the same load (the count resets whenever data arrives) and reports
 * the failure itself.
 */
@OptIn(UnstableApi::class)
class ExoPlayerPort(context: Context, private val texts: PlaybackErrorTexts) : PlayerPort {
    private val listeners = CopyOnWriteArrayList<PlayerListener>()
    private val main = Handler(Looper.getMainLooper())
    private var failed = false

    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val delay = super.getRetryDelayMsFor(info)
            if (delay != C.TIME_UNSET && info.errorCount < MAX_LOAD_ERRORS) return delay
            val message = describe(info.exception)
            main.post { fail(message) }
            return C.TIME_UNSET
        }
    }

    override val exoPlayer: ExoPlayer = run {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(10_000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
        ExoPlayer.Builder(context)
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
            override fun onPlaybackStateChanged(playbackState: Int) =
                listeners.forEach { it.onPlaybackStateChanged(playbackState) }

            override fun onPlayerError(error: PlaybackException) = fail(describe(error))
        })
    }

    override val currentPositionMs: Long get() = exoPlayer.currentPosition
    override val durationMs: Long? get() = exoPlayer.duration.takeIf { it != C.TIME_UNSET }
    override val isPlaying: Boolean get() = exoPlayer.isPlaying
    override val playbackState: Int get() = exoPlayer.playbackState

    override fun prepare(mediaItem: MediaItem) {
        failed = false
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
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

    private companion object {
        /** Connection refused fails fast (about 3 s of retries); an unreachable PC costs a connect timeout each. */
        const val MAX_LOAD_ERRORS = 3
    }
}
