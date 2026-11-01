package com.slidedify.core.model

/** Identifies a music service (e.g. "spotify", "youtube-music", "tidal"). */
@JvmInline
value class ProviderId(val value: String)

/** Track id as given by its provider; only unique together with [Track.provider]. */
@JvmInline
value class TrackId(val value: String)

@JvmInline
value class PlaylistId(val value: String)

data class Artist(
    val id: String,
    val name: String,
)

data class Album(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val releaseYear: Int?,
)

/** A song, independent of the service it comes from. */
data class Track(
    val id: TrackId,
    val provider: ProviderId,
    val title: String,
    val artists: List<Artist>,
    val album: Album,
    val durationMs: Long,
    /** Link to open the track in the provider's own app or website (required by most providers' terms). */
    val externalUrl: String? = null,
) {
    val artistNames: String get() = artists.joinToString(", ") { it.name }
}

data class Playlist(
    val id: PlaylistId,
    val provider: ProviderId,
    val name: String,
    val imageUrl: String? = null,
)

/**
 * The five destinations reachable by swipe or by the joystick wheel.
 * They all live on the right half of the circle; the left half is reserved for skip/cancel.
 */
enum class Slot(val centerDegrees: Float) {
    UP(90f),
    UP_RIGHT(45f),
    RIGHT(0f),
    DOWN_RIGHT(315f),
    DOWN(270f),
}

/**
 * Which playlists each slot sends a track to. A slot can point to several playlists (a "combo").
 * Slots without playlists are disabled.
 */
data class GestureMap(
    val targets: Map<Slot, Set<PlaylistId>>,
) {
    val enabledSlots: Set<Slot> get() = targets.filterValues { it.isNotEmpty() }.keys

    fun playlistsFor(slots: Set<Slot>): Set<PlaylistId> = slots.flatMapTo(linkedSetOf()) { targets[it].orEmpty() }

    companion object {
        /** Assigns playlists to slots in order: UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN. */
        fun inOrder(playlists: List<Playlist>): GestureMap =
            GestureMap(Slot.entries.zip(playlists).associate { (slot, playlist) -> slot to setOf(playlist.id) })
    }
}
