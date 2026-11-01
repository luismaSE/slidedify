package com.slidedify.preview.deezer

import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs

@Serializable
internal data class DeezerArtist(val name: String = "")

@Serializable
internal data class DeezerTrack(
    val title: String = "",
    /** Seconds. */
    val duration: Int = 0,
    val preview: String? = null,
    val artist: DeezerArtist = DeezerArtist(),
)

@Serializable
internal data class DeezerSearch(val data: List<DeezerTrack> = emptyList())

/**
 * 30-second previews from Deezer's public search API (no key needed). Spotify no longer gives
 * preview URLs to new apps, so this is the fallback when full playback isn't possible.
 * Picks the result whose duration is closest to the track's; null when nothing plausible matches.
 */
class DeezerPreviewSource(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.deezer.com/".toHttpUrl(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PreviewSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun previewUrl(track: Track): String? = withContext(dispatcher) {
        try {
            val artist = track.artists.firstOrNull()?.name.orEmpty()
            // Strict query first ("Song - Remastered 2011" style suffixes are cut), then a loose one.
            val title = track.title.substringBefore(" - ").substringBefore(" (").trim()
            search("artist:\"$artist\" track:\"$title\"", track) ?: search("$artist $title", track)
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }

    private fun search(query: String, track: Track): String? {
        val url = baseUrl.newBuilder().addPathSegment("search").addQueryParameter("q", query).addQueryParameter("limit", "10").build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val results = json.decodeFromString(DeezerSearch.serializer(), response.body.string()).data
            val seconds = track.durationMs / 1000.0
            return results
                .filter { !it.preview.isNullOrBlank() }
                .filter { track.durationMs <= 0 || abs(it.duration - seconds) <= MAX_DURATION_DIFF_SECONDS }
                .minByOrNull { abs(it.duration - seconds) }
                ?.preview
        }
    }

    private companion object {
        const val MAX_DURATION_DIFF_SECONDS = 6.0
    }
}
