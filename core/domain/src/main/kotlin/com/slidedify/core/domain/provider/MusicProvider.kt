package com.slidedify.core.domain.provider

import com.slidedify.core.domain.outbox.LibraryWriter
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.ProviderId
import kotlinx.coroutines.flow.StateFlow

/**
 * What a provider can do. Features read this instead of checking which provider is active,
 * so adding a provider never means touching feature code.
 */
data class ProviderCapabilities(
    /** Has a "liked songs" collection that can be written (double tap). */
    val canLike: Boolean = true,
    /** Can play whole tracks on demand (some services only allow previews or need a paid plan). */
    val canPlayFullTracks: Boolean = true,
    /** Sources the user can pick when starting a session. */
    val sources: Set<TrackSource.Kind> = setOf(TrackSource.Kind.LikedSongs, TrackSource.Kind.Playlist),
    /** Max tracks per write request; the sync layer batches accordingly. */
    val maxTracksPerWrite: Int = 100,
)

sealed interface AuthState {
    data object SignedOut : AuthState

    data class SignedIn(val userName: String) : AuthState

    data class Error(val message: String) : AuthState
}

/**
 * Session state for a provider. Starting the sign-in flow needs an Activity, so each provider
 * module ships its own sign-in UI; the domain only observes the result.
 */
interface ProviderAuth {
    val state: StateFlow<AuthState>

    suspend fun signOut()
}

/**
 * Port that every music service implements (Spotify, YouTube Music, Tidal...).
 * Each implementation lives in its own `:provider:*` module and contributes itself to the
 * [ProviderRegistry]; nothing outside that module knows which service it is.
 */
interface MusicProvider {
    val id: ProviderId
    val displayName: String
    val capabilities: ProviderCapabilities

    val auth: ProviderAuth
    val tracks: TrackRepository
    val playlists: PlaylistRepository

    /** Remote writes. Called only by the outbox sync, never directly by features. */
    val writer: LibraryWriter
    val player: Player
}
