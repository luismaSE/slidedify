package com.slidedify.core.domain.transfer

import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class TransferMode {
    /** Add the tracks to the target; the source stays as is. */
    Copy,

    /** Copy, then take the tracks that were found out of the source (unmatched ones stay). */
    Move,

    /** Both sides end up with the union: missing tracks are added in each direction. */
    Sync,
}

sealed interface TransferTarget {
    data class NewPlaylist(val name: String) : TransferTarget

    data class Existing(val playlist: PlaylistId) : TransferTarget
}

data class TransferRequest(
    val from: ProviderId,
    val source: TrackSource,
    val to: ProviderId,
    val target: TransferTarget,
    val mode: TransferMode,
)

data class TransferReport(
    val target: Playlist,
    val sourceCount: Int,
    val addedToTarget: Int,
    val alreadyInTarget: Int,
    val addedToSource: Int,
    val removedFromSource: Int,
    /** Tracks that couldn't be found in the other service. */
    val notFound: List<Track>,
)

sealed interface TransferProgress {
    data class Reading(val serviceName: String) : TransferProgress

    data class Matching(val done: Int, val total: Int) : TransferProgress

    data object Writing : TransferProgress

    data class Done(val report: TransferReport) : TransferProgress

    data class Failed(val message: String) : TransferProgress
}

/**
 * Copies, moves or syncs a playlist (or liked songs) between two services.
 * Tracks are matched with [TrackMatcher] (ISRC first, then title/artist/duration via search);
 * writes go through the offline outbox ([LibraryRepository]) so they survive bad connectivity.
 */
class PlaylistTransfer(
    private val registry: ProviderRegistry,
    private val library: LibraryRepository,
) {
    fun run(request: TransferRequest): Flow<TransferProgress> = flow {
        try {
            val from = requireNotNull(registry.find(request.from)) { "Servicio de origen no disponible" }
            val to = requireNotNull(registry.find(request.to)) { "Servicio de destino no disponible" }
            require(to.capabilities.canSearch) { "${to.displayName} todavía no permite buscar canciones" }
            if (request.mode == TransferMode.Sync) {
                require(request.target is TransferTarget.Existing) { "Para sincronizar elegí una playlist existente" }
                require(from.capabilities.canSearch) { "${from.displayName} todavía no permite buscar canciones" }
            }

            emit(TransferProgress.Reading(from.displayName))
            val sourceTracks = from.tracks.tracks(request.source).distinctBy { it.id }

            val target: Playlist = when (val t = request.target) {
                is TransferTarget.NewPlaylist -> to.playlists.createPlaylist(t.name)
                is TransferTarget.Existing -> to.playlists.writablePlaylists().firstOrNull { it.id == t.playlist }
                    ?: error("La playlist de destino ya no existe")
            }
            emit(TransferProgress.Reading(to.displayName))
            val targetTracks = if (request.target is TransferTarget.Existing) {
                to.tracks.tracks(TrackSource.FromPlaylist(target.id))
            } else {
                emptyList()
            }

            // Source → target.
            val forward = matchAll(sourceTracks, existing = targetTracks, into = to) { done ->
                emit(TransferProgress.Matching(done, sourceTracks.size))
            }

            emit(TransferProgress.Writing)
            val toAdd = forward.newMatches.distinctBy { it.id }
            toAdd.forEach { library.apply(LibraryEffect.AddToPlaylists(it, setOf(target.id))) }

            var removedFromSource = 0
            if (request.mode == TransferMode.Move) {
                forward.matchedSources.forEach { track ->
                    sourceRemoval(request.source, track)?.let { library.apply(it); removedFromSource++ }
                }
            }

            var addedToSource = 0
            if (request.mode == TransferMode.Sync) {
                // Target → source: what the target has and the source doesn't.
                val backward = matchAll(targetTracks, existing = sourceTracks, into = from) {}
                backward.newMatches.distinctBy { it.id }.forEach { track ->
                    sourceAddition(request.source, track)?.let { library.apply(it); addedToSource++ }
                }
            }

            emit(
                TransferProgress.Done(
                    TransferReport(
                        target = target,
                        sourceCount = sourceTracks.size,
                        addedToTarget = toAdd.size,
                        alreadyInTarget = forward.alreadyThere,
                        addedToSource = addedToSource,
                        removedFromSource = removedFromSource,
                        notFound = forward.notFound,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(TransferProgress.Failed(e.message.orEmpty()))
        }
    }

    private class MatchResult(
        val newMatches: List<Track>,
        val matchedSources: List<Track>,
        val alreadyThere: Int,
        val notFound: List<Track>,
    )

    /** For each track: already in [existing]? else search it in [into]. */
    private suspend fun matchAll(
        tracks: List<Track>,
        existing: List<Track>,
        into: MusicProvider,
        onProgress: suspend (Int) -> Unit,
    ): MatchResult {
        val newMatches = mutableListOf<Track>()
        val matchedSources = mutableListOf<Track>()
        val notFound = mutableListOf<Track>()
        var alreadyThere = 0
        val index = ExistingIndex(existing)
        tracks.forEachIndexed { position, track ->
            if (index.contains(track)) {
                alreadyThere++
                matchedSources += track
            } else {
                val found = TrackMatcher.best(track, into.tracks.search(TrackMatcher.queryFor(track)))
                if (found != null) {
                    newMatches += found
                    matchedSources += track
                } else {
                    notFound += track
                }
            }
            onProgress(position + 1)
        }
        return MatchResult(newMatches, matchedSources, alreadyThere, notFound)
    }

    /** Fast "is it already there?" for big playlists: by ISRC, then among same-title tracks only. */
    private class ExistingIndex(tracks: List<Track>) {
        private val byIsrc = tracks.mapNotNull { t -> t.isrc?.uppercase()?.let { it to t } }.toMap()
        private val byTitle = tracks.groupBy { TrackMatcher.normalizeTitle(it.title) }

        fun contains(track: Track): Boolean {
            track.isrc?.uppercase()?.let { if (it in byIsrc) return true }
            val candidates = byTitle[TrackMatcher.normalizeTitle(track.title)].orEmpty()
            return TrackMatcher.best(track, candidates) != null
        }
    }

    private fun sourceRemoval(source: TrackSource, track: Track): LibraryEffect? = when (source) {
        is TrackSource.FromPlaylist -> LibraryEffect.RemoveFromPlaylists(track, setOf(source.id))
        TrackSource.LikedSongs -> LibraryEffect.SetLiked(track, false)
        TrackSource.Recommended -> null
    }

    private fun sourceAddition(source: TrackSource, track: Track): LibraryEffect? = when (source) {
        is TrackSource.FromPlaylist -> LibraryEffect.AddToPlaylists(track, setOf(source.id))
        TrackSource.LikedSongs -> LibraryEffect.SetLiked(track, true)
        TrackSource.Recommended -> null
    }
}
