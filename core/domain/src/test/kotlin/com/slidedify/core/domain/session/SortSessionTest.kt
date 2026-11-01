package com.slidedify.core.domain.session

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.PlaylistId
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
        uri = "spotify:track:t$n",
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
}
