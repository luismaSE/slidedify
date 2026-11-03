package com.slidedify.suggest.lastfm

import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LastFmTagSourceTest {
    private val server = MockWebServer()
    private lateinit var source: LastFmTagSource

    private val track = Track(
        TrackId("1"), ProviderId("demo"), "Holocene - Remastered", listOf(Artist("bi", "Bon Iver")),
        Album("a", "Bon Iver", null, 2011), 336_000,
    )

    @BeforeEach
    fun setUp() {
        server.start()
        source = LastFmTagSource(OkHttpClient(), "key", server.url("/2.0/"), kotlinx.coroutines.Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `song tags are cleaned and weighted by count`() = runTest {
        respond("""{"toptags":{"tag":[{"name":"Indie Folk","count":100},{"name":"chill","count":60},{"name":"seen live","count":50},{"name":"acoustic","count":30},{"name":"rare","count":1}]}}""")
        val tags = source.features(track)!!.tags
        assertEquals(mapOf("indie folk" to 1f, "chill" to 0.6f, "acoustic" to 0.3f), tags)
        val request = server.takeRequest()
        assertEquals("track.gettoptags", request.url.queryParameter("method"))
        assertEquals("Holocene", request.url.queryParameter("track"))
    }

    @Test
    fun `few song tags are topped up with the artist's, weighted less`() = runTest {
        respond("""{"toptags":{"tag":[{"name":"chill","count":100}]}}""")
        respond("""{"toptags":{"tag":[{"name":"folk","count":100},{"name":"chill","count":50}]}}""")
        val tags = source.features(track)!!.tags
        assertEquals(1f, tags["chill"])
        assertEquals(0.6f, tags["folk"])
    }

    @Test
    fun `unknown song is empty, rate limit is a retry`() = runTest {
        respond("""{"error":6,"message":"Track not found"}""")
        respond("""{"toptags":{"tag":[]}}""")
        assertTrue(source.features(track)!!.tags.isEmpty())
        respond("""{"error":29,"message":"Rate limit exceeded"}""")
        assertNull(source.features(track))
    }
}
