package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Playlist
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId

/** A song that may not belong in the playlist being reviewed. */
data class Misfit(
    val track: Track,
    /** How well it fits the rest of its own playlist, 0..1. */
    val fit: Float,
    /** Another playlist where it fits clearly better, if any. */
    val better: Playlist?,
    val betterScore: Float?,
    /** Why it fits [better] (when there is one). */
    val reasons: List<Reason>,
)

/**
 * "Revisar playlist": compares every song with the rest of its own playlist (the profile without
 * that song) and with the user's other playlists, and lists the ones that stand out, worst first.
 */
object PlaylistReview {
    /** Below this, a song doesn't fit its own playlist. */
    const val LOW_FIT = 0.35f

    /** Another playlist must beat the own one by this much (and be a good fit itself). */
    const val BETTER_MARGIN = 0.15f

    fun review(
        playlist: Playlist,
        tracks: List<Track>,
        features: Map<TrackId, TrackFeatures>,
        others: List<PlaylistProfile>,
    ): List<Misfit> {
        val analysed = tracks.mapNotNull { track -> features[track.id]?.let { track to it } }
        val own = PlaylistProfile.build(playlist, tracks, analysed.map { it.second })
        return tracks.mapNotNull { track ->
            val f = features[track.id]
            val ranking = SuggestionEngine.rank(track, f, listOf(own.without(track, f)) + others) ?: return@mapNotNull null
            val ownFit = ranking.first { it.playlist.id == playlist.id }.score
            val best = ranking.firstOrNull { it.playlist.id != playlist.id }
            val betterHere = best != null && best.score >= SuggestionEngine.MIN_SCORE && best.score - ownFit >= BETTER_MARGIN
            when {
                betterHere -> Misfit(track, ownFit, best!!.playlist, best.score, best.reasons)
                ownFit < LOW_FIT -> Misfit(track, ownFit, null, null, emptyList())
                else -> null
            }
        }.sortedBy { it.fit - (it.betterScore ?: 0f) }
    }
}
