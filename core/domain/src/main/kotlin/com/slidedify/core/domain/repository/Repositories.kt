package com.slidedify.core.domain.repository

import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * Liked songs added since the user last caught up (see NewLikes): a view over [LikedSongs]
     * that shares its progress. Services serve it as their liked songs.
     */
    data object NewLiked : TrackSource {
        override val kind = Kind.NewLiked
    }

    enum class Kind { LikedSongs, Playlist, Recommended, NewLiked }
}

/** Stable key of a source (recent lists, chosen order). */
val TrackSource.key: String
    get() = when (this) {
        TrackSource.LikedSongs -> "liked"
        TrackSource.NewLiked -> "new-liked"
        TrackSource.Recommended -> "recommended"
        is TrackSource.FromPlaylist -> "playlist:${id.value}"
    }

/** Key under which sorting progress is kept: new likes count as progress on liked songs. */
val TrackSource.progressKey: String
    get() = if (this == TrackSource.NewLiked) TrackSource.LikedSongs.key else key

/** What to look for when finding a track in another service. */
data class TrackQuery(
    val title: String,
    val artist: String,
    val durationMs: Long = 0,
    val isrc: String? = null,
)

interface TrackRepository {
    suspend fun tracks(source: TrackSource): List<Track>

    /** Catalog search, used to match tracks across services. Only called when `capabilities.canSearch`. */
    suspend fun search(query: TrackQuery): List<Track> = emptyList()

    /**
     * Tracks in pages, so big sources (thousands of songs) show the first page right away while
     * the rest keeps loading. Providers with real paging override this.
     */
    fun pages(source: TrackSource): Flow<List<Track>> = flow { emit(tracks(source)) }
}

/**
 * Which tracks of each source were already sorted (added or skipped), so reopening a source
 * continues where the user left off instead of starting over.
 */
interface SortProgressRepository {
    suspend fun sortedTracks(provider: ProviderId, sourceKey: String): Set<TrackId>

    suspend fun markSorted(provider: ProviderId, sourceKey: String, track: TrackId)

    suspend fun unmarkSorted(provider: ProviderId, sourceKey: String, track: TrackId)

    /** Forget the progress of a source ("start over"). */
    suspend fun clear(provider: ProviderId, sourceKey: String)

    /** How many tracks were sorted in each source of [provider], keyed by [TrackSource.key]. */
    fun sortedCounts(provider: ProviderId): Flow<Map<String, Int>> = flowOf(emptyMap())
}

interface PlaylistRepository {
    /** Playlists the user can write to (owned or collaborative). */
    suspend fun writablePlaylists(): List<Playlist>

    /**
     * Every playlist in the user's library, including followed ones they can't edit
     * ([Playlist.canWrite] false). Those can be sorted (as a source) but are never destinations.
     */
    suspend fun allPlaylists(): List<Playlist> = writablePlaylists()

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
