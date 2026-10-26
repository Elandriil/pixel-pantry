package app.pixelpantry.tv.player

import app.pixelpantry.tv.api.ItemDetail
import app.pixelpantry.tv.api.SubtitleTrack

/**
 * Which subtitle and audio tracks an item plays with, and the sidecar files its MediaItem carries. Pure: the
 * [PlayerViewModel] turns a [Plan] into `MediaItem.SubtitleConfiguration`s and track selections.
 *
 * Ids follow the server (`server/docs/tv-client-notes.md`): `embedded:<streamIndex>`, `sidecar:<URL-encoded
 * basename>`, [OFF]; audio `audio:<streamIndex>`.
 */
object SubtitlePlan {
    const val OFF = "off"
    private const val EMBEDDED = "embedded:"
    private const val SIDECAR = "sidecar:"
    private const val AUDIO = "audio:"

    /** A sidecar file for the MediaItem; [selected] ones get `SELECTION_FLAG_DEFAULT`. */
    data class SubtitleConfig(
        val uri: String,
        /** `application/x-subrip`, `text/vtt` or `text/x-ssa`. */
        val mimeType: String,
        val label: String,
        val language: String?,
        val id: String,
        val selected: Boolean,
    )

    data class Plan(
        /** Every sidecar of the item, in the server's order. */
        val subtitleConfigs: List<SubtitleConfig>,
        /** The embedded stream to show; applied by track selection once the player knows the tracks. */
        val selectedEmbeddedIndex: Int?,
        /** The chosen sidecar's URI, with `?shift=` when delayed. */
        val selectedSidecarUri: String?,
        /** `audio:<index>`, null for an item without audio tracks. */
        val audioChoice: String?,
    ) {
        /** The chosen subtitle id, or [OFF]. */
        val subtitleChoice: String
            get() = selectedEmbeddedIndex?.let { "$EMBEDDED$it" }
                ?: subtitleConfigs.firstOrNull { it.selected }?.id
                ?: OFF
    }

    /**
     * [choice]: a subtitle id or [OFF]; one that matches no track of [detail] (or null) falls back to the default
     * embedded track, else off. [base] resolves a server path; [shiftMs] delays the chosen sidecar.
     */
    fun build(detail: ItemDetail, base: (String) -> String, choice: String?, shiftMs: Int): Plan {
        val chosen: SubtitleTrack? = when (choice) {
            OFF -> null
            else -> detail.subtitles.firstOrNull { it.id == choice && (it.isEmbedded || it.url != null) }
                ?: detail.subtitles.firstOrNull { it.isEmbedded && it.isDefault }
        }
        val configs = detail.subtitles.filter { !it.isEmbedded && it.url != null }.map { t ->
            val selected = t.id == chosen?.id
            val shift = if (selected && shiftMs != 0) "?shift=$shiftMs" else ""
            SubtitleConfig(
                uri = base(t.url!!) + shift,
                mimeType = mimeType(t.format),
                label = t.label,
                language = t.lang,
                id = t.id,
                selected = selected,
            )
        }
        return Plan(
            subtitleConfigs = configs,
            selectedEmbeddedIndex = chosen?.takeIf { it.isEmbedded }?.let(::streamIndex),
            selectedSidecarUri = configs.firstOrNull { it.selected }?.uri,
            audioChoice = audioChoice(detail),
        )
    }

    /** The stored audio choice when it names a track, else the default track, else the first. */
    private fun audioChoice(detail: ItemDetail): String? {
        val stored = detail.audioChoice?.takeIf { c -> detail.audio.any { "$AUDIO${it.index}" == c } }
        return stored ?: (detail.audio.firstOrNull { it.isDefault } ?: detail.audio.firstOrNull())?.let { "$AUDIO${it.index}" }
    }

    fun mimeType(format: String): String = when (format) {
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> "application/x-subrip"
    }

    fun isSidecar(id: String): Boolean = id.startsWith(SIDECAR)

    /**
     * The player's text group for subtitle [id], or null when it has none. A sidecar's group carries its id. An
     * embedded track is found by order: the player's embedded text groups (those that are not sidecars) come in
     * stream order, like the item's embedded tracks. The container's own track ids are not the server's stream
     * indices (Matroska numbers tracks from 1 across all types: stream 1 shows as `0:2`), so they are not
     * compared.
     */
    fun textGroup(id: String, detail: ItemDetail, tracks: List<PlayerTrack>): Int? {
        if (isSidecar(id)) return tracks.firstOrNull { it.id?.let(::unprefixed) == id }?.groupIndex
        val ordinal = detail.subtitles.filter { it.isEmbedded }.indexOfFirst { it.id == id }
        if (ordinal < 0) return null
        return embeddedGroups(tracks).getOrNull(ordinal)?.groupIndex
    }

    /** The id of the embedded track the player's flags name the default (the server does not send the flag). */
    fun defaultEmbeddedId(detail: ItemDetail, tracks: List<PlayerTrack>): String? {
        val ordinal = embeddedGroups(tracks).indexOfFirst { it.isDefault }
        if (ordinal < 0) return null
        return detail.subtitles.filter { it.isEmbedded }.getOrNull(ordinal)?.id
    }

    /** The player's audio group for `audio:<index>`, by order like [textGroup]. */
    fun audioGroup(id: String, detail: ItemDetail, tracks: List<PlayerTrack>): Int? {
        val ordinal = detail.audio.indexOfFirst { "$AUDIO${it.index}" == id }
        if (ordinal < 0) return null
        return tracks.getOrNull(ordinal)?.groupIndex
    }

    private fun embeddedGroups(tracks: List<PlayerTrack>) =
        tracks.filter { it.id?.let { id -> isSidecar(unprefixed(id)) } != true }

    /**
     * A player format id without the period prefix ExoPlayer adds when it merges the video with sidecar files
     * (`0:2` for the file's track 2, `1:sidecar:x.srt` for the first sidecar).
     */
    private fun unprefixed(formatId: String): String = formatId.replace(PERIOD_PREFIX, "")

    private val PERIOD_PREFIX = Regex("^\\d+:")

    private val SubtitleTrack.isEmbedded get() = format == "embedded"

    private fun streamIndex(t: SubtitleTrack): Int? = t.id.removePrefix(EMBEDDED).toIntOrNull()
}
