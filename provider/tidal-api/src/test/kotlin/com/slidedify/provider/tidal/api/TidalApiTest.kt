package com.slidedify.provider.tidal.api

import com.slidedify.core.domain.repository.TrackQuery
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.TrackId
import com.slidedify.core.oauth.AuthorizedHttp
import com.slidedify.core.oauth.InMemoryTokenStore
import com.slidedify.core.oauth.OAuthClient
import com.slidedify.core.oauth.OAuthTokens
import com.slidedify.core.oauth.RefreshingTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TidalApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TidalApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        val store = InMemoryTokenStore()
        runBlocking { store.save(OAuthTokens("token", "refresh", 3600, userId = "u1", obtainedAtMs = System.currentTimeMillis())) }
        val config = TidalEndpoints.config("client", "slidedify://tidal-callback")
        val tokens = RefreshingTokens(store, OAuthClient(config, client, dispatcher = Dispatchers.Unconfined))
        api = TidalApi(AuthorizedHttp(client, tokens, "application/vnd.api+json", Dispatchers.Unconfined), tokens, server.url("/v2/"))
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val page = """{
      "data":[{"id":"111","type":"tracks","meta":{"itemId":"it-1"}}],
      "included":[
        {"id":"111","type":"tracks","attributes":{"title":"Take On Me","duration":"PT3M45S","isrc":"NOA1"},
         "relationships":{"artists":{"data":[{"id":"a1","type":"artists"}]},"albums":{"data":[{"id":"al1","type":"albums"}]}}},
        {"id":"a1","type":"artists","attributes":{"name":"a-ha"}},
        {"id":"al1","type":"albums","attributes":{"title":"Hunting High and Low","releaseDate":"1985-06-01"}}
      ],
      "links":{"next":null}
    }"""

    @Test
    fun `playlist items are resolved from the included resources`() = runTest {
        respond(page)

        val track = TidalTrackRepository(api).pages(TrackSource.FromPlaylist(PlaylistId("pl"))).first().single()

        assertEquals(TrackId("111"), track.id)
        assertEquals("Take On Me", track.title)
        assertEquals("a-ha", track.artists.single().name)
        assertEquals("Hunting High and Low", track.album.name)
        assertEquals(1985, track.album.releaseYear)
        assertEquals(225_000L, track.durationMs)
        assertEquals("NOA1", track.isrc)
        val request = server.takeRequest()
        assertEquals("/v2/playlists/pl/relationships/items", request.url.encodedPath)
        assertEquals("Bearer token", request.headers["Authorization"])
    }

    @Test
    fun `search uses the isrc filter first`() = runTest {
        respond("""{"data":[{"id":"9","type":"tracks","attributes":{"title":"Take On Me","duration":"PT3M45S","isrc":"NOA1"}}]}""")

        val found = TidalTrackRepository(api).search(TrackQuery("Take On Me", "a-ha", 225_000, isrc = "NOA1"))

        assertEquals(listOf("9"), found.map { it.id.value })
        assertEquals("NOA1", server.takeRequest().url.queryParameter("filter[isrc]"))
    }

    @Test
    fun `text search encodes the query in the path`() = runTest {
        respond(page)

        TidalTrackRepository(api).search(TrackQuery("Take On Me", "a-ha"))

        assertEquals("/v2/searchResults/a-ha%20Take%20On%20Me/relationships/tracks", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `adding and removing send JSON API references`() = runTest {
        respond("", 201)
        TidalLibraryWriter(api).addToPlaylist(PlaylistId("pl"), listOf(TrackId("111")))
        val add = server.takeRequest()
        assertEquals("POST", add.method)
        assertEquals("""{"data":[{"id":"111","type":"tracks"}]}""", add.body!!.utf8())

        respond(page) // read the playlist to find the itemId
        respond("", 204)
        TidalLibraryWriter(api).removeFromPlaylist(PlaylistId("pl"), listOf(TrackId("111")))
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertTrue(delete.body!!.utf8().contains(""""itemId":"it-1""""))
    }

    @Test
    fun `iso durations`() {
        assertEquals(225_000L, isoDurationMs("PT3M45S"))
        assertEquals(0L, isoDurationMs("nonsense"))
    }
}
