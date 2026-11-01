package com.slidedify.provider.youtube.api

import com.slidedify.core.domain.repository.TrackQuery
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.TrackId
import com.slidedify.core.oauth.AuthorizedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: YouTubeApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = YouTubeApi(AuthorizedHttp(OkHttpClient(), { "token" }, dispatcher = Dispatchers.Unconfined), server.url("/youtube/v3/"))
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `artist and title come from Topic channels and dashed titles`() {
        assertEquals("a-ha" to "Take On Me", splitArtistTitle("Take On Me", "a-ha - Topic"))
        assertEquals("Queen" to "Bohemian Rhapsody (Official Video)", splitArtistTitle("Queen - Bohemian Rhapsody (Official Video)", "Queen Official"))
        assertEquals("Muse" to "Uprising", splitArtistTitle("Uprising", "MuseVEVO"))
    }

    @Test
    fun `playlist pages follow the page token and skip deleted videos`() = runTest {
        respond(
            """{"items":[
              {"id":"PLI1","snippet":{"title":"Take On Me","videoOwnerChannelTitle":"a-ha - Topic","thumbnails":{"high":{"url":"img"}}},"contentDetails":{"videoId":"v1"}},
              {"id":"PLI2","snippet":{"title":"Deleted video"},"contentDetails":{"videoId":"v2"}}
            ],"nextPageToken":"p2"}""",
        )
        respond("""{"items":[{"id":"PLI3","snippet":{"title":"Queen - Bohemian Rhapsody","channelTitle":"x"},"contentDetails":{"videoId":"v3"}}]}""")

        val pages = YouTubeTrackRepository(api).pages(TrackSource.FromPlaylist(PlaylistId("PL"))).toList()

        assertEquals(listOf(listOf("v1"), listOf("v3")), pages.map { page -> page.map { it.id.value } })
        assertEquals("a-ha", pages[0][0].artists.single().name)
        assertEquals("img", pages[0][0].album.imageUrl)
        server.takeRequest()
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun `search results carry the video id inside an object`() = runTest {
        respond("""{"items":[{"id":{"kind":"youtube#video","videoId":"vid9"},"snippet":{"title":"a-ha - Take On Me","channelTitle":"a-ha"}}]}""")

        val found = YouTubeTrackRepository(api).search(TrackQuery("Take On Me", "a-ha"))

        assertEquals("vid9", found.single().id.value)
        val request = server.takeRequest()
        assertEquals("10", request.url.queryParameter("videoCategoryId"))
        assertEquals("a-ha Take On Me", request.url.queryParameter("q"))
    }

    @Test
    fun `remove looks up the playlist item id first`() = runTest {
        respond("""{"items":[{"id":"ITEM-7","contentDetails":{"videoId":"v1"}},{"id":"ITEM-8","contentDetails":{"videoId":"other"}}]}""")
        respond("", 204)

        YouTubeLibraryWriter(api).removeFromPlaylist(PlaylistId("PL"), listOf(TrackId("v1")))

        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("ITEM-7", delete.url.queryParameter("id"))
    }

    @Test
    fun `adding inserts one item per video`() = runTest {
        respond("{}")
        respond("{}")

        YouTubeLibraryWriter(api).addToPlaylist(PlaylistId("PL"), listOf(TrackId("a"), TrackId("b")))

        val first = server.takeRequest().body!!.utf8()
        assertTrue(first.contains(""""videoId":"a"""") && first.contains(""""playlistId":"PL""""))
        assertTrue(server.takeRequest().body!!.utf8().contains(""""videoId":"b""""))
    }
}
