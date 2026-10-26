package app.pixelpantry.tv.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * The slice of a media player the [PlayerViewModel] drives. Keeps the view model free of ExoPlayer so it can be
 * unit-tested with a fake; [ExoPlayerPort] is the real one.
 */
interface PlayerPort {
    /** The player the `PlayerView` renders; null in tests. */
    val exoPlayer: ExoPlayer?
    val currentPositionMs: Long

    /** Null until the duration is known. */
    val durationMs: Long?
    val isPlaying: Boolean

    /** Whether the player wants to play: false once paused, by the user or by the player itself. */
    val playWhenReady: Boolean

    /** One of `Player.STATE_*`. */
    val playbackState: Int

    fun prepare(mediaItem: MediaItem)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun addListener(listener: PlayerListener)
    fun removeListener(listener: PlayerListener)
    fun release()
}

/** Player events, delivered on the main thread. */
interface PlayerListener {
    fun onIsPlayingChanged(isPlaying: Boolean) {}

    /** [reason] is a `Player.PLAY_WHEN_READY_CHANGE_REASON_*`: the user, audio focus loss, headphones out, … */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {}
    fun onPlaybackStateChanged(state: Int) {}

    /** Playback stopped for good; [message] is plain words for the error screen. */
    fun onPlayerError(message: String) {}
}
