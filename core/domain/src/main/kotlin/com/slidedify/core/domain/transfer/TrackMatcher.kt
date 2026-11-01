package com.slidedify.core.domain.transfer

import com.slidedify.core.domain.repository.TrackQuery
import com.slidedify.core.model.Track
import java.text.Normalizer
import kotlin.math.abs

/**
 * Decides whether two tracks from different services are the same recording.
 * ISRC wins when both sides have it; otherwise title, artist and duration are compared after
 * cleaning the usual noise ("- 2011 Remaster", "(feat. X)", "Official Video", "Artist - Topic").
 */
object TrackMatcher {
    const val DEFAULT_THRESHOLD = 0.72

    private val noise = listOf(
        Regex("""\s+-\s+.*(remaster|remastered|version|mix|edit|live|mono|stereo|single|radio).*$""", RegexOption.IGNORE_CASE),
        Regex("""[(\[][^)\]]*(feat|ft\.|with|remaster|official|video|audio|lyrics?|visualizer|hd|4k|version)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    )

    fun normalizeTitle(title: String): String {
        var cleaned = title
        noise.forEach { cleaned = cleaned.replace(it, " ") }
        return simplify(cleaned)
    }

    fun normalizeArtist(artist: String): String = simplify(
        artist.replace(Regex("""\s+-\s+topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""vevo$""", RegexOption.IGNORE_CASE), ""),
    )

    fun queryFor(track: Track) = TrackQuery(
        title = normalizeTitle(track.title).ifBlank { track.title },
        artist = track.artists.firstOrNull()?.name.orEmpty(),
        durationMs = track.durationMs,
        isrc = track.isrc,
    )

    /** 0.0 (different) … 1.0 (same recording). */
    fun score(source: Track, candidate: Track): Double {
        val a = source.isrc
        val b = candidate.isrc
        if (a != null && b != null) return if (a.equals(b, ignoreCase = true)) 1.0 else 0.0

        val titleScore = similarity(normalizeTitle(source.title), normalizeTitle(candidate.title))
        val artistScore = source.artists.maxOfOrNull { s ->
            candidate.artists.maxOfOrNull { c -> similarity(normalizeArtist(s.name), normalizeArtist(c.name)) } ?: 0.0
        } ?: 0.0
        val durationScore = when {
            source.durationMs <= 0 || candidate.durationMs <= 0 -> 0.5 // unknown on one side
            abs(source.durationMs - candidate.durationMs) <= 3_000 -> 1.0
            abs(source.durationMs - candidate.durationMs) <= 8_000 -> 0.5
            else -> 0.0
        }
        return titleScore * 0.6 + artistScore * 0.3 + durationScore * 0.1
    }

    fun best(source: Track, candidates: List<Track>, threshold: Double = DEFAULT_THRESHOLD): Track? =
        candidates.map { it to score(source, it) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first

    /** Token overlap (Jaccard) with a bonus when one contains the other. */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ta = a.split(' ').toSet()
        val tb = b.split(' ').toSet()
        val jaccard = (ta intersect tb).size.toDouble() / (ta union tb).size
        val contains = if (a.contains(b) || b.contains(a)) 0.85 else 0.0
        return maxOf(jaccard, contains)
    }

    private fun simplify(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
}
