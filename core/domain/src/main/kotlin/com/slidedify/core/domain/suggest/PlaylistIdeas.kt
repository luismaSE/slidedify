package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Playlist
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId

enum class EnergyBand { Calm, Medium, Energetic }

/** A group of songs that sound alike, proposed as a new playlist. */
data class PlaylistIdea(
    /** Dominant style tag ("indie folk"), or null when only the energy groups them. */
    val tag: String?,
    val energy: EnergyBand,
    val tracks: List<Track>,
)

/**
 * "Ideas de playlists": groups songs by their most common style tag and their energy, and proposes
 * the big, distinct groups as new playlists, skipping styles the user already has a playlist for.
 */
object PlaylistIdeas {
    const val MIN_SIZE = 8
    const val MAX_IDEAS = 6

    /** Tags considered as the style of a song: the most frequent ones in the library. */
    private const val STYLE_TAGS = 15

    fun propose(
        tracks: List<Track>,
        features: Map<TrackId, TrackFeatures>,
        existing: List<Playlist>,
        minSize: Int = MIN_SIZE,
        maxIdeas: Int = MAX_IDEAS,
    ): List<PlaylistIdea> {
        val analysed = tracks.mapNotNull { track -> features[track.id]?.takeIf { !it.isEmpty }?.let { track to it } }
        // The styles that matter in this library, weighted by how strongly songs carry them.
        val styles = analysed
            .flatMap { (_, f) -> f.tags.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, weights) -> weights.sum() }
            .entries.sortedByDescending { it.value }
            .take(STYLE_TAGS)
            .map { it.key }
            .toSet()
        val taken = existing.flatMap { playlist ->
            val name = normalizeText(playlist.name)
            styles.filter { name.contains(it) } + NamePriors.of(playlist.name)?.tags.orEmpty()
        }.toSet()

        val groups = analysed.groupBy { (_, f) ->
            val tag = f.tags.entries.filter { it.key in styles }.maxByOrNull { it.value }?.key
            tag to band(f.energy)
        }
        return groups
            .filter { (key, members) ->
                val (tag, energy) = key
                members.size >= minSize &&
                    (tag != null || energy != EnergyBand.Medium) && // "medium energy, no style" says nothing
                    (tag == null || tag !in taken)
            }
            .map { (key, members) -> PlaylistIdea(key.first, key.second, members.map { it.first }) }
            .sortedByDescending { it.tracks.size }
            .take(maxIdeas)
    }

    fun band(energy: Float?): EnergyBand = when {
        energy == null -> EnergyBand.Medium
        energy < 0.38f -> EnergyBand.Calm
        energy > 0.62f -> EnergyBand.Energetic
        else -> EnergyBand.Medium
    }
}
