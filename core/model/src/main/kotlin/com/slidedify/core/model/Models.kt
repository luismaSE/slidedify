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
    /** International Standard Recording Code: identifies the same recording across services, when known. */
    val isrc: String? = null,
    /** When it was added to the source (liked or put in the playlist), epoch millis, if known. */
    val addedAt: Long? = null,
) {
    val artistNames: String get() = artists.joinToString(", ") { it.name }
}

data class Playlist(
    val id: PlaylistId,
    val provider: ProviderId,
    val name: String,
    val imageUrl: String? = null,
    /** Number of songs, when the service reports it (shown on the home screen). */
    val trackCount: Int? = null,
)

/**
 * Position of a destination on the gesture circle (0 = first, the topmost one going clockwise).
 * The circle is split evenly between all destinations plus one "cancel" sector.
 */
@JvmInline
value class Slot(val index: Int)

/**
 * Ordered destinations of the gesture circle. Each destination sends a track to one playlist,
 * or to several at once (a "combo").
 */
data class GestureMap(
    val destinations: List<Set<PlaylistId>>,
) {
    init {
        require(destinations.size <= MAX_DESTINATIONS) { "At most $MAX_DESTINATIONS destinations" }
    }

    val size: Int get() = destinations.size
    val slots: List<Slot> get() = destinations.indices.map(::Slot)
    val isFull: Boolean get() = size >= MAX_DESTINATIONS

    fun playlistsAt(slot: Slot): Set<PlaylistId> = destinations.getOrNull(slot.index).orEmpty()

    fun playlistsFor(slots: Set<Slot>): Set<PlaylistId> = slots.sortedBy { it.index }.flatMapTo(linkedSetOf(), ::playlistsAt)

    fun contains(playlist: PlaylistId): Boolean = destinations.any { playlist in it }

    /** Adds a destination at the end (ignored when full or empty). */
    fun add(playlists: Set<PlaylistId>): GestureMap =
        if (isFull || playlists.isEmpty()) this else GestureMap(destinations + listOf(playlists))

    /** Replaces the playlists of [slot]; an empty set removes the destination. */
    fun replace(slot: Slot, playlists: Set<PlaylistId>): GestureMap = when {
        slot.index !in destinations.indices -> this
        playlists.isEmpty() -> remove(slot)
        else -> GestureMap(destinations.toMutableList().also { it[slot.index] = playlists })
    }

    fun remove(slot: Slot): GestureMap = GestureMap(destinations.filterIndexed { index, _ -> index != slot.index })

    /** Moves a destination to another place on the circle (the others shift to make room). */
    fun move(from: Slot, to: Slot): GestureMap {
        if (from.index !in destinations.indices || to.index !in destinations.indices || from == to) return this
        val list = destinations.toMutableList()
        val moved = list.removeAt(from.index)
        list.add(to.index, moved)
        return GestureMap(list)
    }

    /** Drops playlists that no longer exist, and destinations left empty. */
    fun retainOnly(existing: Set<PlaylistId>): GestureMap =
        GestureMap(destinations.map { it intersect existing }.filter { it.isNotEmpty() })

    companion object {
        const val MAX_DESTINATIONS = 8

        val Empty = GestureMap(emptyList())

        fun of(playlists: List<PlaylistId>): GestureMap = GestureMap(playlists.map { setOf(it) })

        fun of(playlist: PlaylistId): GestureMap = of(listOf(playlist))
    }
}
