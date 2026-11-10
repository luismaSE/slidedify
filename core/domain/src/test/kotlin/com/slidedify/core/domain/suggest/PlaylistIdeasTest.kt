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

class PlaylistIdeasTest {
    private val provider = ProviderId("p")
    private fun track(id: String) = Track(TrackId(id), provider, id, listOf(Artist("a", "A")), Album("a", "A", null, null), 200_000)

    private val folk = TrackFeatures(tags = mapOf("indie folk" to 1f, "acoustic" to 0.6f), audioEnergy = 0.2f)
    private val rock = TrackFeatures(tags = mapOf("rock" to 1f), audioEnergy = 0.85f)
    private val pop = TrackFeatures(tags = mapOf("pop" to 1f), audioEnergy = 0.5f)

    private val library = (1..12).map { track("folk$it") } + (1..9).map { track("rock$it") } + (1..3).map { track("pop$it") }
    private val features = library.associate { t ->
        t.id to when {
            t.id.value.startsWith("folk") -> folk
            t.id.value.startsWith("rock") -> rock
            else -> pop
        }
    }

    @Test
    fun `big groups of alike songs become ideas, biggest first`() {
        val ideas = PlaylistIdeas.propose(library, features, existing = emptyList())
        assertEquals(listOf("indie folk" to EnergyBand.Calm, "rock" to EnergyBand.Energetic), ideas.map { it.tag to it.energy })
        assertEquals(12, ideas.first().tracks.size)
    }

    @Test
    fun `styles the user already has a playlist for are skipped`() {
        val ideas = PlaylistIdeas.propose(library, features, existing = listOf(Playlist(PlaylistId("x"), provider, "Rock nacional")))
        assertTrue(ideas.none { it.tag == "rock" })
    }

    @Test
    fun `small groups and songs without data are ignored`() {
        val ideas = PlaylistIdeas.propose(library + track("unknown"), features, emptyList())
        assertTrue(ideas.none { it.tag == "pop" })
        assertTrue(ideas.flatMap { it.tracks }.none { it.id.value == "unknown" })
    }
}
