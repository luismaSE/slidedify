package com.slidedify.lyrics.lrclib

import com.slidedify.core.domain.lyrics.LyricLine
import com.slidedify.core.domain.lyrics.Lyrics
import com.slidedify.core.domain.lyrics.LyricsRepository
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
import kotlin.test.assertTrue

class LrcParserTest {
    @Test
    fun `parses timestamps with different precisions and sorts them`() {
        val lyrics = LrcParser.parseSynced(
            """
            [ar: Someone]
            [00:12.50] Second
            [00:01.00]First
            [01:02.123] Third
            not a lyric line
            """.trimIndent(),
        )
        assertEquals(
            listOf(LyricLine(1_000, "First"), LyricLine(12_500, "Second"), LyricLine(62_123, "Third")),
            lyrics.lines,
        )
        assertTrue(lyrics.isSynced)
    }

    @Test
    fun `repeated verse with several timestamps`() {
        val lyrics = LrcParser.parseSynced("[00:10.00][00:30.00] Chorus")
        assertEquals(listOf(10_000L, 30_000L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `current line follows the position`() {
        val lyrics = Lyrics(listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b")))
        assertEquals(-1, lyrics.lineIndexAt(500))
        assertEquals(0, lyrics.lineIndexAt(4_999))
        assertEquals(1, lyrics.lineIndexAt(9_000))
    }
}

class LrclibLyricsRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: LrclibLyricsRepository

    private val track = Track(
        id = TrackId("1"),
        provider = ProviderId("spotify"),
        title = "Song",
        artists = listOf(Artist("a", "Band")),
        album = Album("al", "Album", null, 2000),
        durationMs = 200_000,
    )

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = LrclibLyricsRepository(OkHttpClient(), server.url("/"), dispatcher = Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun respond(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `exact match returns synced lyrics`() = runTest {
        respond(200, """{"id":1,"duration":200,"syncedLyrics":"[00:01.00] Hello","plainLyrics":"Hello"}""")

        val lyrics = repository.lyricsFor(track)

        assertEquals(listOf(LyricLine(1_000, "Hello")), lyrics?.lines)
        val request = server.takeRequest()
        assertEquals("/api/get", request.url.encodedPath)
        assertEquals("Song", request.url.queryParameter("track_name"))
        assertEquals("200", request.url.queryParameter("duration"))
        assertTrue(request.headers["User-Agent"].orEmpty().startsWith("Slidedify"))
    }

    @Test
    fun `falls back to search and picks the closest duration`() = runTest {
        respond(404)
        respond(
            200,
            """[
              {"id":1,"duration":260,"plainLyrics":"too long"},
              {"id":2,"duration":201,"plainLyrics":"right one","unknownField":true},
              {"id":3,"duration":198.5,"plainLyrics":"close but worse"}
            ]""",
        )

        val lyrics = repository.lyricsFor(track)

        assertEquals("right one", lyrics?.lines?.single()?.text)
        assertEquals(false, lyrics?.isSynced)
    }

    @Test
    fun `instrumental and server errors give no lyrics`() = runTest {
        respond(200, """{"id":1,"duration":200,"instrumental":true}""")
        assertNull(repository.lyricsFor(track))

        respond(500)
        assertNull(repository.lyricsFor(track))
    }

    @Test
    fun `cache avoids repeated lookups, including misses`() = runTest {
        var calls = 0
        val cached = CachingLyricsRepository(
            object : LyricsRepository {
                override suspend fun lyricsFor(track: Track): Lyrics? {
                    calls++
                    return null
                }
            },
        )
        cached.lyricsFor(track)
        cached.lyricsFor(track)
        assertEquals(1, calls)
    }
}
