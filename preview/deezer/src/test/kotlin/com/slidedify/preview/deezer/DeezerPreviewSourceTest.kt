package com.slidedify.preview.deezer

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeezerPreviewSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: DeezerPreviewSource

    private val track = Track(
        id = TrackId("1"),
        provider = ProviderId("spotify"),
        title = "Take On Me - 2015 Remaster",
        artists = listOf(Artist("a", "a-ha")),
        album = Album("al", "Hunting High and Low", null, 1985),
        durationMs = 225_000,
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = DeezerPreviewSource(OkHttpClient(), server.url("/"), Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `picks the closest duration and cleans the title`() = runTest {
        respond(
            """{"data":[
              {"title":"Take On Me (Live)","duration":260,"preview":"live.mp3","artist":{"name":"a-ha"}},
              {"title":"Take On Me","duration":226,"preview":"studio.mp3","artist":{"name":"a-ha"}},
              {"title":"Take On Me","duration":224,"preview":"","artist":{"name":"a-ha"}}
            ]}""",
        )

        assertEquals("studio.mp3", source.previewUrl(track))
        assertEquals("""artist:"a-ha" track:"Take On Me"""", server.takeRequest().url.queryParameter("q"))
    }

    @Test
    fun `falls back to a loose search when the strict one finds nothing`() = runTest {
        respond("""{"data":[]}""")
        respond("""{"data":[{"title":"Take On Me","duration":225,"preview":"loose.mp3","artist":{"name":"a-ha"}}]}""")

        assertEquals("loose.mp3", source.previewUrl(track))
        server.takeRequest()
        assertEquals("a-ha Take On Me", server.takeRequest().url.queryParameter("q"))
    }

    @Test
    fun `no plausible match or a server error gives null`() = runTest {
        respond("""{"data":[{"title":"Other","duration":100,"preview":"x.mp3"}]}""")
        respond("""{"data":[]}""")
        assertNull(source.previewUrl(track))

        respond("oops", code = 500)
        respond("oops", code = 500)
        assertNull(source.previewUrl(track))
    }

    @Test
    fun `features read tempo and loudness of the matched song`() = runTest {
        val features = DeezerFeatureSource(OkHttpClient(), server.url("/"), Dispatchers.Unconfined)
        respond("""{"data":[{"id":42,"title":"Take On Me","duration":225,"preview":"https://p/1.mp3","artist":{"name":"a-ha"}}]}""")
        respond("""{"id":42,"title":"Take On Me","duration":225,"bpm":169.4,"gain":-9.0}""")
        val result = features.features(track)!!
        assertEquals(169.4f, result.bpm)
        assertEquals(0.5f, result.loudness!!, 0.001f)
        assertEquals("/track/42", server.takeRequest().let { server.takeRequest() }.url.encodedPath)
    }

    @Test
    fun `unknown tempo is left empty`() = runTest {
        val features = DeezerFeatureSource(OkHttpClient(), server.url("/"), Dispatchers.Unconfined)
        respond("""{"data":[{"id":7,"title":"Take On Me","duration":225,"preview":"https://p/1.mp3","artist":{"name":"a-ha"}}]}""")
        respond("""{"id":7,"bpm":0,"gain":0}""")
        val result = features.features(track)!!
        assertNull(result.bpm)
        assertNull(result.loudness)
    }

    @Test
    fun `the ISRC gives the exact recording`() = runTest {
        respond("""{"id":9,"title":"Take On Me","duration":225,"preview":"exact.mp3","artist":{"name":"a-ha"}}""")
        assertEquals("exact.mp3", source.previewUrl(track.copy(isrc = "NOA028500001")))
        assertEquals("/track/isrc:NOA028500001", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an unknown ISRC falls back to the search`() = runTest {
        respond("""{"error":{"type":"DataException","message":"no data","code":800}}""")
        respond("""{"data":[{"title":"Take On Me","duration":225,"preview":"found.mp3","artist":{"name":"a-ha"}}]}""")
        assertEquals("found.mp3", source.previewUrl(track.copy(isrc = "XX0000000000")))
    }

    @Test
    fun `other artists and extra versions are not taken`() = runTest {
        respond(
            """{"data":[
              {"title":"Take On Me","duration":225,"preview":"cover.mp3","artist":{"name":"Weezer"}},
              {"title":"Take On Me - Remix","duration":225,"preview":"remix.mp3","artist":{"name":"a-ha"}},
              {"title":"Take On Me","duration":229,"preview":"original.mp3","artist":{"name":"a-ha"}}
            ]}""",
        )
        assertEquals("original.mp3", source.previewUrl(track))
    }
}
