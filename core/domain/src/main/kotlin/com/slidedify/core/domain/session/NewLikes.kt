package com.slidedify.core.domain.session

import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile

/**
 * "Nuevos Me gusta": liked songs added after the user last caught up, i.e. newer than the most
 * recent liked song they already sorted, and newer than [baseline] (when the feature started, so a
 * first-time user doesn't get their whole library). Services list liked songs newest first, so
 * reading stops at the first song that is not new; usually one page.
 */
object NewLikes {
    /** Songs liked within this window count as new the first time (before anything was sorted). */
    const val FIRST_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    suspend fun load(tracks: TrackRepository, sorted: Set<TrackId>, baseline: Long?, maxPages: Int = 20): List<Track> {
        val found = mutableListOf<Track>()
        var pages = 0
        // Decide after each page whether to ask for the next one, so no extra page is fetched.
        tracks.pages(TrackSource.LikedSongs)
            .transformWhile<List<Track>, Unit> { page ->
                pages++
                val (fresh, stop) = scan(page, sorted, baseline)
                found += fresh
                !stop && pages < maxPages
            }
            .collect()
        return found
    }

    /** New songs at the start of [page], and whether a song that is not new was reached. */
    internal fun scan(page: List<Track>, sorted: Set<TrackId>, baseline: Long?): Pair<List<Track>, Boolean> {
        val fresh = mutableListOf<Track>()
        for (track in page) {
            // The newest song already sorted marks where the user caught up.
            if (track.id in sorted) return fresh to true
            val addedAt = track.addedAt ?: return fresh to true // No dates: the feature can't work.
            if (baseline != null && addedAt <= baseline) return fresh to true
            fresh += track
        }
        return fresh to false
    }
}
