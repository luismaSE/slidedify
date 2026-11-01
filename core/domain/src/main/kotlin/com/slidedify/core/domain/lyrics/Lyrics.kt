package com.slidedify.core.domain.lyrics

import com.slidedify.core.model.Track

data class LyricLine(
    /** Start time for synced lyrics; null for plain text. */
    val timeMs: Long?,
    val text: String,
)

data class Lyrics(val lines: List<LyricLine>) {
    val isSynced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs != null }

    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun lineIndexAt(positionMs: Long): Int = lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= positionMs }
}

/**
 * Lyrics are looked up by title/artist/duration, not by provider, so a single source
 * (e.g. LRCLIB) works for every music service.
 */
interface LyricsRepository {
    suspend fun lyricsFor(track: Track): Lyrics?
}
