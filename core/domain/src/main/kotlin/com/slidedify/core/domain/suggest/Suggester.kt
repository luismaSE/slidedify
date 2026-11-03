package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Playlist
import com.slidedify.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Gathers features from every [FeatureSource] (once per song, then cached) and builds playlist
 * profiles from a sample of their songs. Scoring itself is in [SuggestionEngine].
 */
class Suggester(
    private val sources: List<FeatureSource>,
    private val cache: FeatureCache,
    parallelism: Int = 3,
) {
    private val permits = Semaphore(parallelism)

    /** Features of [track], running only the sources that have not looked at it yet. */
    suspend fun features(track: Track, includeExpensive: Boolean = true): TrackFeatures {
        val key = track.featureKey
        val known = safely { cache.get(key) } ?: TrackFeatures()
        val pending = sources.filter { it.id !in known.analyzedBy && (includeExpensive || !it.isExpensive) }
        if (pending.isEmpty()) return known
        val found = coroutineScope {
            pending.map { source ->
                async { permits.withPermit { safely { source.features(track) } }?.copy(analyzedBy = setOf(source.id)) }
            }.awaitAll()
        }
        val merged = found.filterNotNull().fold(known) { acc, f -> acc.merge(f) }
        if (merged != known) safely { cache.put(key, merged) }
        return merged
    }

    /**
     * Profile of [playlist] from its [tracks]: artists from all of them, sound from an evenly
     * spread sample (a few with audio analysis, which downloads the preview).
     */
    suspend fun profile(playlist: Playlist, tracks: List<Track>): PlaylistProfile {
        val sample = spreadSample(tracks, SAMPLE_SIZE)
        val analysed = coroutineScope {
            sample.mapIndexed { index, track -> async { features(track, includeExpensive = index < EXPENSIVE_SAMPLE_SIZE) } }.awaitAll()
        }
        return PlaylistProfile.build(playlist, tracks, analysed)
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val SAMPLE_SIZE = 12
        const val EXPENSIVE_SAMPLE_SIZE = 3

        /** Up to [n] items spread over the whole list (newest songs are usually at the end). */
        fun <T> spreadSample(items: List<T>, n: Int): List<T> {
            if (items.size <= n) return items.reversed()
            val step = items.size.toDouble() / n
            return (0 until n).map { items[items.size - 1 - (it * step).toInt()] }
        }
    }
}
