package com.slidedify.core.domain.player

import com.slidedify.core.model.Track
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val error: PlayerError? = null,
    /** Playing a short preview (e.g. 30 s) instead of the full track. */
    val isPreview: Boolean = false,
    /** Length of what is playing when it differs from the track (previews). */
    val playingDurationMs: Long? = null,
    /** Informational message (not an error), e.g. why a preview is used instead of the full track. */
    val notice: String? = null,
) {
    val durationMs: Long get() = playingDurationMs ?: track?.durationMs ?: 0
}

sealed interface PlayerError {
    data object SpotifyNotInstalled : PlayerError

    data object PremiumRequired : PlayerError

    data class Disconnected(val message: String?) : PlayerError
}

/**
 * Finds a short audio preview for a track on a public catalog (by title/artist/duration),
 * independent of the music provider. Used when full playback isn't available.
 */
interface PreviewSource {
    /** URL of a short audio clip, or null if none was found. */
    suspend fun previewUrl(track: Track): String?
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
