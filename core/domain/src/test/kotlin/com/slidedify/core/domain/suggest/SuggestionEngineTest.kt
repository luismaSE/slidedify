package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionEngineTest {
    private val provider = ProviderId("demo")

    private fun playlist(name: String) = Playlist(PlaylistId(name.lowercase()), provider, name)

    private fun track(title: String, artist: String = "Someone") = Track(
        id = TrackId(title),
        provider = provider,
        title = title,
        artists = listOf(Artist(artist.lowercase(), artist)),
        album = Album("a", "Album", null, null),
        durationMs = 200_000,
    )

    private val gym = PlaylistProfile(playlist("Gym"))
    private val punchy = PlaylistProfile(playlist("Punchy"))
    private val relax = PlaylistProfile(playlist("Relax"))

    @Test
    fun `a calm song goes to Relax, from the playlist names alone`() {
        val calm = TrackFeatures(tags = mapOf("chill" to 1f, "acoustic" to 0.6f), audioEnergy = 0.2f)
        val suggestion = assertNotNull(SuggestionEngine.suggest(track("Sunday"), calm, listOf(listOf(gym), listOf(punchy), listOf(relax))))
        assertEquals(2, suggestion.slotIndex)
        assertTrue(suggestion.reasons.isNotEmpty())
    }

    @Test
    fun `an intense song goes to Gym or Punchy but not Relax`() {
        val intense = TrackFeatures(tags = mapOf("workout" to 1f, "metal" to 0.8f), audioEnergy = 0.9f)
        val suggestion = assertNotNull(SuggestionEngine.suggest(track("Loud"), intense, listOf(listOf(relax), listOf(gym))))
        assertEquals(1, suggestion.slotIndex)
    }

    @Test
    fun `an artist already in a playlist is a strong hint`() {
        val withArtist = PlaylistProfile(playlist("Favoritas")).withArtists(List(4) { track("Old $it", artist = "Soda") })
        val other = PlaylistProfile(playlist("Otras")).withArtists(listOf(track("x", artist = "Nobody")))
        val suggestion = assertNotNull(SuggestionEngine.suggest(track("New", artist = "Soda"), null, listOf(listOf(other), listOf(withArtist))))
        assertEquals(1, suggestion.slotIndex)
        assertTrue(suggestion.reasons.first().contains("Soda"))
    }

    @Test
    fun `no suggestion when nothing is known`() {
        val a = PlaylistProfile(playlist("Mis temas"))
        val b = PlaylistProfile(playlist("Varios"))
        assertNull(SuggestionEngine.suggest(track("Song"), null, listOf(listOf(a), listOf(b))))
    }

    @Test
    fun `no suggestion when two options are equally good`() {
        val calm = TrackFeatures(tags = mapOf("chill" to 1f), audioEnergy = 0.2f)
        val chill = PlaylistProfile(playlist("Chill"))
        assertNull(SuggestionEngine.suggest(track("Song"), calm, listOf(listOf(relax), listOf(chill))))
    }

    @Test
    fun `profiles learn the sound of their songs`() {
        val calmSongs = List(5) { TrackFeatures(audioEnergy = 0.15f + it * 0.02f, tags = mapOf("piano" to 1f)) }
        val loudSongs = List(5) { TrackFeatures(audioEnergy = 0.85f, tags = mapOf("rock" to 1f)) }
        val a = PlaylistProfile.build(playlist("Lista A"), emptyList(), calmSongs)
        val b = PlaylistProfile.build(playlist("Lista B"), emptyList(), loudSongs)
        val song = TrackFeatures(audioEnergy = 0.2f, tags = mapOf("piano" to 0.9f))
        assertEquals(0, SuggestionEngine.suggest(track("Song"), song, listOf(listOf(a), listOf(b)))?.slotIndex)
    }

    @Test
    fun `playlist names in Spanish and English carry energy`() {
        assertTrue(NamePriors.of("GYM 💪")!!.energy!! > 0.8f)
        assertTrue(NamePriors.of("Lo-fi para estudiar")!!.energy!! < 0.3f)
        assertTrue(NamePriors.of("Música para dormir")!!.energy!! < 0.2f)
        assertTrue(NamePriors.of("Chillvibes")!!.energy!! < 0.3f)
        assertNull(NamePriors.of("Mis temas"))
    }

    @Test
    fun `tags are cleaned and synonyms folded`() {
        assertEquals("hip-hop", TagVocabulary.clean("Hip Hop"))
        assertNull(TagVocabulary.clean("seen live"))
        assertNull(TagVocabulary.clean("2011"))
        assertEquals("80s", TagVocabulary.clean("80s"))
    }

    @Test
    fun `each source runs once per song and results are cached`() = runTest {
        var calls = 0
        val source = object : FeatureSource {
            override val id = "test"
            override suspend fun features(track: Track): TrackFeatures {
                calls++
                return TrackFeatures(bpm = 100f)
            }
        }
        val suggester = Suggester(listOf(source), InMemoryFeatureCache())
        repeat(3) { assertEquals(100f, suggester.features(track("Song")).bpm) }
        assertEquals(1, calls)
    }

    @Test
    fun `a failing source is retried later`() = runTest {
        var fail = true
        val source = object : FeatureSource {
            override val id = "flaky"
            override suspend fun features(track: Track): TrackFeatures? = if (fail) null else TrackFeatures(bpm = 90f)
        }
        val suggester = Suggester(listOf(source), InMemoryFeatureCache())
        assertNull(suggester.features(track("Song")).bpm)
        fail = false
        assertEquals(90f, suggester.features(track("Song")).bpm)
    }

    @Test
    fun `sample spreads over the playlist, newest first`() {
        assertEquals(listOf(9, 7, 5, 3, 1), Suggester.spreadSample((0..9).toList(), 5))
        assertEquals(listOf(2, 1, 0), Suggester.spreadSample(listOf(0, 1, 2), 5))
    }
}
