package com.slidedify.core.domain.session

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SortSessionTest {

    private fun track(n: Int) = Track(
        id = TrackId("t$n"),
        provider = ProviderId("test"),
        title = "Song $n",
        artists = listOf(Artist("a", "Artist")),
        album = Album("al", "Album", imageUrl = null, releaseYear = 2020),
        durationMs = 180_000,
    )

    private val gym = PlaylistId("gym")
    private val focus = PlaylistId("focus")
    private val session = SortSession(listOf(track(1), track(2), track(3)))

    @Test
    fun `adding to playlists advances and emits one effect`() {
        val update = session.decide(Decision.AddTo(setOf(gym, focus)))
        assertEquals(track(2), update.session.current)
        assertEquals(listOf(LibraryEffect.AddToPlaylists(track(1), setOf(gym, focus))), update.effects)
    }

    @Test
    fun `skip advances without effects`() {
        val update = session.decide(Decision.Skip)
        assertEquals(track(2), update.session.current)
        assertTrue(update.effects.isEmpty())
    }

    @Test
    fun `undo after add goes back and removes from the playlists`() {
        val added = session.decide(Decision.AddTo(setOf(gym))).session
        val undone = added.undo()
        assertEquals(track(1), undone.session.current)
        assertEquals(listOf(LibraryEffect.RemoveFromPlaylists(track(1), setOf(gym))), undone.effects)
        assertFalse(undone.session.canUndo)
    }

    @Test
    fun `like toggles without advancing and can be undone`() {
        val liked = session.toggleLike()
        assertEquals(track(1), liked.session.current)
        assertTrue(liked.session.isLiked(track(1)))
        assertEquals(listOf(LibraryEffect.SetLiked(track(1), true)), liked.effects)

        val undone = liked.session.undo()
        assertFalse(undone.session.isLiked(track(1)))
        assertEquals(listOf(LibraryEffect.SetLiked(track(1), false)), undone.effects)
    }

    @Test
    fun `finishing the queue leaves no current track`() {
        val done = (1..3).fold(session) { s, _ -> s.decide(Decision.Skip).session }
        assertTrue(done.isFinished)
        assertNull(done.current)
        assertTrue(done.decide(Decision.Skip).effects.isEmpty())
    }

    @Test
    fun `appending pages keeps the position and ignores duplicates`() {
        val advanced = session.decide(Decision.Skip).session
        val more = advanced.append(listOf(track(3), track(4), track(5)))
        assertEquals(5, more.tracks.size)
        assertEquals(track(2), more.current)
        assertEquals(listOf(track(3), track(4), track(5)), more.upcoming)
    }

    @Test
    fun `a finished session continues when a new page arrives`() {
        val done = (1..3).fold(session) { s, _ -> s.decide(Decision.Skip).session }
        assertTrue(done.isFinished)
        assertEquals(track(4), done.append(listOf(track(4))).current)
    }

    @Test
    fun `reviewed tracks are skipped when resuming and when advancing`() {
        val resumed = SortSession(listOf(track(1), track(2), track(3)), sorted = setOf(TrackId("t1"), TrackId("t3")))
            .startAtFirstPending()
        assertEquals(track(2), resumed.current)
        assertEquals(2, resumed.sortedCount)

        val next = resumed.decide(Decision.Skip).session
        assertTrue(next.isFinished) // t3 was already reviewed
        assertEquals(3, next.sortedCount)
    }

    @Test
    fun `after jumping ahead, the earlier pending tracks are still sorted`() {
        val jumped = session.jumpTo(2)
        val next = jumped.decide(Decision.Skip).session
        assertEquals(track(1), next.current)
        assertFalse(next.isFinished)
    }

    @Test
    fun `reordering keeps the current track and the next ones follow the new order`() {
        val reversed = session.decide(Decision.Skip).session // at t2
            .reordered(compareByDescending { it.id.value })
        assertEquals(listOf(track(3), track(2), track(1)), reversed.tracks)
        assertEquals(track(2), reversed.current)
        // t1 was reviewed; t3 is before the cursor now, so it comes back after t2.
        assertEquals(track(3), reversed.decide(Decision.Skip).session.current)
    }

    @Test
    fun `undo finds the track after a reorder`() {
        val decided = session.decide(Decision.Skip).session
        val undone = decided.reordered(compareByDescending { it.id.value }).undo().session
        assertEquals(track(1), undone.current)
    }

    @Test
    fun `jumping back to a reviewed track and deciding again keeps it reviewed after undo`() {
        val base = SortSession(listOf(track(1), track(2)), sorted = setOf(TrackId("t1"))).startAtFirstPending()
        val jumped = base.jumpTo(0)
        assertEquals(track(1), jumped.current)

        val decided = jumped.decide(Decision.AddTo(setOf(gym))).session
        assertEquals(track(2), decided.current)

        val undone = decided.undo().session
        assertEquals(track(1), undone.current)
        assertTrue(undone.isSorted(track(1))) // it had been reviewed before the jump
    }

    @Test
    fun `undo returns to where the track was even after skipping reviewed ones`() {
        val session = SortSession(listOf(track(1), track(2), track(3)), sorted = setOf(TrackId("t2")))
        val decided = session.decide(Decision.Skip).session
        assertEquals(track(3), decided.current)
        val undone = decided.undo().session
        assertEquals(track(1), undone.current)
        assertFalse(undone.isSorted(track(1)))
    }
}
