package com.slidedify.feature.insights

import androidx.lifecycle.SavedStateHandle
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.suggest.FeatureSource
import com.slidedify.core.domain.suggest.InMemoryFeatureCache
import com.slidedify.core.domain.suggest.Suggester
import com.slidedify.core.domain.suggest.TrackFeatures
import com.slidedify.core.model.Track
import com.slidedify.core.testing.FakeProvider
import com.slidedify.core.testing.MemorySettings
import com.slidedify.core.testing.testPlaylist
import com.slidedify.core.testing.testTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val calm = TrackFeatures(tags = mapOf("chill" to 1f, "acoustic" to 0.5f), audioEnergy = 0.15f)
    private val loud = TrackFeatures(tags = mapOf("metal" to 1f, "workout" to 0.8f), audioEnergy = 0.92f)

    /** Songs whose title starts with "Loud" sound loud, the rest calm. */
    private val sound = object : FeatureSource {
        override val id = "test"
        override suspend fun features(track: Track) = if (track.title.startsWith("Loud")) loud else calm
    }
    private val effects = mutableListOf<LibraryEffect>()
    private val library = object : LibraryRepository {
        override suspend fun apply(effect: LibraryEffect) {
            effects += effect
        }
    }

    private fun song(n: Int, loud: Boolean = false) = testTrack(n, artist = "Artist$n").let { if (loud) it.copy(title = "Loud $n") else it }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `review flags the song that doesn't fit and moves it where it does`() = runTest(dispatcher) {
        val relax = testPlaylist("Relax")
        val gym = testPlaylist("Gym")
        val intruder = song(99, loud = true)
        val provider = FakeProvider(library = listOf(relax, gym))
        provider.songs["playlist:relax"] = (1..6).map { song(it) } + intruder
        provider.songs["playlist:gym"] = (10..15).map { song(it, loud = true) }

        val vm = ReviewViewModel(
            ProviderRegistry(setOf(provider)),
            MemorySettings(),
            Suggester(listOf(sound), InMemoryFeatureCache()),
            library,
            SavedStateHandle(mapOf(ReviewArgs.PLAYLIST_ID to "relax", ReviewArgs.TITLE to "Relax")),
        )

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(7, state.analysed)
        assertEquals(listOf(intruder.id), state.misfits.map { it.track.id })
        assertEquals("Gym", state.misfits.single().better?.name)

        vm.move(state.misfits.single())
        assertEquals<List<LibraryEffect>>(
            listOf(LibraryEffect.AddToPlaylists(intruder, setOf(gym.id)), LibraryEffect.RemoveFromPlaylists(intruder, setOf(relax.id))),
            effects,
        )
        assertEquals(ReviewAction.Moved("Gym"), vm.state.value.handled[intruder.id])
        assertEquals(emptyList(), vm.state.value.pending)
    }

    @Test
    fun `ideas group alike liked songs and create the playlist with them`() = runTest(dispatcher) {
        val provider = FakeProvider()
        val liked = (1..10).map { song(it) } + (20..22).map { song(it, loud = true) }
        provider.songs["liked"] = liked

        val vm = IdeasViewModel(ProviderRegistry(setOf(provider)), Suggester(listOf(sound), InMemoryFeatureCache()), library)

        val idea = vm.state.value.ideas.single()
        assertEquals("chill", idea.tag)
        assertEquals(10, idea.tracks.size)

        vm.create(0, "Chill tranquilo")
        assertEquals("Chill tranquilo", vm.state.value.created[0])
        assertEquals("Chill tranquilo", provider.library.single().name)
        assertEquals(10, effects.size)
    }
}
