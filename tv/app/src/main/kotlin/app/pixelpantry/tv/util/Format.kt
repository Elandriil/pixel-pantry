package app.pixelpantry.tv.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Runtime label: `"52 min"`, `"1 h 35 min"`. */
fun formatRuntime(seconds: Int): String {
    val totalMinutes = seconds.coerceAtLeast(0) / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}

/** Time left in an item: `"23 min left"`, or `"< 1 min left"` under a minute. */
fun formatRemaining(positionSec: Double, durationSec: Double): String {
    val remaining = durationSec - positionSec
    if (remaining < 60.0) return "< 1 min left"
    return "${(remaining / 60.0).toInt()} min left"
}

/** Wall-clock end time on a 24 h clock: `"Ends at 22:41"`. */
fun formatEndsAt(remainingSec: Double, now: LocalTime): String {
    val end = now.plusSeconds(remainingSec.coerceAtLeast(0.0).toLong())
    return "Ends at ${end.format(clockFormatter)}"
}

/** Episode tag: `"S2 · E4"`, `"S3 · E1–2"`; empty for movies. */
fun formatEpisodeTag(season: Int?, episode: Int?, episodeEnd: Int?): String {
    if (season == null) return ""
    if (episode == null) return "S$season"
    val range = if (episodeEnd != null && episodeEnd != episode) "$episode–$episodeEnd" else "$episode"
    return "S$season · E$range"
}

/** Playback clock: `"32:04"`, `"1:02:04"`. */
fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
