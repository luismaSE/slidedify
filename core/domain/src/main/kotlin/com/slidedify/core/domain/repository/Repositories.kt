package com.slidedify.core.domain.repository

import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Track

/** Where the tracks to sort come from. */
sealed interface TrackSource {
    data object LikedSongs : TrackSource

    data class FromPlaylist(val id: PlaylistId) : TrackSource

    data object Recommended : TrackSource
}

interface TrackRepository {
    suspend fun tracks(source: TrackSource): List<Track>
}

interface PlaylistRepository {
    /** Playlists the user can write to (owned or collaborative). */
    suspend fun writablePlaylists(): List<Playlist>
}

/**
 * Applies changes to the user's library. The real implementation queues them in a local outbox and
 * syncs in batches (see docs/PROPUESTA.md §5.4), so callers never wait for the network.
 */
interface LibraryRepository {
    suspend fun apply(effect: LibraryEffect)
}
