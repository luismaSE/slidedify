package com.slidedify.provider.tidal.api

import com.slidedify.core.domain.outbox.LibraryWriter
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderAuth
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackQuery
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import com.slidedify.core.oauth.AuthorizedHttp
import com.slidedify.core.oauth.NotSignedInException
import com.slidedify.core.oauth.OAuthConfig
import com.slidedify.core.oauth.RefreshingTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration

val TIDAL: ProviderId = ProviderId("tidal")

/**
 * TIDAL developer API (JSON:API, https://developer.tidal.com). Paths and scopes are kept here;
 * those marked "verify" must be confirmed with a registered app before release.
 */
object TidalEndpoints {
    const val BASE = "https://openapi.tidal.com/v2/"
    const val AUTHORIZE = "https://login.tidal.com/authorize"
    const val TOKEN = "https://auth.tidal.com/v1/oauth2/token"
    val SCOPES = listOf("user.read", "collection.read", "collection.write", "playlists.read", "playlists.write", "search.read")

    const val ME = "users/me"
    fun collectionTracks(userId: String) = "userCollections/$userId/relationships/tracks" // verify
    const val PLAYLISTS = "playlists"
    fun playlistItems(id: String) = "playlists/$id/relationships/items"
    // Search: searchResults/{query}/relationships/tracks (verify), built in TidalApi.search.
    const val TRACKS = "tracks"

    fun config(clientId: String, redirectUri: String) = OAuthConfig(
        clientId = clientId,
        redirectUri = redirectUri,
        authorizeUrl = AUTHORIZE,
        tokenUrl = TOKEN,
        scopes = SCOPES,
    )
}

/**
 * Minimal JSON:API reader: resources come in `data`, related resources (artists, albums) in
 * `included`, linked by type + id.
 */
internal class JsonApiDocument(root: JsonObject) {
    val data: List<JsonObject> = when (val d = root["data"]) {
        is JsonArray -> d.map { it.jsonObject }
        is JsonObject -> listOf(d)
        else -> emptyList()
    }
    private val included: Map<Pair<String, String>, JsonObject> = (root["included"] as? JsonArray).orEmpty()
        .map { it.jsonObject }
        .associateBy { it.str("type").orEmpty() to it.str("id").orEmpty() }
    val next: String? = (root["links"] as? JsonObject)?.str("next")

    fun resolve(ref: JsonObject): JsonObject = included[ref.str("type").orEmpty() to ref.str("id").orEmpty()] ?: ref

    fun related(resource: JsonObject, relationship: String): List<JsonObject> {
        val data = (resource["relationships"] as? JsonObject)?.get(relationship)?.let { (it as? JsonObject)?.get("data") }
        val refs = when (data) {
            is JsonArray -> data.map { it.jsonObject }
            is JsonObject -> listOf(data)
            else -> emptyList()
        }
        return refs.map(::resolve)
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)?.let {
    runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
}

internal fun JsonObject.attr(key: String): String? = (this["attributes"] as? JsonObject)?.str(key)

/** "PT3M45S" → 225000 ms. */
internal fun isoDurationMs(value: String?): Long = value?.let { runCatching { Duration.parse(it).toMillis() }.getOrNull() } ?: 0

class TidalApi(
    private val http: AuthorizedHttp,
    private val tokens: RefreshingTokens,
    private val baseUrl: HttpUrl = TidalEndpoints.BASE.toHttpUrl(),
    private val countryCode: String = "US",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl =
        baseUrl.resolve(path)!!.newBuilder()
            .addQueryParameter("countryCode", countryCode)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private suspend fun get(url: HttpUrl): JsonApiDocument =
        JsonApiDocument(json.parseToJsonElement(http.request("GET", url) ?: "{}").jsonObject)

    suspend fun userId(): String = tokens.userId()
        ?: get(url(TidalEndpoints.ME)).data.firstOrNull()?.str("id")
        ?: throw NotSignedInException()

    suspend fun userName(): String {
        val me = get(url(TidalEndpoints.ME)).data.firstOrNull()
        return me?.attr("username") ?: me?.attr("email") ?: "TIDAL"
    }

    /** Pages of tracks from a relationship endpoint (collection, playlist items, search). */
    fun trackPages(path: String): Flow<List<Track>> = flow {
        var next: HttpUrl? = url(path, "include" to "items,items.artists,items.albums,tracks,tracks.artists,tracks.albums")
        while (next != null) {
            val doc = get(next)
            emit(doc.data.map(doc::resolve).filter { it.str("type") == "tracks" }.mapNotNull { toTrack(doc, it) })
            next = doc.next?.let { link -> if (link.startsWith("http")) link.toHttpUrl() else baseUrl.resolve(link.removePrefix("/v2/").removePrefix("/")) }
        }
    }

    suspend fun allTracks(path: String): List<Track> {
        val result = mutableListOf<Track>()
        trackPages(path).collect { result += it }
        return result
    }

    suspend fun search(query: TrackQuery): List<Track> {
        query.isrc?.let { isrc ->
            val doc = get(url(TidalEndpoints.TRACKS, "filter[isrc]" to isrc, "include" to "artists,albums"))
            val exact = doc.data.mapNotNull { toTrack(doc, it) }
            if (exact.isNotEmpty()) return exact
        }
        val text = "${query.artist} ${query.title}".trim()
        // The query is a path segment: build it with addPathSegment so spaces and symbols are encoded.
        val searchUrl = baseUrl.newBuilder()
            .addPathSegment("searchResults").addPathSegment(text).addPathSegments("relationships/tracks")
            .addQueryParameter("countryCode", countryCode)
            .addQueryParameter("include", "tracks,tracks.artists,tracks.albums")
            .build()
        val doc = get(searchUrl)
        return doc.data.map(doc::resolve).mapNotNull { toTrack(doc, it) }
    }

    suspend fun myPlaylists(): List<Playlist> {
        val doc = get(url(TidalEndpoints.PLAYLISTS, "filter[r.owners.id]" to userId()))
        return doc.data.map { Playlist(PlaylistId(it.str("id").orEmpty()), TIDAL, it.attr("name") ?: "Playlist") }
    }

    suspend fun createPlaylist(name: String): Playlist {
        val body = buildJsonObject {
            putJsonObject("data") {
                put("type", "playlists")
                putJsonObject("attributes") {
                    put("name", name)
                    put("description", "Creada con Slidedify")
                    put("accessType", "UNLISTED")
                }
            }
        }
        val doc = JsonApiDocument(json.parseToJsonElement(http.request("POST", url(TidalEndpoints.PLAYLISTS), body.toString()) ?: "{}").jsonObject)
        val created = doc.data.firstOrNull() ?: error("TIDAL did not return the new playlist")
        return Playlist(PlaylistId(created.str("id").orEmpty()), TIDAL, created.attr("name") ?: name)
    }

    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        trackIds.chunked(MAX_WRITE).forEach { chunk ->
            http.request("POST", url(TidalEndpoints.playlistItems(playlistId)), refs(chunk).toString())
        }
    }

    /** Removing needs each entry's itemId, so the playlist is read first. */
    suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>) {
        val wanted = trackIds.toSet()
        val doc = get(url(TidalEndpoints.playlistItems(playlistId)))
        val entries = doc.data.filter { it.str("id") in wanted }
        if (entries.isEmpty()) return
        val body = buildJsonObject {
            put(
                "data",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("id", entry.str("id"))
                                put("type", "tracks")
                                (entry["meta"] as? JsonObject)?.let { put("meta", it) }
                            },
                        )
                    }
                },
            )
        }
        http.request("DELETE", url(TidalEndpoints.playlistItems(playlistId)), body.toString())
    }

    suspend fun setInCollection(trackIds: List<String>, saved: Boolean) {
        val path = TidalEndpoints.collectionTracks(userId())
        trackIds.chunked(MAX_WRITE).forEach { chunk ->
            http.request(if (saved) "POST" else "DELETE", url(path), refs(chunk).toString())
        }
    }

    private fun refs(ids: List<String>) = buildJsonObject {
        put("data", buildJsonArray { ids.forEach { id -> add(buildJsonObject { put("id", id); put("type", "tracks") }) } })
    }

    private fun toTrack(doc: JsonApiDocument, resource: JsonObject): Track? {
        val id = resource.str("id") ?: return null
        if (resource.str("type") != "tracks") return null
        val artists = doc.related(resource, "artists").map { Artist(it.str("id").orEmpty(), it.attr("name").orEmpty()) }
            .filter { it.name.isNotBlank() }
        val album = doc.related(resource, "albums").firstOrNull()
        return Track(
            id = TrackId(id),
            provider = TIDAL,
            title = resource.attr("title") ?: return null,
            artists = artists,
            album = Album(
                id = album?.str("id").orEmpty(),
                name = album?.attr("title").orEmpty(),
                imageUrl = null,
                releaseYear = album?.attr("releaseDate")?.take(4)?.toIntOrNull(),
            ),
            durationMs = isoDurationMs(resource.attr("duration")),
            externalUrl = "https://tidal.com/browse/track/$id",
            isrc = resource.attr("isrc"),
        )
    }

    companion object {
        const val MAX_WRITE = 20 // verify: per-request limit for relationship writes
    }
}

class TidalTrackRepository(private val api: TidalApi) : TrackRepository {
    override suspend fun tracks(source: TrackSource): List<Track> = when (source) {
        TrackSource.LikedSongs -> api.allTracks(TidalEndpoints.collectionTracks(api.userId()))
        is TrackSource.FromPlaylist -> api.allTracks(TidalEndpoints.playlistItems(source.id.value))
        TrackSource.Recommended -> emptyList()
    }

    override fun pages(source: TrackSource): Flow<List<Track>> = flow {
        when (source) {
            TrackSource.LikedSongs -> api.trackPages(TidalEndpoints.collectionTracks(api.userId())).collect { emit(it) }
            is TrackSource.FromPlaylist -> api.trackPages(TidalEndpoints.playlistItems(source.id.value)).collect { emit(it) }
            TrackSource.Recommended -> emit(emptyList())
        }
    }

    override suspend fun search(query: TrackQuery): List<Track> = api.search(query)
}

class TidalPlaylistRepository(private val api: TidalApi) : PlaylistRepository {
    override suspend fun writablePlaylists(): List<Playlist> = api.myPlaylists()

    override suspend fun createPlaylist(name: String): Playlist = api.createPlaylist(name)
}

class TidalLibraryWriter(private val api: TidalApi) : LibraryWriter {
    override suspend fun addToPlaylist(playlist: PlaylistId, tracks: List<TrackId>) = api.addToPlaylist(playlist.value, tracks.map { it.value })

    override suspend fun removeFromPlaylist(playlist: PlaylistId, tracks: List<TrackId>) =
        api.removeFromPlaylist(playlist.value, tracks.map { it.value })

    override suspend fun setLiked(tracks: List<TrackId>, liked: Boolean) = api.setInCollection(tracks.map { it.value }, liked)

    override suspend fun playlistTrackIds(playlist: PlaylistId): Set<TrackId> =
        api.allTracks(TidalEndpoints.playlistItems(playlist.value)).mapTo(HashSet()) { it.id }
}

class TidalAuth(private val tokens: RefreshingTokens, private val api: TidalApi) : ProviderAuth {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.value = try {
            if (tokens.accessToken(false) == null) AuthState.SignedOut else AuthState.SignedIn(api.userName())
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotSignedInException) {
            AuthState.SignedOut
        } catch (e: java.io.IOException) {
            AuthState.Error(e.message ?: "Error de conexión")
        }
    }

    suspend fun completeSignIn(code: String, verifier: String) {
        tokens.signIn(code, verifier)
        refresh()
    }

    override suspend fun signOut() {
        tokens.signOut()
        _state.value = AuthState.SignedOut
    }
}

/** TIDAL as a [MusicProvider]. Playback uses previews (same as Spotify). */
class TidalMusicProvider(
    override val auth: TidalAuth,
    override val tracks: TidalTrackRepository,
    override val playlists: TidalPlaylistRepository,
    override val writer: TidalLibraryWriter,
    override val player: Player,
) : MusicProvider {
    override val id = TIDAL
    override val displayName = "TIDAL"
    override val capabilities = ProviderCapabilities(
        canLike = true,
        canSearch = true,
        canCreatePlaylists = true,
        canPlayFullTracks = false,
        sources = setOf(TrackSource.Kind.LikedSongs, TrackSource.Kind.Playlist),
        maxTracksPerWrite = TidalApi.MAX_WRITE,
    )
}
