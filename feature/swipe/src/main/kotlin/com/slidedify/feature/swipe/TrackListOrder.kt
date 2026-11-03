package com.slidedify.feature.swipe

import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import java.text.Collator
import java.util.Locale

/** How the song list can be ordered. [defaultDescending] is the natural direction when picked. */
enum class TrackOrder(val label: String, val defaultDescending: Boolean = false) {
    Source("Orden de la lista"),
    Added("Agregadas", defaultDescending = true),
    Title("Título"),
    Artist("Artista"),
    Album("Álbum"),
    Duration("Duración"),
}

enum class TrackFilter(val label: String) { All("Todas"), Pending("Pendientes"), Reviewed("Revisadas") }

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
 * The list to show: filtered, searched and ordered. Keeps each song's index in the queue so
 * picking one jumps to the right place. Songs without the sorted-by value go last either way.
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
    val collator = Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY }
    val text = compareBy<String, String>(collator) { it }
    val comparator: Comparator<IndexedValue<Track>> = when (options.order) {
        TrackOrder.Source -> compareBy { it.index }
        TrackOrder.Added -> compareBy { it.value.addedAt ?: Long.MIN_VALUE }
        TrackOrder.Title -> compareBy(text) { it.value.title }
        TrackOrder.Artist -> compareBy<IndexedValue<Track>, String>(text) { it.value.artistNames }.thenBy(text) { it.value.album.name }
        TrackOrder.Album -> compareBy<IndexedValue<Track>, String>(text) { it.value.album.name }.thenBy { it.index }
        TrackOrder.Duration -> compareBy { it.value.durationMs }
    }
    val ordered = visible.sortedWith(if (options.descending) comparator.reversed() else comparator)
    // Missing dates always at the end, whatever the direction.
    return if (options.order == TrackOrder.Added) ordered.sortedBy { it.value.addedAt == null } else ordered
}
