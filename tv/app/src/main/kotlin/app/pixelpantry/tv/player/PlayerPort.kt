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

    /** The prepared item's text and audio track groups, in the player's order; empty until [tracksKnown]. */
    val textTracks: List<PlayerTrack>
    val audioTracks: List<PlayerTrack>

    /** The prepared item's tracks are known (they are forgotten by [prepare] until it has read them again). */
    val tracksKnown: Boolean

    /**
     * Loads [mediaItem] and starts buffering at [startPositionMs] (null: at its default position). The play /
     * pause intent carries over, so re-preparing the item playing now (a new subtitle file) keeps it playing.
     */
    fun prepare(mediaItem: MediaItem, startPositionMs: Long? = null)

    /** Shows the text track group [groupIndex] (an index into the player's track groups); null turns text off. */
    fun setTextTrack(groupIndex: Int?)
    fun setAudioTrack(groupIndex: Int)
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

    /** [PlayerPort.textTracks] / [PlayerPort.audioTracks] or their selection changed. */
    fun onTracksChanged() {}

    /** Playback stopped for good; [message] is plain words for the error screen. */
    fun onPlayerError(message: String) {}
}

/**
 * One track group as the player sees it. [id] is the format id: the container's track id for an embedded track,
 * the subtitle configuration id (`sidecar:…`) for a sidecar. [isDefault]: the file flags it as default.
 */
data class PlayerTrack(
    val groupIndex: Int,
    val id: String?,
    val language: String?,
    val label: String?,
    val isDefault: Boolean,
    val selected: Boolean,
)
