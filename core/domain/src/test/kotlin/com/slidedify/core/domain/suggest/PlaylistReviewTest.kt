package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistReviewTest {
    private val provider = ProviderId("p")
    private fun playlist(name: String) = Playlist(PlaylistId(name.lowercase()), provider, name)
    private fun track(id: String, artist: String) =
        Track(TrackId(id), provider, id, listOf(Artist(artist, artist)), Album("a", "A", null, null), 200_000)

    private val calm = TrackFeatures(tags = mapOf("chill" to 1f, "acoustic" to 0.5f), audioEnergy = 0.15f)
    private val loud = TrackFeatures(tags = mapOf("metal" to 1f, "workout" to 0.8f), audioEnergy = 0.92f)

    @Test
    fun `a loud song in a calm playlist is flagged and sent where it fits`() {
        val relax = playlist("Relax")
        val songs = (1..6).map { track("calm$it", "Artist$it") } + track("intruder", "Metallica")
        val features = songs.associate { it.id to if (it.id.value == "intruder") loud else calm }
        val gymSongs = (1..5).map { track("gym$it", "Gym$it") }
        val gym = PlaylistProfile.build(playlist("Gym"), gymSongs, List(5) { loud })

        val misfits = PlaylistReview.review(relax, songs, features, listOf(gym))

        assertEquals(listOf("intruder"), misfits.map { it.track.id.value })
        assertEquals("Gym", misfits.single().better?.name)
        assertTrue(misfits.single().fit < 0.35f)
    }

    @Test
    fun `a coherent playlist has nothing to review`() {
        val relax = playlist("Relax")
        val songs = (1..6).map { track("calm$it", "Artist$it") }
        val misfits = PlaylistReview.review(relax, songs, songs.associate { it.id to calm }, emptyList())
        assertTrue(misfits.isEmpty())
    }

    @Test
    fun `without the song itself, its artist doesn't vouch for it`() {
        val p = PlaylistProfile(playlist("X")).withArtists(listOf(track("a", "Solo")))
        assertEquals(emptyMap(), p.without(track("a", "Solo"), null).artists)
    }
}
