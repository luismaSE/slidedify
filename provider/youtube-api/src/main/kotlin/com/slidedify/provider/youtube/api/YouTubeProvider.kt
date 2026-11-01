package com.slidedify.provider.youtube.api

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

val YOUTUBE_MUSIC: ProviderId = ProviderId("youtube-music")

/**
 * YouTube Music has no public API of its own; its playlists and likes are regular YouTube
 * playlists/ratings, available through the official YouTube Data API v3. Limits worth knowing:
 * a daily quota of 10,000 units (a search costs 100, an insert 50, a read 1) and no ISRC or
 * duration in playlist items, so cross-service matching relies on title and artist.
 */
object YouTubeEndpoints {
    const val BASE = "https://www.googleapis.com/youtube/v3/"
    const val SCOPE = "https://www.googleapis.com/auth/youtube"
    const val PAGE_SIZE = 50
    const val MUSIC_CATEGORY = "10"
}

@Serializable
internal data class Thumbnail(val url: String = "")

@Serializable
internal data class Snippet(
    val title: String = "",
    val channelTitle: String = "",
    val videoOwnerChannelTitle: String? = null,
    val publishedAt: String? = null,
    val thumbnails: Map<String, Thumbnail> = emptyMap(),
    val resourceId: ResourceId? = null,
    val playlistId: String? = null,
)

@Serializable
internal data class ResourceId(val videoId: String? = null)

@Serializable
internal data class ContentDetails(val videoId: String? = null, val duration: String? = null)

/** `id` is a string for videos/playlists/playlistItems and an object for search results. */
@Serializable
internal data class Item(
    val id: kotlinx.serialization.json.JsonElement? = null,
    val snippet: Snippet = Snippet(),
    val contentDetails: ContentDetails? = null,
)

@Serializable
internal data class ListResponse(
    val items: List<Item> = emptyList(),
    val nextPageToken: String? = null,
)

/** "Artist - Song" titles and "Artist - Topic" channels are how music shows up on YouTube. */
internal fun splitArtistTitle(rawTitle: String, channel: String): Pair<String, String> {
    val topic = Regex("""\s+-\s+Topic$""", RegexOption.IGNORE_CASE)
    if (topic.containsMatchIn(channel)) return channel.replace(topic, "") to rawTitle
    val dash = rawTitle.indexOf(" - ")
    if (dash > 0) return rawTitle.substring(0, dash).trim() to rawTitle.substring(dash + 3).trim()
    return channel.removeSuffix("VEVO").trim() to rawTitle
}

/** "PT3M45S" → 225000 ms; 0 when missing. */
internal fun isoDurationMs(value: String?): Long = value?.let { runCatching { java.time.Duration.parse(it).toMillis() }.getOrNull() } ?: 0

class YouTubeApi(
    private val http: AuthorizedHttp,
    private val baseUrl: HttpUrl = YouTubeEndpoints.BASE.toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String, vararg params: Pair<String, String?>): HttpUrl =
        baseUrl.resolve(path)!!.newBuilder().apply { params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }.build()

    private suspend fun list(url: HttpUrl): ListResponse =
        json.decodeFromString(ListResponse.serializer(), http.request("GET", url) ?: "{}")

    suspend fun channelName(): String =
        list(url("channels", "part" to "snippet", "mine" to "true")).items.firstOrNull()?.snippet?.title ?: "YouTube"

    fun likedPages(): Flow<List<Track>> = pages { token ->
        url("videos", "part" to "snippet,contentDetails", "myRating" to "like", "maxResults" to "${YouTubeEndpoints.PAGE_SIZE}", "pageToken" to token)
    }

    fun playlistPages(playlistId: String): Flow<List<Track>> = pages { token ->
        url("playlistItems", "part" to "snippet,contentDetails", "playlistId" to playlistId, "maxResults" to "${YouTubeEndpoints.PAGE_SIZE}", "pageToken" to token)
    }

    private fun pages(urlFor: (String?) -> HttpUrl): Flow<List<Track>> = flow {
        var token: String? = null
        do {
            val response = list(urlFor(token))
            emit(response.items.mapNotNull(::toTrack))
            token = response.nextPageToken
        } while (token != null)
    }

    suspend fun search(query: TrackQuery): List<Track> {
        val q = "${query.artist} ${query.title}".trim()
        return list(
            url("search", "part" to "snippet", "type" to "video", "videoCategoryId" to YouTubeEndpoints.MUSIC_CATEGORY, "maxResults" to "10", "q" to q),
        ).items.mapNotNull(::toTrack)
    }

    suspend fun myPlaylists(): List<Playlist> {
        val result = mutableListOf<Playlist>()
        var token: String? = null
        do {
            val response = list(url("playlists", "part" to "snippet", "mine" to "true", "maxResults" to "${YouTubeEndpoints.PAGE_SIZE}", "pageToken" to token))
            result += response.items.mapNotNull { item ->
                item.id?.let { id -> Playlist(PlaylistId(id.toString().trim('"')), YOUTUBE_MUSIC, item.snippet.title) }
            }
            token = response.nextPageToken
        } while (token != null)
        return result
    }

    suspend fun createPlaylist(name: String): Playlist {
        val body = buildJsonObject {
            putJsonObject("snippet") {
                put("title", name)
                put("description", "Creada con Slidedify")
            }
            putJsonObject("status") { put("privacyStatus", "private") }
        }
        val created = json.decodeFromString(Item.serializer(), http.request("POST", url("playlists", "part" to "snippet,status"), body.toString()) ?: "{}")
        return Playlist(PlaylistId(created.id.toString().trim('"')), YOUTUBE_MUSIC, created.snippet.title.ifBlank { name })
    }

    /** One request per video: the API has no batch insert. */
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>) {
        videoIds.forEach { videoId ->
            val body = buildJsonObject {
                putJsonObject("snippet") {
                    put("playlistId", playlistId)
                    putJsonObject("resourceId") {
                        put("kind", "youtube#video")
                        put("videoId", videoId)
                    }
                }
            }
            http.request("POST", url("playlistItems", "part" to "snippet"), body.toString())
        }
    }

    /** Deleting needs the playlist item id (not the video id), so the playlist is read first. */
    suspend fun removeFromPlaylist(playlistId: String, videoIds: List<String>) {
        val wanted = videoIds.toSet()
        var token: String? = null
        do {
            val response = list(url("playlistItems", "part" to "contentDetails", "playlistId" to playlistId, "maxResults" to "${YouTubeEndpoints.PAGE_SIZE}", "pageToken" to token))
            response.items.filter { it.contentDetails?.videoId in wanted }.forEach { item ->
                http.request("DELETE", url("playlistItems", "id" to item.id.toString().trim('"')))
            }
            token = response.nextPageToken
        } while (token != null)
    }

    suspend fun rate(videoIds: List<String>, liked: Boolean) {
        videoIds.forEach { id -> http.request("POST", url("videos/rate", "id" to id, "rating" to if (liked) "like" else "none")) }
    }

    private fun toTrack(item: Item): Track? {
        val videoId = item.contentDetails?.videoId
            ?: item.snippet.resourceId?.videoId
            ?: (item.id as? kotlinx.serialization.json.JsonObject)?.get("videoId")?.toString()?.trim('"')
            ?: item.id?.toString()?.trim('"')?.takeIf { it.isNotBlank() && !it.startsWith("{") }
            ?: return null
        val channel = item.snippet.videoOwnerChannelTitle ?: item.snippet.channelTitle
        if (item.snippet.title == "Deleted video" || item.snippet.title == "Private video") return null
        val (artist, title) = splitArtistTitle(item.snippet.title, channel)
        val thumbnails = item.snippet.thumbnails
        return Track(
            id = TrackId(videoId),
            provider = YOUTUBE_MUSIC,
            title = title,
            artists = listOf(Artist(channel, artist)),
            album = Album("", "", (thumbnails["high"] ?: thumbnails["medium"] ?: thumbnails["default"])?.url, item.snippet.publishedAt?.take(4)?.toIntOrNull()),
            durationMs = isoDurationMs(item.contentDetails?.duration),
            externalUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }
}

class YouTubeTrackRepository(private val api: YouTubeApi) : TrackRepository {
    override suspend fun tracks(source: TrackSource): List<Track> {
        val all = mutableListOf<Track>()
        pages(source).collect { all += it }
        return all
    }

    override fun pages(source: TrackSource): Flow<List<Track>> = when (source) {
        TrackSource.LikedSongs -> api.likedPages()
        is TrackSource.FromPlaylist -> api.playlistPages(source.id.value)
        TrackSource.Recommended -> flow { emit(emptyList()) }
    }

    override suspend fun search(query: TrackQuery): List<Track> = api.search(query)
}

class YouTubePlaylistRepository(private val api: YouTubeApi) : PlaylistRepository {
    override suspend fun writablePlaylists(): List<Playlist> = api.myPlaylists()

    override suspend fun createPlaylist(name: String): Playlist = api.createPlaylist(name)
}

class YouTubeLibraryWriter(private val api: YouTubeApi) : LibraryWriter {
    override suspend fun addToPlaylist(playlist: PlaylistId, tracks: List<TrackId>) = api.addToPlaylist(playlist.value, tracks.map { it.value })

    override suspend fun removeFromPlaylist(playlist: PlaylistId, tracks: List<TrackId>) =
        api.removeFromPlaylist(playlist.value, tracks.map { it.value })

    override suspend fun setLiked(tracks: List<TrackId>, liked: Boolean) = api.rate(tracks.map { it.value }, liked)
}

/** Signed-in state; the token itself comes from Google Play Services on Android. */
class YouTubeAuth(
    private val hasToken: suspend () -> Boolean,
    private val api: YouTubeApi,
    private val onSignOut: suspend () -> Unit,
) : ProviderAuth {
    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    suspend fun refresh() {
        _state.value = try {
            if (!hasToken()) AuthState.SignedOut else AuthState.SignedIn(api.channelName())
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotSignedInException) {
            AuthState.SignedOut
        } catch (e: java.io.IOException) {
            AuthState.Error(e.message ?: "Error de conexión")
        }
    }

    override suspend fun signOut() {
        onSignOut()
        _state.value = AuthState.SignedOut
    }
}

class YouTubeMusicProvider(
    override val auth: YouTubeAuth,
    override val tracks: YouTubeTrackRepository,
    override val playlists: YouTubePlaylistRepository,
    override val writer: YouTubeLibraryWriter,
    override val player: Player,
) : MusicProvider {
    override val id = YOUTUBE_MUSIC
    override val displayName = "YouTube Music"
    override val capabilities = ProviderCapabilities(
        canLike = true,
        canSearch = true,
        canCreatePlaylists = true,
        canPlayFullTracks = false,
        sources = setOf(TrackSource.Kind.LikedSongs, TrackSource.Kind.Playlist),
        maxTracksPerWrite = 25,
    )
}
