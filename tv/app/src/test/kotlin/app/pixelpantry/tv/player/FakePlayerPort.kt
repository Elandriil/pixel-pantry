package app.pixelpantry.tv.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Records every call; tests move [position]/[duration] and fire listener events with [fire]. */
class FakePlayerPort : PlayerPort {
    val calls = mutableListOf<String>()
    val prepared = mutableListOf<MediaItem>()
    val seeks = mutableListOf<Long>()
    private val listeners = mutableListOf<PlayerListener>()
    var position = 0L
    var duration: Long? = null
    var playing = false
    var state = Player.STATE_IDLE
    var released = false

    override val exoPlayer: ExoPlayer? = null
    override val currentPositionMs: Long get() = position
    override val durationMs: Long? get() = duration
    override val isPlaying: Boolean get() = playing
    override val playbackState: Int get() = state

    override fun prepare(mediaItem: MediaItem) {
        calls += "prepare"
        prepared += mediaItem
    }

    override fun play() {
        calls += "play"
        playing = true
    }

    override fun pause() {
        calls += "pause"
        playing = false
    }

    override fun seekTo(ms: Long) {
        calls += "seekTo($ms)"
        seeks += ms
        position = ms
    }

    override fun addListener(listener: PlayerListener) {
        listeners += listener
    }

    override fun removeListener(listener: PlayerListener) {
        listeners -= listener
    }

    override fun release() {
        calls += "release"
        released = true
    }

    fun fire(event: (PlayerListener) -> Unit) = listeners.toList().forEach(event)
}
