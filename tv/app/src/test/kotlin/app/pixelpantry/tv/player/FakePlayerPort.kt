package app.pixelpantry.tv.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Records every call; tests move [position]/[duration] and fire listener events with [fire]. */
class FakePlayerPort : PlayerPort {
    val calls = mutableListOf<String>()
    val prepared = mutableListOf<MediaItem>()

    /** The start position of each [prepare] (null: the item's default, then a seek). */
    val preparedAt = mutableListOf<Long?>()
    val seeks = mutableListOf<Long>()

    /** Every [setTextTrack] (null = text off) and [setAudioTrack], in order. */
    val textSelections = mutableListOf<Int?>()
    val audioSelections = mutableListOf<Int>()
    private val listeners = mutableListOf<PlayerListener>()
    var position = 0L
    var duration: Long? = null
    var playing = false
    var whenReady = false
    var state = Player.STATE_IDLE
    var released = false

    /** Set by tests before firing `onTracksChanged`; a new [prepare] forgets them, like ExoPlayer. */
    override var textTracks: List<PlayerTrack> = emptyList()
    override var audioTracks: List<PlayerTrack> = emptyList()

    override val exoPlayer: ExoPlayer? = null
    override val currentPositionMs: Long get() = position
    override val durationMs: Long? get() = duration
    override val isPlaying: Boolean get() = playing
    override val playWhenReady: Boolean get() = whenReady
    override val playbackState: Int get() = state
    override val tracksKnown: Boolean get() = textTracks.isNotEmpty() || audioTracks.isNotEmpty()

    override fun prepare(mediaItem: MediaItem, startPositionMs: Long?) {
        calls += "prepare"
        prepared += mediaItem
        preparedAt += startPositionMs
        textTracks = emptyList()
        audioTracks = emptyList()
        if (startPositionMs != null) position = startPositionMs
    }

    override fun play() {
        calls += "play"
        playing = true
        changeWhenReady(true)
    }

    override fun pause() {
        calls += "pause"
        playing = false
        changeWhenReady(false)
    }

    /** Like ExoPlayer: a change of playWhenReady by play()/pause() is reported as a user request. */
    private fun changeWhenReady(value: Boolean) {
        if (whenReady == value) return
        whenReady = value
        fire { it.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
    }

    override fun stop() {
        calls += "stop"
        playing = false
        state = Player.STATE_IDLE
    }

    override fun seekTo(ms: Long) {
        calls += "seekTo($ms)"
        seeks += ms
        position = ms
    }

    override fun setTextTrack(groupIndex: Int?) {
        textSelections += groupIndex
    }

    override fun setAudioTrack(groupIndex: Int) {
        audioSelections += groupIndex
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
