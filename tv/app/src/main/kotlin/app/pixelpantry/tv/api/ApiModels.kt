package app.pixelpantry.tv.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
data class Progress(val position: Double, val duration: Double, val watched: Boolean)

@Serializable
data class ItemSummary(
    val id: Int,
    val type: String, // "episode" | "movie"
    val category: String,
    val title: String,
    val showId: Int? = null,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val still: String? = null,
    val airDate: String? = null,
    val progress: Progress? = null,
    val addedAt: Long = 0,
)

@Serializable
data class ShowSummary(
    val id: Int,
    val category: String,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val episodeCount: Int = 0,
    val unwatchedCount: Int = 0,
    val matched: Boolean = true,
    val addedAt: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class Card {
    @Serializable
    @SerialName("show")
    data class ShowCard(val show: ShowSummary, val nextUp: ItemSummary? = null) : Card()

    @Serializable
    @SerialName("item")
    data class ItemCard(val item: ItemSummary, val reason: String? = null) : Card() // "resume" | "next"
}

@Serializable
data class Row(val key: String, val title: String, val cards: List<Card>)

@Serializable
data class CategoryPage(val category: String, val rows: List<Row>)

@Serializable
data class SeasonDetail(
    val number: Int,
    val name: String,
    val poster: String? = null,
    val episodes: List<ItemSummary>,
)

@Serializable
data class ShowDetail(
    val show: ShowSummary,
    val seasons: List<SeasonDetail>,
    val resume: ItemSummary? = null,
    val fileCount: Int = 0,
)

@Serializable
data class Track(
    val index: Int,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    @SerialName("default") val isDefault: Boolean = false,
    val forced: Boolean = false,
)

@Serializable
data class SubtitleTrack(
    val id: String,
    val label: String,
    val lang: String? = null,
    val url: String? = null,
    val format: String, // "srt" | "vtt" | "ass" | "embedded"
)

@Serializable
data class ItemDetail(
    val item: ItemSummary,
    val streamUrl: String,
    val fileName: String,
    val fileSize: Long = 0,
    val audio: List<Track> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val next: ItemSummary? = null,
    val prev: ItemSummary? = null,
    val subtitleChoice: String? = null,
    val audioChoice: String? = null,
)

@Serializable
data class SearchResponse(val shows: List<ShowSummary>, val movies: List<ItemSummary>)

@Serializable
data class HistoryEntry(
    val sessionId: Int,
    val itemId: Int,
    val startedAt: Long,
    val endedAt: Long,
    val secondsWatched: Double,
    val item: ItemSummary? = null,
)

@Serializable
data class HistoryResponse(val entries: List<HistoryEntry>)

@Serializable
data class TopShow(val showId: Int, val title: String, val hours: Double)

@Serializable
data class Stats(
    val hoursWeek: Double,
    val hoursMonth: Double,
    val hoursAll: Double,
    val episodesAll: Int,
    val moviesAll: Int,
    val topShowMonth: TopShow? = null,
)

@Serializable
data class ProgressResponse(val sessionId: Int, val watched: Boolean)

@Serializable
data class DeleteResponse(val trashed: List<String>)

@Serializable
data class SubtitleListResponse(val tracks: List<SubtitleTrack>)

@Serializable
data class OsResult(
    val fileId: Long,
    val release: String,
    val hashMatch: Boolean,
    val downloads: Int,
    val language: String,
    val fps: Double? = null,
)

@Serializable
data class SubtitleSearchResponse(val results: List<OsResult>)

@Serializable
data class InfoResponse(val name: String, val version: String)

@Serializable
data class Settings(
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int,
    val autoplayNext: Boolean,
)

@Serializable
data class SettingsPatch(
    val skipBackSeconds: Int? = null,
    val skipForwardSeconds: Int? = null,
    val autoplayNext: Boolean? = null,
)

@Serializable
data class ScanStatus(
    val state: String, // "idle" | "scanning"
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val filesSeen: Int = 0,
    val filesProcessed: Int = 0,
    val phase: String = "done",
    val lastError: String? = null,
)
