package com.slidedify.provider.spotify.api

import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun `challenge matches the RFC 7636 example`() {
        val pkce = Pkce.fromVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
    }

    @Test
    fun `generated verifier has a valid length and alphabet`() {
        val verifier = Pkce.generate().verifier
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}

class SpotifyApiTest {
    private lateinit var server: MockWebServer
    private val store = MemoryTokenStore()
    private var now = 1_000_000L
    private val config = SpotifyAuthConfig("client-123", "slidedify://callback")

    private lateinit var auth: SpotifyAuthClient
    private lateinit var tokens: TokenProvider
    private lateinit var api: SpotifyWebApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        auth = SpotifyAuthClient(config, client, server.url("/accounts/"), { now }, Dispatchers.Unconfined)
        tokens = TokenProvider(store, auth) { now }
        api = SpotifyWebApi(client, tokens, server.url("/"), Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() = server.close()

    private fun respond(code: Int = 200, body: String = "", headers: Map<String, String> = emptyMap()) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun signedIn(expiresIn: Long = 3600) = kotlinx.coroutines.runBlocking {
        store.save(SpotifyTokens("access-1", "refresh-1", expiresIn, obtainedAtMs = now))
    }

    // --- Auth -------------------------------------------------------------------------------------

    @Test
    fun `authorize url carries PKCE and scopes`() {
        val url = auth.authorizeUrl(Pkce.fromVerifier("v".repeat(43)), state = "xyz")
        assertEquals("/accounts/authorize", url.encodedPath)
        assertEquals("client-123", url.queryParameter("client_id"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("slidedify://callback", url.queryParameter("redirect_uri"))
        assertEquals("xyz", url.queryParameter("state"))
        assertTrue(url.queryParameter("scope")!!.contains("playlist-modify-private"))
    }

    @Test
    fun `code exchange sends the verifier and stores tokens`() = runTest {
        respond(body = """{"access_token":"a","refresh_token":"r","expires_in":3600,"token_type":"Bearer"}""")

        tokens.signIn("the-code", "the-verifier")

        val request = server.takeRequest()
        assertEquals("/accounts/api/token", request.url.encodedPath)
        val form = request.body?.utf8().orEmpty()
        assertTrue("grant_type=authorization_code" in form)
        assertTrue("code_verifier=the-verifier" in form)
        assertTrue("client_id=client-123" in form)
        assertEquals("a", store.tokens?.accessToken)
        assertEquals(now, store.tokens?.obtainedAtMs)
    }

    @Test
    fun `expired token is refreshed and the old refresh token kept`() = runTest {
        signedIn(expiresIn = 10)
        now += 60_000
        respond(body = """{"access_token":"access-2","expires_in":3600}""")

        assertEquals("access-2", tokens.accessToken())
        assertEquals("refresh-1", store.tokens?.refreshToken)
    }

    // --- Web API ----------------------------------------------------------------------------------

    private fun trackJson(id: String, extra: String = "") =
        """{"id":"$id","name":"Song $id","type":"track","duration_ms":200000,
           "artists":[{"id":"ar","name":"Band"}],
           "album":{"id":"al","name":"Album","release_date":"1999-05-01","images":[{"url":"small","width":64},{"url":"big","width":640}]},
           "external_urls":{"spotify":"https://open.spotify.com/track/$id"}$extra}"""

    @Test
    fun `liked songs follow pagination and map to domain tracks`() = runTest {
        signedIn()
        respond(body = """{"items":[{"track":${trackJson("1")}}],"next":"${server.url("/v1/me/tracks?offset=50")}"}""")
        respond(body = """{"items":[{"track":${trackJson("2")}},{"track":${trackJson("3", ",\"is_local\":true")}}],"next":null}""")

        val result = SpotifyTrackRepository(api).tracks(TrackSource.LikedSongs)

        assertEquals(listOf("1", "2"), result.map { it.id.value })
        val first = result.first()
        assertEquals("big", first.album.imageUrl)
        assertEquals(1999, first.album.releaseYear)
        assertEquals("https://open.spotify.com/track/1", first.externalUrl)
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `the date each song was added is kept`() = runTest {
        signedIn()
        respond(body = """{"items":[{"added_at":"2024-03-01T10:00:00Z","item":${trackJson("1")}},{"item":${trackJson("2")}}]}""")

        val result = SpotifyTrackRepository(api).tracks(TrackSource.FromPlaylist(PlaylistId("pl")))

        assertEquals(java.time.Instant.parse("2024-03-01T10:00:00Z").toEpochMilli(), result[0].addedAt)
        assertEquals(null, result[1].addedAt)
    }

    @Test
    fun `playlist items accept the new item key and skip episodes`() = runTest {
        signedIn()
        respond(body = """{"items":[{"item":${trackJson("1")}},{"item":${trackJson("2", ",\"type\":\"episode\"").replace("\"type\":\"track\",", "")}}]}""")

        val result = SpotifyTrackRepository(api).tracks(TrackSource.FromPlaylist(PlaylistId("pl")))

        assertEquals(listOf("1"), result.map { it.id.value })
        assertEquals("/v1/playlists/pl/items", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `only owned or collaborative playlists are writable`() = runTest {
        signedIn()
        respond(body = """{"id":"me"}""")
        respond(
            body = """{"items":[
              {"id":"a","name":"Mine","owner":{"id":"me"}},
              {"id":"b","name":"Theirs","owner":{"id":"other"}},
              {"id":"c","name":"Shared","owner":{"id":"other"},"collaborative":true}
            ]}""",
        )
        val names = SpotifyPlaylistRepository(api).writablePlaylists().map { it.name }
        assertEquals(listOf("Mine", "Shared"), names)
    }

    @Test
    fun `adding many tracks is split in chunks of 100`() = runTest {
        signedIn()
        respond(201, """{"snapshot_id":"s1"}""")
        respond(201, """{"snapshot_id":"s2"}""")

        SpotifyLibraryWriter(api).addToPlaylist(PlaylistId("pl"), (1..150).map { TrackId("t$it") })

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/v1/playlists/pl/items", first.url.encodedPath)
        assertTrue(first.body!!.utf8().startsWith("""{"uris":["spotify:track:t1","""))
        assertEquals(100, Regex("spotify:track:").findAll(first.body!!.utf8()).count())
        assertEquals(50, Regex("spotify:track:").findAll(server.takeRequest().body!!.utf8()).count())
    }

    @Test
    fun `a 401 triggers one refresh and a retry`() = runTest {
        signedIn()
        respond(401, """{"error":{"status":401,"message":"The access token expired"}}""")
        respond(body = """{"access_token":"access-2","expires_in":3600}""") // token refresh
        respond(body = """{"id":"me","display_name":"Luis"}""")

        assertEquals("Luis", api.me().displayName)
        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `429 is reported with retry-after and quota flag`() = runTest {
        signedIn()
        respond(429, """{"error":{"status":429,"reason":"QUOTA_EXCEEDED"}}""", mapOf("Retry-After" to "30"))

        val error = assertFailsWith<SpotifyRateLimitException> { api.me() }
        assertEquals(30, error.retryAfterSeconds)
        assertTrue(error.quotaExceeded)
    }

    @Test
    fun `not signed in fails fast and auth reports signed out`() = runTest {
        assertFailsWith<SpotifyNotSignedInException> { api.me() }
        val providerAuth = SpotifyProviderAuth(tokens, api)
        providerAuth.refresh()
        assertEquals(com.slidedify.core.domain.provider.AuthState.SignedOut, providerAuth.state.value)
    }

    @Test
    fun `nothing playing returns null`() = runTest {
        signedIn()
        respond(204)
        assertNull(api.playback())
    }

    @Test
    fun `pages arrive one by one so the first one can be shown right away`() = runTest {
        signedIn()
        respond(body = """{"items":[{"item":${trackJson("1")}}],"next":"${server.url("/v1/playlists/pl/items?offset=50")}"}""")
        respond(body = """{"items":[{"item":${trackJson("2")}}],"next":null}""")

        val first = SpotifyTrackRepository(api).pages(TrackSource.FromPlaylist(PlaylistId("pl"))).first()

        assertEquals(listOf("1"), first.map { it.id.value })
        assertEquals(1, server.requestCount) // the second page was not requested yet
    }

    @Test
    fun `without an active device playback targets the phone`() = runTest {
        signedIn()
        respond(404, """{"error":{"status":404,"message":"Player command failed: No active device found","reason":"NO_ACTIVE_DEVICE"}}""")
        respond(
            body = """{"devices":[
              {"id":"tv","name":"TV","type":"TV","is_active":false},
              {"id":"phone","name":"Pixel","type":"Smartphone","is_active":false}
            ]}""",
        )
        respond(204)
        val player = SpotifyWebPlayer(api, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), pollIntervalMs = Long.MAX_VALUE)
        val track = com.slidedify.core.model.Track(
            TrackId("abc"), SPOTIFY, "Song", emptyList(), com.slidedify.core.model.Album("", "", null, null), 1000,
        )

        player.play(track)
        respond(204)
        player.pause() // stop the local ticker

        server.takeRequest() // first play (404)
        assertEquals("/v1/me/player/devices", server.takeRequest().url.encodedPath)
        val retry = server.takeRequest()
        assertEquals("phone", retry.url.queryParameter("device_id"))
        assertTrue(retry.body!!.utf8().contains("spotify:track:abc"))
        assertNull(player.state.value.error)
    }

    @Test
    fun `writer reports what a playlist already has`() = runTest {
        signedIn()
        respond(body = """{"items":[{"item":${trackJson("1")}},{"item":${trackJson("2")}}]}""")
        assertEquals(setOf(TrackId("1"), TrackId("2")), SpotifyLibraryWriter(api).playlistTrackIds(PlaylistId("pl")))
    }

    class MemoryTokenStore : TokenStore {
        var tokens: SpotifyTokens? = null

        override suspend fun load() = tokens

        override suspend fun save(tokens: SpotifyTokens) {
            this.tokens = tokens
        }

        override suspend fun clear() {
            tokens = null
        }
    }
}
