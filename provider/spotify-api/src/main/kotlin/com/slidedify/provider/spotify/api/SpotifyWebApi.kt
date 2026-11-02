package com.slidedify.provider.spotify.api

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Every Web API path in one place. Spotify changed several routes in 2026 (e.g. playlist
 * `/tracks` → `/items`, library writes consolidated under `/me/library`); paths marked
 * "verify" come from developer reports and must be confirmed against the official changelog
 * with a real account.
 */
object SpotifyEndpoints {
    const val ME = "v1/me"
    const val SAVED_TRACKS = "v1/me/tracks"
    const val LIBRARY = "v1/me/library" // verify: request format
    const val MY_PLAYLISTS = "v1/me/playlists" // GET list, POST create (verify for create)
    const val TOP_TRACKS = "v1/me/top/tracks"
    const val SEARCH = "v1/search"
    /** Developer reports say search is capped at 10 results since 2026. */
    const val SEARCH_LIMIT = 10
    fun playlistItems(id: String) = "v1/playlists/$id/items" // verify: was /tracks before 2026
    const val PLAYER = "v1/me/player"
    const val DEVICES = "v1/me/player/devices"
    const val PLAY = "v1/me/player/play"
    const val PAUSE = "v1/me/player/pause"

    const val PAGE_SIZE = 50
    const val MAX_PLAYLIST_WRITE = 100
    const val MAX_LIBRARY_WRITE = 40
}

/** HTTP 429. [quotaExceeded] when Spotify reports `"reason": "QUOTA_EXCEEDED"` (app-wide quota). */
class SpotifyRateLimitException(val retryAfterSeconds: Long, val quotaExceeded: Boolean) :
    IOException("Spotify rate limit, retry after ${retryAfterSeconds}s${if (quotaExceeded) " (quota exceeded)" else ""}")

class SpotifyApiException(val code: Int, message: String) : IOException("Spotify API $code: $message")

class SpotifyNotSignedInException : IOException("Not signed in to Spotify")

// --- DTOs (tolerant: unknown fields ignored, optional everything that may be missing) ----------

@Serializable
data class UserDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    val product: String? = null,
)

@Serializable
data class ImageDto(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class ArtistDto(val id: String? = null, val name: String)

@Serializable
data class AlbumDto(
    val id: String? = null,
    val name: String = "",
    val images: List<ImageDto> = emptyList(),
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class TrackDto(
    val id: String? = null,
    val name: String = "",
    val type: String = "track",
    val artists: List<ArtistDto> = emptyList(),
    val album: AlbumDto = AlbumDto(),
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("is_local") val isLocal: Boolean = false,
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
    @SerialName("external_ids") val externalIds: Map<String, String> = emptyMap(),
)

/** Saved-tracks and playlist entries. Playlists renamed `track` to `item` in 2026; accept both. */
@Serializable
data class EntryDto(
    val track: TrackDto? = null,
    val item: TrackDto? = null,
) {
    val content: TrackDto? get() = item ?: track
}

@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int? = null,
)

@Serializable
data class SearchDto(val tracks: PageDto<TrackDto> = PageDto())

@Serializable
data class OwnerDto(val id: String)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String,
    val owner: OwnerDto? = null,
    val collaborative: Boolean = false,
    val images: List<ImageDto>? = null,
    /** `{ "total": n }`; newer API versions call it `items`. Kept loose so either shape parses. */
    val tracks: JsonElement? = null,
    val items: JsonElement? = null,
) {
    val trackCount: Int?
        get() = listOfNotNull(tracks, items).firstNotNullOfOrNull { element ->
            ((element as? JsonObject)?.get("total") as? JsonPrimitive)?.intOrNull
        }
}

@Serializable
data class DeviceDto(
    val id: String? = null,
    val name: String = "",
    val type: String = "",
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_restricted") val isRestricted: Boolean = false,
)

@Serializable
data class DevicesDto(val devices: List<DeviceDto> = emptyList())

@Serializable
data class PlaybackDto(
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("progress_ms") val progressMs: Long? = null,
    val item: TrackDto? = null,
)

/**
 * Thin Spotify Web API client: auth header, one retry after a 401 with a refreshed token,
 * typed errors for 429. No business logic here.
 */
class SpotifyWebApi(
    private val client: OkHttpClient,
    private val tokens: TokenProvider,
    private val baseUrl: HttpUrl = "https://api.spotify.com/".toHttpUrl(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    suspend fun me(): UserDto = get(SpotifyEndpoints.ME, UserDto.serializer())

    suspend fun savedTracks(): List<TrackDto> = pagedEntries(SpotifyEndpoints.SAVED_TRACKS)

    suspend fun playlistTracks(playlistId: String): List<TrackDto> = pagedEntries(SpotifyEndpoints.playlistItems(playlistId))

    /** Saved tracks page by page (first page arrives after a single request). */
    fun savedTrackPages(): Flow<List<TrackDto>> = entryPages(SpotifyEndpoints.SAVED_TRACKS)

    fun playlistTrackPages(playlistId: String): Flow<List<TrackDto>> = entryPages(SpotifyEndpoints.playlistItems(playlistId))

    /** Spotify Connect devices of the user (the phone's Spotify app appears here while it is open). */
    suspend fun devices(): List<DeviceDto> = get(SpotifyEndpoints.DEVICES, DevicesDto.serializer()).devices

    /** Catalog search; [query] uses Spotify's field filters, e.g. `isrc:XXX` or `track:"x" artist:"y"`. */
    suspend fun searchTracks(query: String): List<TrackDto> {
        val url = baseUrl.newBuilder().addPathSegments(SpotifyEndpoints.SEARCH)
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", SpotifyEndpoints.SEARCH_LIMIT.toString())
            .build()
        return get(url.toString(), SearchDto.serializer()).tracks.items
    }

    suspend fun topTracks(): List<TrackDto> =
        get("${SpotifyEndpoints.TOP_TRACKS}?limit=${SpotifyEndpoints.PAGE_SIZE}", PageDto.serializer(TrackDto.serializer())).items

    suspend fun myPlaylists(): List<PlaylistDto> {
        val result = mutableListOf<PlaylistDto>()
        var path: String? = "${SpotifyEndpoints.MY_PLAYLISTS}?limit=${SpotifyEndpoints.PAGE_SIZE}"
        while (path != null) {
            val page = get(path, PageDto.serializer(PlaylistDto.serializer()))
            result += page.items
            path = page.next
        }
        return result
    }

    suspend fun createPlaylist(name: String): PlaylistDto {
        val body = buildJsonObject {
            put("name", name)
            put("public", false)
            put("description", "Creada con Compass")
        }
        return send("POST", SpotifyEndpoints.MY_PLAYLISTS, body.toString(), PlaylistDto.serializer())
    }

    suspend fun addToPlaylist(playlistId: String, uris: List<String>) {
        uris.chunked(SpotifyEndpoints.MAX_PLAYLIST_WRITE).forEach { chunk ->
            val body = buildJsonObject { put("uris", JsonArray(chunk.map(::JsonPrimitive))) }
            send("POST", SpotifyEndpoints.playlistItems(playlistId), body.toString())
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, uris: List<String>) {
        uris.chunked(SpotifyEndpoints.MAX_PLAYLIST_WRITE).forEach { chunk ->
            val items = JsonArray(chunk.map { uri -> buildJsonObject { put("uri", uri) } })
            send("DELETE", SpotifyEndpoints.playlistItems(playlistId), buildJsonObject { put("items", items) }.toString())
        }
    }

    /** Liked Songs. Uses the consolidated `/me/library` endpoint with track URIs. */
    suspend fun setSaved(uris: List<String>, saved: Boolean) {
        uris.chunked(SpotifyEndpoints.MAX_LIBRARY_WRITE).forEach { chunk ->
            val path = "${SpotifyEndpoints.LIBRARY}?uris=${chunk.joinToString(",")}"
            send(if (saved) "PUT" else "DELETE", path, null)
        }
    }

    suspend fun play(uris: List<String>, deviceId: String? = null) {
        val body = buildJsonObject { put("uris", JsonArray(uris.map(::JsonPrimitive))) }
        send("PUT", SpotifyEndpoints.PLAY + (deviceId?.let { "?device_id=$it" } ?: ""), body.toString())
    }

    suspend fun resume() = send("PUT", SpotifyEndpoints.PLAY, null)

    suspend fun pause() = send("PUT", SpotifyEndpoints.PAUSE, null)

    /** `null` when nothing is playing (HTTP 204). */
    suspend fun playback(): PlaybackDto? = request("GET", SpotifyEndpoints.PLAYER, null)?.let {
        json.decodeFromString(PlaybackDto.serializer(), it)
    }

    // --- plumbing ---------------------------------------------------------------------------------

    private suspend fun pagedEntries(firstPath: String): List<TrackDto> {
        val result = mutableListOf<TrackDto>()
        entryPages(firstPath).collect { result += it }
        return result
    }

    private fun entryPages(firstPath: String): Flow<List<TrackDto>> = flow {
        var path: String? = if ('?' in firstPath) firstPath else "$firstPath?limit=${SpotifyEndpoints.PAGE_SIZE}"
        while (path != null) {
            val page = get(path, PageDto.serializer(EntryDto.serializer()))
            emit(page.items.mapNotNull { it.content })
            path = page.next
        }
    }

    private suspend fun <T> get(path: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromString(serializer, request("GET", path, null) ?: throw SpotifyApiException(204, "Empty body"))

    private suspend fun <T> send(method: String, path: String, body: String?, serializer: kotlinx.serialization.KSerializer<T>): T =
        json.decodeFromString(serializer, request(method, path, body) ?: throw SpotifyApiException(204, "Empty body"))

    private suspend fun send(method: String, path: String, body: String?) {
        request(method, path, body)
    }

    /** Body text, or null for 204. [path] may also be an absolute `next` URL from a page. */
    private suspend fun request(method: String, path: String, body: String?): String? = withContext(dispatcher) {
        val url = if (path.startsWith("http")) path.toHttpUrl() else baseUrl.resolve(path)!!
        var token = tokens.accessToken() ?: throw SpotifyNotSignedInException()
        repeat(2) { attempt ->
            val requestBody: RequestBody? = body?.toRequestBody(jsonType)
                ?: if (method == "GET") null else ByteArray(0).toRequestBody(null)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .method(method, requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 204 -> return@withContext null
                    response.isSuccessful -> return@withContext text
                    response.code == 401 && attempt == 0 -> {
                        token = tokens.accessToken(forceRefresh = true) ?: throw SpotifyNotSignedInException()
                    }
                    response.code == 429 -> throw SpotifyRateLimitException(
                        retryAfterSeconds = response.header("Retry-After")?.toLongOrNull() ?: 5,
                        quotaExceeded = "QUOTA_EXCEEDED" in text,
                    )
                    else -> throw SpotifyApiException(response.code, text.take(300))
                }
            }
        }
        throw SpotifyNotSignedInException()
    }
}
