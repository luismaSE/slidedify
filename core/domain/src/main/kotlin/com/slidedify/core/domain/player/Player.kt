package com.slidedify.core.domain.player

import com.slidedify.core.model.Track
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val error: PlayerError? = null,
)

sealed interface PlayerError {
    data object SpotifyNotInstalled : PlayerError

    data object PremiumRequired : PlayerError

    data class Disconnected(val message: String?) : PlayerError
}

/**
 * Playback abstraction. The production implementation wraps the Spotify App Remote SDK;
 * features only see this interface, so they can be tested and previewed with fakes.
 */
interface Player {
    val state: StateFlow<PlayerState>

    suspend fun play(track: Track)

    suspend fun pause()

    suspend fun resume()
}
