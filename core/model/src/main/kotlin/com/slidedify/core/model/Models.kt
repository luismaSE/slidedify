package com.slidedify.core.model

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

data class Track(
    val id: TrackId,
    /** Spotify URI, e.g. `spotify:track:...`. Used for playback and playlist writes. */
    val uri: String,
    val title: String,
    val artists: List<Artist>,
    val album: Album,
    val durationMs: Long,
) {
    val artistNames: String get() = artists.joinToString(", ") { it.name }
}

data class Playlist(
    val id: PlaylistId,
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
