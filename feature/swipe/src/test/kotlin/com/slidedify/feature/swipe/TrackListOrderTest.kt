package com.slidedify.feature.swipe

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import org.junit.Test
import kotlin.test.assertEquals

class TrackListOrderTest {
    private fun track(title: String, artist: String, added: Long?, seconds: Long, album: String = "A") = Track(
        TrackId(title), ProviderId("p"), title, listOf(Artist(artist, artist)), Album(album, album, null, null), seconds * 1000, addedAt = added,
    )

    private val queue = listOf(
        track("Zamba", "Mercedes", added = 100, seconds = 200),
        track("árbol", "Bersuit", added = 300, seconds = 150),
        track("Bohemian", "Queen", added = null, seconds = 355),
        track("Ana", "Charly", added = 200, seconds = 120),
    )

    private fun titles(options: TrackListOptions, sorted: Set<TrackId> = emptySet()) =
        arrangeTracks(queue, sorted, options).map { it.value.title }

    @Test
    fun `alphabetical ignores accents and case, both ways`() {
        assertEquals(listOf("Ana", "árbol", "Bohemian", "Zamba"), titles(TrackListOptions(order = TrackOrder.Title)))
        assertEquals(listOf("Zamba", "Bohemian", "árbol", "Ana"), titles(TrackListOptions(order = TrackOrder.Title, descending = true)))
    }

    @Test
    fun `recently added starts newest first and songs without date go last`() {
        val options = TrackListOptions().withOrder(TrackOrder.Added)
        assertEquals(listOf("árbol", "Ana", "Zamba", "Bohemian"), titles(options))
        assertEquals(listOf("Zamba", "Ana", "árbol", "Bohemian"), titles(options.copy(descending = false)))
    }

    @Test
    fun `duration, artist and original order`() {
        assertEquals(listOf("Ana", "árbol", "Zamba", "Bohemian"), titles(TrackListOptions(order = TrackOrder.Duration)))
        assertEquals(listOf("árbol", "Ana", "Zamba", "Bohemian"), titles(TrackListOptions(order = TrackOrder.Artist)))
        assertEquals(listOf("Ana", "Bohemian", "árbol", "Zamba"), titles(TrackListOptions(order = TrackOrder.Source, descending = true)))
    }

    @Test
    fun `filters and search keep the queue index`() {
        val reviewed = setOf(TrackId("Zamba"))
        assertEquals(listOf("árbol", "Bohemian", "Ana"), titles(TrackListOptions(filter = TrackFilter.Pending), reviewed))
        assertEquals(listOf("Zamba"), titles(TrackListOptions(filter = TrackFilter.Reviewed), reviewed))
        val found = arrangeTracks(queue, emptySet(), TrackListOptions(query = "queen"))
        assertEquals(listOf(2), found.map { it.index })
    }

    @Test
    fun `recently added is offered only when dates are known`() {
        assertEquals(true, TrackOrder.Added in availableOrders(queue))
        assertEquals(false, TrackOrder.Added in availableOrders(queue.map { it.copy(addedAt = null) }))
    }
}
