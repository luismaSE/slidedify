package com.slidedify.core.testing

import com.slidedify.core.domain.outbox.LibraryWriter
import com.slidedify.core.domain.outbox.SyncStatus
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderAuth
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.SortProgressRepository
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.repository.key
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

fun testTrack(n: Int, artist: String = "Artist", provider: ProviderId = ProviderId("test")) = Track(
    id = TrackId("t$n"),
    provider = provider,
    title = "Song $n",
    artists = listOf(Artist(artist.lowercase(), artist)),
    album = Album("al$n", "Album $n", imageUrl = null, releaseYear = 2020),
    durationMs = 180_000,
)

fun testPlaylist(name: String, provider: ProviderId = ProviderId("test"), canWrite: Boolean = true, trackCount: Int? = null) =
    Playlist(PlaylistId(name.lowercase()), provider, name, trackCount = trackCount, canWrite = canWrite)

/** A music service in memory. Songs per source are keyed by [TrackSource.key]. */
class FakeProvider(
    id: String = "test",
    override val displayName: String = "Test",
    var library: List<Playlist> = emptyList(),
    val songs: MutableMap<String, List<Track>> = mutableMapOf(),
    signedIn: Boolean = true,
    override val capabilities: ProviderCapabilities = ProviderCapabilities(),
    /** When set, loading playlists fails with this. */
    var failure: Exception? = null,
) : MusicProvider {
    override val id = ProviderId(id)

    val authState = MutableStateFlow<AuthState>(if (signedIn) AuthState.SignedIn("Ana Pérez") else AuthState.SignedOut)
    var signOutCalls = 0

    override val auth = object : ProviderAuth {
        override val state: StateFlow<AuthState> = authState

        override suspend fun signOut() {
            signOutCalls++
            authState.value = AuthState.SignedOut
        }
    }

    override val tracks = object : TrackRepository {
        override suspend fun tracks(source: TrackSource): List<Track> = songs[source.key].orEmpty()
    }

    override val playlists = object : PlaylistRepository {
        override suspend fun writablePlaylists(): List<Playlist> = allPlaylists().filter { it.canWrite }

        override suspend fun allPlaylists(): List<Playlist> {
            failure?.let { throw it }
            return library
        }

        override suspend fun createPlaylist(name: String): Playlist =
            Playlist(PlaylistId(name.lowercase()), this@FakeProvider.id, name).also { library = library + it }
    }

    override val writer = object : LibraryWriter {
        override suspend fun addToPlaylist(playlist: PlaylistId, tracks: List<TrackId>) = Unit

        override suspend fun removeFromPlaylist(playlist: PlaylistId, tracks: List<TrackId>) = Unit

        override suspend fun setLiked(tracks: List<TrackId>, liked: Boolean) = Unit
    }

    override val player = object : Player {
        override val state = MutableStateFlow(PlayerState())

        override suspend fun play(track: Track) {
            state.value = PlayerState(track = track, isPlaying = true)
        }

        override suspend fun pause() = state.update { it.copy(isPlaying = false) }

        override suspend fun resume() = state.update { it.copy(isPlaying = true) }
    }
}

class MemorySettings(initial: SortSettings = SortSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<SortSettings> = state

    override suspend fun update(transform: (SortSettings) -> SortSettings) = state.update(transform)
}

class MemoryProgress : SortProgressRepository {
    private val sorted = MutableStateFlow<Set<Triple<ProviderId, String, TrackId>>>(emptySet())

    override suspend fun sortedTracks(provider: ProviderId, sourceKey: String): Set<TrackId> =
        sorted.value.filter { it.first == provider && it.second == sourceKey }.mapTo(HashSet()) { it.third }

    override suspend fun markSorted(provider: ProviderId, sourceKey: String, track: TrackId) =
        sorted.update { it + Triple(provider, sourceKey, track) }

    override suspend fun unmarkSorted(provider: ProviderId, sourceKey: String, track: TrackId) =
        sorted.update { it - Triple(provider, sourceKey, track) }

    override suspend fun clear(provider: ProviderId, sourceKey: String) =
        sorted.update { all -> all.filterNot { it.first == provider && it.second == sourceKey }.toSet() }

    override fun sortedCounts(provider: ProviderId): Flow<Map<String, Int>> =
        sorted.map { all -> all.filter { it.first == provider }.groupingBy { it.second }.eachCount() }
}

class MemorySyncStatus(pending: Int = 0) : SyncStatus {
    override val pendingCount = MutableStateFlow(pending)
}
