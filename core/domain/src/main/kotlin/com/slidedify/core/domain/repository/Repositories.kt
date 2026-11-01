package com.slidedify.core.domain.repository

import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Track

/** Where the tracks to sort come from. */
sealed interface TrackSource {
    val kind: Kind

    data object LikedSongs : TrackSource {
        override val kind = Kind.LikedSongs
    }

    data class FromPlaylist(val id: PlaylistId) : TrackSource {
        override val kind = Kind.Playlist
    }

    data object Recommended : TrackSource {
        override val kind = Kind.Recommended
    }

    enum class Kind { LikedSongs, Playlist, Recommended }
}

interface TrackRepository {
    suspend fun tracks(source: TrackSource): List<Track>
}

interface PlaylistRepository {
    /** Playlists the user can write to (owned or collaborative). */
    suspend fun writablePlaylists(): List<Playlist>

    /**
     * Creates a playlist and returns it. Unlike track writes this is immediate (not queued),
     * because the new id is needed right away. Only called when `capabilities.canCreatePlaylists`.
     */
    suspend fun createPlaylist(name: String): Playlist
}

/**
 * Applies changes to the user's library. Real implementations queue them in a local outbox and
 * sync in batches (see docs/PROPUESTA.md §5.4), so callers never wait for the network.
 */
interface LibraryRepository {
    suspend fun apply(effect: LibraryEffect)
}
