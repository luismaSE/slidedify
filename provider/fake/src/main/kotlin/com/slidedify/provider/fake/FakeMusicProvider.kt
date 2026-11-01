package com.slidedify.provider.fake

import android.util.Log
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderAuth
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo provider with invented songs and simulated playback. It lets the gestures be tried without
 * any account, and doubles as the reference for implementing a real provider module.
 */
@Singleton
class FakeMusicProvider @Inject constructor(
    override val tracks: FakeTrackRepository,
    override val playlists: FakePlaylistRepository,
    override val library: InMemoryLibraryRepository,
    override val player: FakePlayer,
) : MusicProvider {
    override val id = ID
    override val displayName = "Demo"
    override val capabilities = ProviderCapabilities()
    override val auth: ProviderAuth = object : ProviderAuth {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn("demo"))

        override suspend fun signOut() = Unit
    }

    companion object {
        val ID = ProviderId("demo")
    }
}

/** Sample library with invented songs. */
internal object FakeCatalog {
    val playlists = listOf(
        Playlist(PlaylistId("gym"), FakeMusicProvider.ID, "Gym"),
        Playlist(PlaylistId("chill"), FakeMusicProvider.ID, "Chill"),
        Playlist(PlaylistId("focus"), FakeMusicProvider.ID, "Focus"),
        Playlist(PlaylistId("party"), FakeMusicProvider.ID, "Fiesta"),
        Playlist(PlaylistId("running"), FakeMusicProvider.ID, "Running"),
    )

    private val rows = listOf(
        Triple("Neon Skies", "The Midnight Owls", "Afterglow" to 2019),
        Triple("Paper Planes Over Lima", "Costa Azul", "Litoral" to 2021),
        Triple("Slow Orbit", "Lunar Tides", "Gravity Wells" to 2018),
        Triple("Concrete Garden", "Urban Ferns", "Rooftops" to 2022),
        Triple("Last Train South", "Hollow Pines", "Northbound" to 2016),
        Triple("Fuego Lento", "Brisa Roja", "Verano Eterno" to 2023),
        Triple("Static Hearts", "Velvet Circuit", "Analog Love" to 2020),
        Triple("Run the Ridge", "Summit Club", "Altitude" to 2024),
        Triple("Glass Lanterns", "Mira Sol", "Faroles" to 2017),
        Triple("Deep Blue Hours", "The Quiet Harbor", "Low Tide" to 2015),
        Triple("Bassline Republic", "DJ Kompass", "Nightshift" to 2025),
        Triple("Hojas de Otoño", "Valle Norte", "Estaciones" to 2014),
    )

    val tracks: List<Track> = rows.mapIndexed { index, (title, artist, album) ->
        Track(
            id = TrackId("fake$index"),
            provider = FakeMusicProvider.ID,
            title = title,
            artists = listOf(Artist("artist$index", artist)),
            album = Album("album$index", album.first, imageUrl = null, releaseYear = album.second),
            durationMs = 150_000L + index * 13_000L,
        )
    }
}

@Singleton
class FakeTrackRepository @Inject constructor() : TrackRepository {
    override suspend fun tracks(source: TrackSource): List<Track> = FakeCatalog.tracks
}

@Singleton
class FakePlaylistRepository @Inject constructor() : PlaylistRepository {
    override suspend fun writablePlaylists(): List<Playlist> = FakeCatalog.playlists
}

/** Logs effects instead of calling Spotify. */
@Singleton
class InMemoryLibraryRepository @Inject constructor() : LibraryRepository {
    private val _applied = MutableStateFlow<List<LibraryEffect>>(emptyList())
    val applied: StateFlow<List<LibraryEffect>> = _applied.asStateFlow()

    override suspend fun apply(effect: LibraryEffect) {
        Log.d("Slidedify", "Library effect: $effect")
        _applied.update { it + effect }
    }
}

/** Simulates playback by advancing the position; no audio. */
@Singleton
class FakePlayer @Inject constructor() : Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    override suspend fun play(track: Track) {
        _state.value = PlayerState(track = track, isPlaying = true, positionMs = 0)
        startTicking()
    }

    override suspend fun pause() {
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false) }
    }

    override suspend fun resume() {
        if (_state.value.track == null) return
        _state.update { it.copy(isPlaying = true) }
        startTicking()
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                _state.update { current ->
                    val duration = current.track?.durationMs ?: 0
                    val next = current.positionMs + TICK_MS
                    if (next >= duration) {
                        current.copy(positionMs = duration, isPlaying = false)
                    } else {
                        current.copy(positionMs = next)
                    }
                }
                if (!_state.value.isPlaying) break
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
