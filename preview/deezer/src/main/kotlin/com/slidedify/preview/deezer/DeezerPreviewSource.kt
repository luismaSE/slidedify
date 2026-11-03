package com.slidedify.preview.deezer

import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.domain.suggest.FeatureSource
import com.slidedify.core.domain.suggest.TrackFeatures
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
    val id: Long = 0,
    val title: String = "",
    /** Seconds. */
    val duration: Int = 0,
    val preview: String? = null,
    val artist: DeezerArtist = DeezerArtist(),
    /** Only in the full track object (`/track/{id}`); 0 when unknown. */
    val bpm: Float = 0f,
    /** ReplayGain-style loudness in dB (more negative = louder master); only in the full object. */
    val gain: Float = 0f,
)

@Serializable
internal data class DeezerSearch(val data: List<DeezerTrack> = emptyList())

/** Finds a song in Deezer's public catalog (no key needed), matching artist, title and duration. */
internal class DeezerCatalog(private val client: OkHttpClient, private val baseUrl: HttpUrl) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blocking; call from an IO dispatcher. Throws IOException on network errors. */
    fun match(track: Track): DeezerTrack? {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        // Strict query first ("Song - Remastered 2011" style suffixes are cut), then a loose one.
        val title = track.title.substringBefore(" - ").substringBefore(" (").trim()
        return search("artist:\"$artist\" track:\"$title\"", track) ?: search("$artist $title", track)
    }

    fun details(id: Long): DeezerTrack? {
        val url = baseUrl.newBuilder().addPathSegment("track").addPathSegment(id.toString()).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Deezer ${response.code}")
            return json.decodeFromString(DeezerTrack.serializer(), response.body.string()).takeIf { it.id != 0L }
        }
    }

    private fun search(query: String, track: Track): DeezerTrack? {
        val url = baseUrl.newBuilder().addPathSegment("search").addQueryParameter("q", query).addQueryParameter("limit", "10").build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val results = json.decodeFromString(DeezerSearch.serializer(), response.body.string()).data
            val seconds = track.durationMs / 1000.0
            return results
                .filter { !it.preview.isNullOrBlank() }
                .filter { track.durationMs <= 0 || abs(it.duration - seconds) <= MAX_DURATION_DIFF_SECONDS }
                .minByOrNull { abs(it.duration - seconds) }
        }
    }

    private companion object {
        const val MAX_DURATION_DIFF_SECONDS = 6.0
    }
}

/**
 * 30-second previews from Deezer's public search API (no key needed). Spotify no longer gives
 * preview URLs to new apps, so this is the fallback when full playback isn't possible.
 * Picks the result whose duration is closest to the track's; null when nothing plausible matches.
 */
class DeezerPreviewSource(
    client: OkHttpClient,
    baseUrl: HttpUrl = "https://api.deezer.com/".toHttpUrl(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PreviewSource {
    private val catalog = DeezerCatalog(client, baseUrl)

    override suspend fun previewUrl(track: Track): String? = withContext(dispatcher) {
        try {
            catalog.match(track)?.preview
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }
}

/** Tempo and loudness from Deezer's track data, for suggestions. */
class DeezerFeatureSource(
    client: OkHttpClient,
    baseUrl: HttpUrl = "https://api.deezer.com/".toHttpUrl(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FeatureSource {
    override val id = "deezer"

    private val catalog = DeezerCatalog(client, baseUrl)

    override suspend fun features(track: Track): TrackFeatures? = withContext(dispatcher) {
        try {
            val found = catalog.match(track) ?: return@withContext TrackFeatures()
            val details = catalog.details(found.id) ?: return@withContext TrackFeatures()
            TrackFeatures(
                bpm = details.bpm.takeIf { it in 40f..250f },
                // Typical masters sit between -4 dB (quiet) and -14 dB (very loud).
                loudness = details.gain.takeIf { it != 0f }?.let { ((-it - 4f) / 10f).coerceIn(0f, 1f) },
            )
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }
}
