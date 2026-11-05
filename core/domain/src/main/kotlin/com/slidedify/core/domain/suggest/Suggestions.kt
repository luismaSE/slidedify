package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Playlist
import com.slidedify.core.model.Track
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Running mean and spread of a number, so profiles can learn one track at a time. */
data class Stat(val sum: Double = 0.0, val sumSquares: Double = 0.0, val count: Int = 0) {
    val mean: Float get() = if (count == 0) 0f else (sum / count).toFloat()

    /** Standard deviation, never below [MIN_SPREAD] so a few similar songs don't make it too strict. */
    val spread: Float
        get() {
            if (count < 2) return DEFAULT_SPREAD
            val variance = (sumSquares / count) - (sum / count) * (sum / count)
            return maxOf(MIN_SPREAD, sqrt(maxOf(0.0, variance)).toFloat())
        }

    operator fun plus(value: Float) = Stat(sum + value, sumSquares + value.toDouble() * value, count + 1)

    companion object {
        const val MIN_SPREAD = 0.12f
        const val DEFAULT_SPREAD = 0.2f

        fun of(values: List<Float>) = values.fold(Stat()) { acc, v -> acc + v }
    }
}

/** The style of a playlist, learnt from its songs and its name. */
data class PlaylistProfile(
    val playlist: Playlist,
    /** Songs seen (all of them, not only the analysed sample). */
    val size: Int = 0,
    /** Artist id to number of songs. */
    val artists: Map<String, Int> = emptyMap(),
    val artistNames: Map<String, String> = emptyMap(),
    /** Sum of tag weights over the analysed songs; divide by [tagSamples] for the average. */
    val tagSums: Map<String, Float> = emptyMap(),
    val tagSamples: Int = 0,
    val energy: Stat = Stat(),
    val bpm: Stat = Stat(),
    val name: NamePrior? = NamePriors.of(playlist.name),
) {
    val tags: Map<String, Float>
        get() = if (tagSamples == 0) emptyMap() else tagSums.mapValues { it.value / tagSamples }

    /** Counts the artists of [tracks] (cheap, all songs). */
    fun withArtists(tracks: List<Track>): PlaylistProfile {
        val counts = artists.toMutableMap()
        val names = artistNames.toMutableMap()
        tracks.forEach { track ->
            track.artists.forEach { artist ->
                counts[artist.id] = (counts[artist.id] ?: 0) + 1
                names[artist.id] = artist.name
            }
        }
        return copy(size = size + tracks.size, artists = counts, artistNames = names)
    }

    /** Folds the sound of one analysed song into the profile. */
    fun withFeatures(features: TrackFeatures): PlaylistProfile {
        if (features.isEmpty) return this
        val sums = tagSums.toMutableMap()
        features.tags.forEach { (tag, weight) -> sums[tag] = (sums[tag] ?: 0f) + weight }
        return copy(
            tagSums = sums,
            tagSamples = tagSamples + if (features.tags.isEmpty()) 0 else 1,
            energy = features.energy?.let { energy + it } ?: energy,
            bpm = features.bpm?.let { bpm + it } ?: bpm,
        )
    }

    /** Learns from a song the user just sorted into this playlist. */
    fun learn(track: Track, features: TrackFeatures?): PlaylistProfile =
        withArtists(listOf(track)).let { if (features != null) it.withFeatures(features) else it }

    companion object {
        fun build(playlist: Playlist, tracks: List<Track>, analysed: List<TrackFeatures>): PlaylistProfile =
            analysed.fold(PlaylistProfile(playlist).withArtists(tracks)) { profile, f -> profile.withFeatures(f) }
    }
}

/** Why a playlist fits a song. Data, not text: each screen words it in the user's language. */
sealed interface Reason {
    data class SameArtist(val artist: String, val count: Int) : Reason

    enum class Mood { Calm, Energetic, Similar }

    /** [fromName]: the playlist has no analysed songs yet, so its name set the expectation. */
    data class SameMood(val mood: Mood, val playlist: String, val fromName: Boolean) : Reason

    data class SimilarStyle(val tags: List<String>) : Reason

    data class MatchesName(val playlist: String) : Reason

    data class SimilarTempo(val bpm: Int) : Reason
}

/** A guess for the current song: which slot of the gesture circle, how sure, and why. */
data class Suggestion(
    val slotIndex: Int,
    val score: Float,
    val playlists: List<Playlist>,
    val reasons: List<Reason>,
)

private enum class Signal(val weight: Float) { Artist(0.4f), Energy(0.3f), Tags(0.25f), Name(0.15f), Tempo(0.05f) }

private data class Evidence(val value: Float, val reason: Reason? = null)

/**
 * Scores how well a song fits each playlist and proposes the best slot. Only suggests when one
 * option clearly stands out: a wrong confident suggestion is worse than none.
 */
object SuggestionEngine {
    const val MIN_SCORE = 0.5f
    const val MIN_MARGIN = 0.1f

    /** Neutral-low value for a playlist we know nothing about on a signal others do have. */
    private const val UNKNOWN = 0.35f

    /**
     * [slots] are the destinations in circle order; each one sends to one or more playlists.
     * Returns null when nothing fits well enough or two options are too close to call.
     */
    fun suggest(track: Track, features: TrackFeatures?, slots: List<List<PlaylistProfile>>): Suggestion? {
        if (slots.isEmpty()) return null
        val evidence = slots.map { profiles -> profiles.map { evidence(track, features, it) } }
        // A signal counts when some option has evidence for it; the artist one only when some
        // playlist actually has the artist (otherwise it would lower every score alike).
        val active = Signal.entries.filter { signal ->
            evidence.any { slot -> slot.any { val e = it[signal]; e != null && (signal != Signal.Artist || e.value > 0f) } }
        }
        if (active.isEmpty()) return null
        val totalWeight = active.sumOf { it.weight.toDouble() }.toFloat()

        fun scoreOf(signals: Map<Signal, Evidence>) =
            active.sumOf { ((signals[it]?.value ?: UNKNOWN) * it.weight).toDouble() }.toFloat() / totalWeight

        // A combo slot must suit every playlist in it, so it takes the average.
        val scores = evidence.map { slot -> if (slot.isEmpty()) 0f else slot.map(::scoreOf).average().toFloat() }
        val ranked = scores.indices.sortedByDescending { scores[it] }
        val best = ranked.first()
        val bestScore = scores[best]
        val runnerUp = ranked.getOrNull(1)?.let { scores[it] } ?: 0f
        if (bestScore < MIN_SCORE || bestScore - runnerUp < MIN_MARGIN) return null

        val reasons = evidence[best]
            .flatMap { signals -> active.sortedByDescending { (signals[it]?.value ?: 0f) * it.weight }.mapNotNull { signals[it]?.reason } }
            .distinct()
            .take(3)
        return Suggestion(best, bestScore, slots[best].map { it.playlist }, reasons)
    }

    private fun evidence(track: Track, features: TrackFeatures?, profile: PlaylistProfile): Map<Signal, Evidence> = buildMap {
        artist(track, profile)?.let { put(Signal.Artist, it) }
        if (features != null) {
            energy(features, profile)?.let { put(Signal.Energy, it) }
            tags(features, profile)?.let { put(Signal.Tags, it) }
            name(features, profile)?.let { put(Signal.Name, it) }
            tempo(features, profile)?.let { put(Signal.Tempo, it) }
        }
    }

    private fun artist(track: Track, profile: PlaylistProfile): Evidence? {
        val hits = track.artists.mapNotNull { artist -> profile.artists[artist.id]?.let { artist to it } }
        if (hits.isEmpty()) return if (profile.size > 0) Evidence(0f) else null
        val (artist, count) = hits.maxBy { it.second }
        val total = hits.sumOf { it.second }
        val value = 0.55f + 0.45f * (1f - exp(-total / 3f))
        return Evidence(value, Reason.SameArtist(artist.name, count))
    }

    private fun energy(features: TrackFeatures, profile: PlaylistProfile): Evidence? {
        val songEnergy = features.energy ?: return null
        val learnt = profile.energy.takeIf { it.count >= 2 }
        val target = learnt?.mean ?: profile.name?.energy ?: return null
        val spread = learnt?.spread ?: Stat.DEFAULT_SPREAD
        val d = songEnergy - target
        val value = exp(-(d * d) / (2 * spread * spread))
        val reason = if (value < 0.6f) {
            null
        } else {
            val mood = when {
                songEnergy < 0.4f -> Reason.Mood.Calm
                songEnergy > 0.65f -> Reason.Mood.Energetic
                else -> Reason.Mood.Similar
            }
            Reason.SameMood(mood, profile.playlist.name, fromName = learnt == null)
        }
        return Evidence(value, reason)
    }

    private fun tags(features: TrackFeatures, profile: PlaylistProfile): Evidence? {
        if (features.tags.isEmpty()) return null
        val mine = profile.tags
        if (mine.isEmpty()) return null
        val value = cosine(features.tags, mine)
        val shared = features.tags.keys.filter { it in mine }
            .sortedByDescending { (features.tags[it] ?: 0f) * (mine[it] ?: 0f) }
            .take(3)
        val reason = if (value >= 0.3f && shared.isNotEmpty()) Reason.SimilarStyle(shared) else null
        return Evidence(value, reason)
    }

    private fun name(features: TrackFeatures, profile: PlaylistProfile): Evidence? {
        val prior = profile.name ?: return null
        if (prior.tags.isEmpty() || features.tags.isEmpty()) return null
        val overlap = prior.tags.sumOf { (features.tags[it] ?: 0f).toDouble() }.toFloat().coerceAtMost(1f)
        val reason = if (overlap >= 0.3f) Reason.MatchesName(profile.playlist.name) else null
        return Evidence(overlap, reason)
    }

    private fun tempo(features: TrackFeatures, profile: PlaylistProfile): Evidence? {
        val bpm = features.bpm?.takeIf { it > 0 } ?: return null
        val stat = profile.bpm.takeIf { it.count >= 3 } ?: return null
        val target = stat.mean.takeIf { it > 0 } ?: return null
        // Half and double tempo count as close: trackers often disagree by a factor of two.
        val d = listOf(bpm / target, bpm * 2 / target, bpm / (target * 2)).minOf { abs(ln(it.toDouble())) }.toFloat()
        val value = exp(-(d * d) / (2 * 0.12f * 0.12f))
        return Evidence(value, if (value > 0.8f) Reason.SimilarTempo(bpm.toInt()) else null)
    }

    private fun cosine(a: Map<String, Float>, b: Map<String, Float>): Float {
        val dot = a.entries.sumOf { (k, v) -> (v * (b[k] ?: 0f)).toDouble() }
        val na = sqrt(a.values.sumOf { (it * it).toDouble() })
        val nb = sqrt(b.values.sumOf { (it * it).toDouble() })
        return if (na == 0.0 || nb == 0.0) 0f else (dot / (na * nb)).toFloat()
    }
}
