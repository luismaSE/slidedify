package com.slidedify.core.domain.transfer

import com.slidedify.core.domain.outbox.LibraryWriter
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderAuth
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackQuery
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun track(provider: String, id: String, title: String, artist: String, seconds: Int, isrc: String? = null) = Track(
    id = TrackId(id),
    provider = ProviderId(provider),
    title = title,
    artists = listOf(Artist(artist, artist)),
    album = Album("a", "Album", null, null),
    durationMs = seconds * 1000L,
    isrc = isrc,
)

class TrackMatcherTest {
    @Test
    fun `same recording despite noise in titles and artist names`() {
        val spotify = track("spotify", "1", "Take On Me - 2015 Remaster", "a-ha", 225)
        val youtube = track("youtube", "x", "Take On Me (Official Video)", "a-ha - Topic", 226)
        assertTrue(TrackMatcher.score(spotify, youtube) >= TrackMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun `isrc decides when both sides have it`() {
        val a = track("spotify", "1", "Song", "Band", 200, isrc = "USABC1234567")
        assertEquals(1.0, TrackMatcher.score(a, track("tidal", "9", "Other name", "Other", 100, isrc = "usabc1234567")))
        assertEquals(0.0, TrackMatcher.score(a, track("tidal", "9", "Song", "Band", 200, isrc = "GBXYZ7654321")))
    }

    @Test
    fun `different songs by the same artist don't match`() {
        val a = track("spotify", "1", "Billie Jean", "Michael Jackson", 294)
        val b = track("tidal", "2", "Beat It", "Michael Jackson", 258)
        assertEquals(null, TrackMatcher.best(a, listOf(b)))
    }

    @Test
    fun `accents and featuring are ignored`() {
        assertEquals("corazon", TrackMatcher.normalizeTitle("Corazón (feat. Nego do Borel)"))
        assertEquals("the weeknd", TrackMatcher.normalizeArtist("The Weeknd - Topic"))
    }
}

class PlaylistTransferTest {
    /** Minimal in-memory service: a catalog to search, playlists and liked songs. */
    private class MemoryService(id: String, override val displayName: String, catalog: List<Track>, canSearch: Boolean = true) : MusicProvider {
        override val id = ProviderId(id)
        override val capabilities = ProviderCapabilities(canSearch = canSearch)
        val playlistTracks = mutableMapOf<PlaylistId, MutableList<Track>>()
        val created = mutableListOf<Playlist>()
        override val auth = object : ProviderAuth {
            override val state = MutableStateFlow<AuthState>(AuthState.SignedIn("me"))

            override suspend fun signOut() = Unit
        }
        override val tracks = object : TrackRepository {
            override suspend fun tracks(source: TrackSource) = when (source) {
                is TrackSource.FromPlaylist -> playlistTracks[source.id].orEmpty().toList()
                else -> catalog.take(2)
            }

            override suspend fun search(query: TrackQuery) =
                catalog.filter { TrackMatcher.normalizeTitle(it.title).contains(query.title.lowercase()) }
        }
        override val playlists = object : PlaylistRepository {
            override suspend fun writablePlaylists() = created + playlistTracks.keys.map { Playlist(it, this@MemoryService.id, it.value) }

            override suspend fun createPlaylist(name: String) =
                Playlist(PlaylistId("new-$name"), this@MemoryService.id, name).also { created += it }
        }
        override val writer: LibraryWriter get() = error("transfers write through the outbox")
        override val player: Player get() = error("unused")
    }

    private class RecordingLibrary : LibraryRepository {
        val effects = mutableListOf<LibraryEffect>()

        override suspend fun apply(effect: LibraryEffect) {
            effects += effect
        }
    }

    private val sTakeOnMe = track("spotify", "s1", "Take On Me - 2015 Remaster", "a-ha", 225, isrc = "NOA1")
    private val sBillie = track("spotify", "s2", "Billie Jean", "Michael Jackson", 294)
    private val sObscure = track("spotify", "s3", "Unreleased Demo", "Nobody", 100)
    private val tTakeOnMe = track("tidal", "t1", "Take On Me", "a-ha", 225, isrc = "NOA1")
    private val tBillie = track("tidal", "t2", "Billie Jean", "Michael Jackson", 294)
    private val tExtra = track("tidal", "t3", "Smells Like Teen Spirit", "Nirvana", 301)

    private val spotify = MemoryService("spotify", "Spotify", catalog = listOf(sTakeOnMe, sBillie, sObscure, track("spotify", "s4", "Smells Like Teen Spirit", "Nirvana", 301)))
        .apply { playlistTracks[PlaylistId("mix")] = mutableListOf(sTakeOnMe, sBillie, sObscure) }
    private val tidal = MemoryService("tidal", "Tidal", catalog = listOf(tTakeOnMe, tBillie, tExtra))
    private val library = RecordingLibrary()
    private val transfer = PlaylistTransfer(ProviderRegistry(setOf(spotify, tidal)), library)

    private suspend fun runTransfer(request: TransferRequest) = transfer.run(request).toList()

    @Test
    fun `copy into a new playlist adds what was found and reports the rest`() = runTest {
        val steps = runTransfer(
            TransferRequest(
                from = spotify.id, source = TrackSource.FromPlaylist(PlaylistId("mix")),
                to = tidal.id, target = TransferTarget.NewPlaylist("Mix"), mode = TransferMode.Copy,
            ),
        )
        val report = assertIs<TransferProgress.Done>(steps.last()).report
        assertEquals("Mix", report.target.name)
        assertEquals(2, report.addedToTarget)
        assertEquals(listOf(sObscure), report.notFound)
        assertEquals(
            listOf<LibraryEffect>(
                LibraryEffect.AddToPlaylists(tTakeOnMe, setOf(PlaylistId("new-Mix"))),
                LibraryEffect.AddToPlaylists(tBillie, setOf(PlaylistId("new-Mix"))),
            ),
            library.effects,
        )
        assertTrue(steps.any { it is TransferProgress.Matching && it.done == 3 && it.total == 3 })
    }

    @Test
    fun `move removes the found tracks from the source playlist`() = runTest {
        runTransfer(
            TransferRequest(spotify.id, TrackSource.FromPlaylist(PlaylistId("mix")), tidal.id, TransferTarget.NewPlaylist("Mix"), TransferMode.Move),
        )
        val removed = library.effects.filterIsInstance<LibraryEffect.RemoveFromPlaylists>().map { it.track }
        assertEquals(listOf(sTakeOnMe, sBillie), removed) // the unmatched one stays
    }

    @Test
    fun `sync adds the missing tracks in both directions and skips what is already there`() = runTest {
        tidal.playlistTracks[PlaylistId("mix-t")] = mutableListOf(tTakeOnMe, tExtra)

        val report = assertIs<TransferProgress.Done>(
            runTransfer(
                TransferRequest(spotify.id, TrackSource.FromPlaylist(PlaylistId("mix")), tidal.id, TransferTarget.Existing(PlaylistId("mix-t")), TransferMode.Sync),
            ).last(),
        ).report

        assertEquals(1, report.alreadyInTarget) // Take On Me (same ISRC)
        assertEquals(1, report.addedToTarget) // Billie Jean
        assertEquals(1, report.addedToSource) // Smells Like Teen Spirit into the Spotify playlist
        assertTrue(LibraryEffect.AddToPlaylists(tBillie, setOf(PlaylistId("mix-t"))) in library.effects)
        assertTrue(library.effects.any { it is LibraryEffect.AddToPlaylists && it.track.provider == spotify.id && it.track.title == "Smells Like Teen Spirit" })
    }

    @Test
    fun `a target that can't search fails with a clear message`() = runTest {
        val blind = MemoryService("blind", "Ciego", emptyList(), canSearch = false)
        val result = PlaylistTransfer(ProviderRegistry(setOf(spotify, blind)), library).run(
            TransferRequest(spotify.id, TrackSource.LikedSongs, blind.id, TransferTarget.NewPlaylist("x"), TransferMode.Copy),
        ).toList().last()
        assertEquals(TransferProgress.Failed("Ciego todavía no permite buscar canciones"), result)
    }
}
