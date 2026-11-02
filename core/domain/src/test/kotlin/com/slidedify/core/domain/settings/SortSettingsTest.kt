package com.slidedify.core.domain.settings

import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortSettingsTest {
    private val provider = ProviderId("spotify")
    private val playlists = listOf("a", "b", "c").map { Playlist(PlaylistId(it), provider, it) }
    private fun id(s: String) = PlaylistId(s)

    @Test
    fun `default map has only the first playlist`() {
        assertEquals(GestureMap.of(id("a")), SortSettings().gestureMapFor(provider, playlists))
        assertEquals(GestureMap.Empty, SortSettings().gestureMapFor(provider, emptyList()))
    }

    @Test
    fun `saved combos are kept and emptied destinations disappear`() {
        val saved = GestureMap(listOf(setOf(id("a"), id("gone")), setOf(id("gone")), setOf(id("c"))))
        val map = SortSettings().withGestureMap(provider, saved).gestureMapFor(provider, playlists)
        assertEquals(GestureMap(listOf(setOf(id("a")), setOf(id("c")))), map)
    }

    @Test
    fun `hold timeout is clamped`() {
        assertEquals(200L, SortSettings().withHoldTimeout(50).holdTimeoutMs)
        assertEquals(600L, SortSettings().withHoldTimeout(5_000).holdTimeoutMs)
        assertEquals(350L, SortSettings().withHoldTimeout(350).holdTimeoutMs)
    }

    @Test
    fun `gesture map editing`() {
        val map = GestureMap.of(id("a")).add(setOf(id("b"))).add(setOf(id("c"), id("a")))
        assertEquals(3, map.size)
        assertEquals(setOf(id("b"), id("c"), id("a")), map.playlistsFor(setOf(Slot(2), Slot(1))))
        assertEquals(GestureMap.of(listOf(id("a"), id("b"))), map.remove(Slot(2)))
        assertEquals(GestureMap.of(listOf(id("a"), id("c"))), map.replace(Slot(1), setOf(id("c"))).remove(Slot(2)))
        assertEquals(GestureMap.of(listOf(id("a"), id("b"))), map.replace(Slot(2), emptySet()))
        assertTrue(map.contains(id("c")))
    }

    @Test
    fun `at most eight destinations`() {
        val full = (1..8).fold(GestureMap.Empty) { m, n -> m.add(setOf(id("p$n"))) }
        assertTrue(full.isFull)
        assertEquals(full, full.add(setOf(id("extra"))))
        assertFailsWith<IllegalArgumentException> { GestureMap((1..9).map { setOf(id("p$it")) }) }
    }

    @Test
    fun `recent keeps newest first, one entry per source, and remembers the total`() {
        val liked = RecentSource(provider, TrackSource.LikedSongs, "Me gusta", total = 40, openedAt = 1)
        val gym = RecentSource(provider, TrackSource.FromPlaylist(id("gym")), "Gym", openedAt = 2)
        val settings = SortSettings()
            .withRecent(liked)
            .withRecent(gym)
            .withRecent(liked.copy(total = null, openedAt = 3))

        assertEquals(listOf("Me gusta", "Gym"), settings.recent.map { it.title })
        assertEquals(40, settings.recent.first().total)
        assertEquals(3, settings.recent.first().openedAt)
    }

    @Test
    fun `recent is capped`() {
        val settings = (1..20).fold(SortSettings()) { acc, n ->
            acc.withRecent(RecentSource(provider, TrackSource.FromPlaylist(id("p$n")), "P$n", openedAt = n.toLong()))
        }
        assertEquals(SortSettings.MAX_RECENT, settings.recent.size)
        assertEquals("P20", settings.recent.first().title)
    }
}
