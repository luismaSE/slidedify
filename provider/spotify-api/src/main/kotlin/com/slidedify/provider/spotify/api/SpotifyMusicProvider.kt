package com.slidedify.provider.spotify.api

import com.slidedify.core.domain.outbox.LibraryWriter
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.player.PlayerError
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderAuth
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

val SPOTIFY: ProviderId = ProviderId("spotify")

internal fun trackUri(id: TrackId) = "spotify:track:${id.value}"

/** Maps a Web API track; local files and podcast episodes are skipped (they can't be sorted). */
internal fun TrackDto.toDomain(): Track? {
    val trackId = id ?: return null
    if (isLocal || type != "track") return null
    return Track(
        id = TrackId(trackId),
        provider = SPOTIFY,
        title = name,
        artists = artists.map { Artist(it.id.orEmpty(), it.name) },
        album = Album(
            id = album.id.orEmpty(),
            name = album.name,
            imageUrl = album.images.maxByOrNull { it.width ?: 0 }?.url,
            releaseYear = album.releaseDate?.take(4)?.toIntOrNull(),
        ),
        durationMs = durationMs,
        externalUrl = externalUrls["spotify"],
    )
}

class SpotifyTrackRepository(private val api: SpotifyWebApi) : TrackRepository {
    override suspend fun tracks(source: TrackSource): List<Track> = when (source) {
        TrackSource.LikedSongs -> api.savedTracks()
        is TrackSource.FromPlaylist -> api.playlistTracks(source.id.value)
        // /recommendations is gone for new apps; top tracks are the base (see docs §5.2).
        TrackSource.Recommended -> api.topTracks()
    }.mapNotNull { it.toDomain() }.distinctBy { it.id }
}

class SpotifyPlaylistRepository(private val api: SpotifyWebApi) : PlaylistRepository {
    override suspend fun writablePlaylists(): List<Playlist> {
        val me = api.me().id
        return api.myPlaylists()
            .filter { it.owner?.id == me || it.collaborative }
            .map { it.toDomain() }
    }

    override suspend fun createPlaylist(name: String): Playlist = api.createPlaylist(name).toDomain()

    private fun PlaylistDto.toDomain() = Playlist(PlaylistId(id), SPOTIFY, name, images?.firstOrNull()?.url)
}

class SpotifyLibraryWriter(private val api: SpotifyWebApi) : LibraryWriter {
    override suspend fun addToPlaylist(playlist: PlaylistId, tracks: List<TrackId>) =
        api.addToPlaylist(playlist.value, tracks.map(::trackUri))

    override suspend fun removeFromPlaylist(playlist: PlaylistId, tracks: List<TrackId>) =
        api.removeFromPlaylist(playlist.value, tracks.map(::trackUri))

    override suspend fun setLiked(tracks: List<TrackId>, liked: Boolean) = api.setSaved(tracks.map(::trackUri), liked)
}

/**
 * Plays through the Web API on the user's active Spotify device (normally the Spotify app on the
 * same phone). Needs Premium. Position is polled and interpolated locally between polls.
 * The App Remote SDK can replace this later behind the same [Player] interface.
 */
class SpotifyWebPlayer(
    private val api: SpotifyWebApi,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 2_000,
) : Player {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    override suspend fun play(track: Track) {
        _state.value = PlayerState(track = track, isPlaying = true, positionMs = 0)
        guarded { api.play(listOf(trackUri(track.id))) }
        startTicking()
    }

    override suspend fun pause() {
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false) }
        guarded { api.pause() }
    }

    override suspend fun resume() {
        _state.update { it.copy(isPlaying = true) }
        guarded { api.resume() }
        startTicking()
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceLastPoll = 0L
            while (isActive && _state.value.isPlaying) {
                delay(TICK_MS)
                sinceLastPoll += TICK_MS
                _state.update { it.copy(positionMs = it.positionMs + TICK_MS) }
                if (sinceLastPoll >= pollIntervalMs) {
                    sinceLastPoll = 0
                    guarded { syncFromServer() }
                }
            }
        }
    }

    private suspend fun syncFromServer() {
        val playback = api.playback() ?: return
        val playing = playback.item?.id
        _state.update { current ->
            // Ignore stale responses about a previous track.
            val expected = current.track?.id?.value
            if (playing != null && expected != null && playing != expected) current
            else current.copy(isPlaying = playback.isPlaying, positionMs = playback.progressMs ?: current.positionMs)
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
            _state.update { it.copy(error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyApiException) {
            val error = when (e.code) {
                403 -> PlayerError.PremiumRequired
                404 -> PlayerError.Disconnected("Abrí Spotify en este teléfono para reproducir")
                else -> PlayerError.Disconnected(e.message)
            }
            _state.update { it.copy(isPlaying = false, error = error) }
        } catch (e: java.io.IOException) {
            _state.update { it.copy(isPlaying = false, error = PlayerError.Disconnected(e.message)) }
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}

/** Signed-in state, derived from stored tokens and the profile endpoint. */
class SpotifyProviderAuth(
    private val tokens: TokenProvider,
    private val api: SpotifyWebApi,
) : ProviderAuth {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Call at startup and after the login redirect. */
    suspend fun refresh() {
        _state.value = try {
            if (tokens.accessToken() == null) AuthState.SignedOut else AuthState.SignedIn(api.me().let { it.displayName ?: it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyNotSignedInException) {
            AuthState.SignedOut
        } catch (e: java.io.IOException) {
            AuthState.Error(e.message ?: "Error de conexión")
        }
    }

    suspend fun completeSignIn(code: String, verifier: String) {
        tokens.signIn(code, verifier)
        refresh()
    }

    override suspend fun signOut() {
        tokens.signOut()
        _state.value = AuthState.SignedOut
    }
}

class SpotifyMusicProvider(
    override val auth: SpotifyProviderAuth,
    override val tracks: SpotifyTrackRepository,
    override val playlists: SpotifyPlaylistRepository,
    override val writer: SpotifyLibraryWriter,
    override val player: Player,
) : MusicProvider {
    override val id = SPOTIFY
    override val displayName = "Spotify"
    override val capabilities = ProviderCapabilities(
        canLike = true,
        canCreatePlaylists = true,
        canPlayFullTracks = true, // Premium; free accounts get PlayerError.PremiumRequired
        sources = setOf(TrackSource.Kind.LikedSongs, TrackSource.Kind.Playlist, TrackSource.Kind.Recommended),
        maxTracksPerWrite = SpotifyEndpoints.MAX_LIBRARY_WRITE,
    )

    companion object {
        /** Wires everything from the low-level pieces; the Android module only supplies storage and scope. */
        fun create(api: SpotifyWebApi, tokens: TokenProvider, scope: CoroutineScope) = SpotifyMusicProvider(
            auth = SpotifyProviderAuth(tokens, api),
            tracks = SpotifyTrackRepository(api),
            playlists = SpotifyPlaylistRepository(api),
            writer = SpotifyLibraryWriter(api),
            player = SpotifyWebPlayer(api, scope),
        )
    }
}
