package com.slidedify.lyrics.lrclib

import com.slidedify.core.domain.lyrics.LyricLine
import com.slidedify.core.domain.lyrics.Lyrics

/** Parses the LRC format (`[mm:ss.xx] text`), the de-facto standard for synced lyrics. */
object LrcParser {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseSynced(lrc: String): Lyrics {
        val lines = lrc.lineSequence().flatMap { raw ->
            val stamps = timestamp.findAll(raw).toList()
            // A line may carry several timestamps when the same verse repeats.
            if (stamps.isEmpty() || stamps.first().range.first != 0) return@flatMap emptySequence()
            val text = raw.substring(stamps.last().range.last + 1).trim()
            stamps.asSequence().map { LyricLine(it.toMillis(), text) }
        }
        return Lyrics(lines.sortedBy { it.timeMs }.toList())
    }

    fun parsePlain(text: String): Lyrics = Lyrics(text.lines().map { LyricLine(timeMs = null, text = it.trim()) })

    private fun MatchResult.toMillis(): Long {
        val (minutes, seconds, fraction) = destructured
        val fractionMs = when (fraction.length) {
            0 -> 0
            1 -> fraction.toInt() * 100
            2 -> fraction.toInt() * 10
            else -> fraction.take(3).toInt()
        }
        return minutes.toLong() * 60_000 + seconds.toLong() * 1_000 + fractionMs
    }
}
