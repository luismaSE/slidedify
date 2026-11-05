package com.slidedify.feature.swipe

import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import java.text.Collator
import java.util.Locale

/** How the song list can be ordered. [defaultDescending] is the natural direction when picked. */
enum class TrackOrder(val defaultDescending: Boolean = false) {
    Source,
    Added(defaultDescending = true),
    Title,
    Artist,
    Album,
    Duration,
}

enum class TrackFilter { All, Pending, Reviewed }

data class TrackListOptions(
    val order: TrackOrder = TrackOrder.Source,
    val descending: Boolean = false,
    val filter: TrackFilter = TrackFilter.All,
    val query: String = "",
) {
    /** Picking a new order starts in its natural direction (newest first, A to Z…). */
    fun withOrder(newOrder: TrackOrder) = copy(order = newOrder, descending = newOrder.defaultDescending)
}

/** Orders that make sense for [queue]: "Agregadas" only when the service reports dates. */
fun availableOrders(queue: List<Track>): List<TrackOrder> =
    TrackOrder.entries.filter { it != TrackOrder.Added || queue.any { track -> track.addedAt != null } }

/**
 * Comparator for [order]; [position] gives a track's place in the original list. Songs without
 * the sorted-by value (no date) go last in both directions.
 */
fun trackComparator(order: TrackOrder, descending: Boolean, position: (Track) -> Int): Comparator<Track> {
    // Accent- and case-insensitive, in the phone's language.
    val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
    val text = compareBy<String, String>(collator) { it }
    val base: Comparator<Track> = when (order) {
        TrackOrder.Source -> compareBy(position)
        TrackOrder.Added -> compareBy { it.addedAt ?: 0L }
        TrackOrder.Title -> compareBy(text) { it.title }
        TrackOrder.Artist -> compareBy<Track, String>(text) { it.artistNames }.thenBy(text) { it.album.name }
        TrackOrder.Album -> compareBy<Track, String>(text) { it.album.name }.thenBy(position)
        TrackOrder.Duration -> compareBy { it.durationMs }
    }
    val directed = if (descending) base.reversed() else base
    return if (order == TrackOrder.Added) compareBy<Track> { it.addedAt == null }.then(directed) else directed
}

/** Stored as "Added:desc" per source. */
fun encodeOrder(order: TrackOrder, descending: Boolean) = "${order.name}:${if (descending) "desc" else "asc"}"

fun decodeOrder(value: String?): Pair<TrackOrder, Boolean>? {
    val (name, direction) = value?.split(':')?.takeIf { it.size == 2 } ?: return null
    val order = TrackOrder.entries.firstOrNull { it.name == name } ?: return null
    return order to (direction == "desc")
}

/**
 * The list to show: filtered, searched and ordered. Keeps each song's index in the queue so
 * picking one jumps to the right place.
 */
fun arrangeTracks(queue: List<Track>, sorted: Set<TrackId>, options: TrackListOptions): List<IndexedValue<Track>> {
    val needle = options.query.trim()
    val visible = queue.withIndex().filter { (_, track) ->
        val passesFilter = when (options.filter) {
            TrackFilter.All -> true
            TrackFilter.Pending -> track.id !in sorted
            TrackFilter.Reviewed -> track.id in sorted
        }
        passesFilter && (
            needle.isEmpty() ||
                track.title.contains(needle, ignoreCase = true) ||
                track.artistNames.contains(needle, ignoreCase = true) ||
                track.album.name.contains(needle, ignoreCase = true)
            )
    }
    val indexOf = queue.withIndex().associate { it.value.id to it.index }
    val comparator = trackComparator(options.order, options.descending) { indexOf[it.id] ?: 0 }
    return visible.sortedWith(compareBy(comparator) { it.value })
}
