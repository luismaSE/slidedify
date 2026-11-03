package com.slidedify.suggest.lastfm

import com.slidedify.core.domain.suggest.FeatureSource
import com.slidedify.core.domain.suggest.TagVocabulary
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
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class LastFmTag(val name: String = "", val count: Int = 0)

@Serializable
internal data class LastFmTagList(val tag: List<LastFmTag> = emptyList())

@Serializable
internal data class LastFmTopTags(val toptags: LastFmTagList? = null, val error: Int? = null)

/**
 * Style and mood tags from Last.fm ("chill", "workout", "acoustic"…), written by its listeners.
 * Uses the song's tags, topped up with the artist's when the song has few. Needs a free API key.
 */
class LastFmTagSource(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: HttpUrl = "https://ws.audioscrobbler.com/2.0/".toHttpUrl(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FeatureSource {
    override val id = "lastfm"

    private val json = Json { ignoreUnknownKeys = true }
    private val artistTags = ConcurrentHashMap<String, Map<String, Float>>()

    override suspend fun features(track: Track): TrackFeatures? = withContext(dispatcher) {
        try {
            val artist = track.artists.firstOrNull()?.name ?: return@withContext TrackFeatures()
            val title = track.title.substringBefore(" - ").substringBefore(" (").trim()
            val songTags = topTags("track.gettoptags", "artist" to artist, "track" to title) ?: return@withContext null
            val tags = if (songTags.size >= MIN_SONG_TAGS) {
                songTags
            } else {
                val ofArtist = artistTags[artist.lowercase()]
                    ?: topTags("artist.gettoptags", "artist" to artist)?.also { artistTags[artist.lowercase()] = it }
                    ?: return@withContext null
                // The song's own tags matter more than the artist's.
                (songTags.keys + ofArtist.keys).associateWith { maxOf(songTags[it] ?: 0f, (ofArtist[it] ?: 0f) * ARTIST_WEIGHT) }
            }
            TrackFeatures(tags = tags.entries.sortedByDescending { it.value }.take(MAX_TAGS).associate { it.key to it.value })
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }

    /** Cleaned tags weighted 0..1 by their count; empty when not found; null on a temporary error. */
    private fun topTags(method: String, vararg params: Pair<String, String>): Map<String, Float>? {
        val url = baseUrl.newBuilder()
            .addQueryParameter("method", method)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("autocorrect", "1")
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("format", "json")
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code >= 500 || response.code == 429) return null
            val body = json.decodeFromString(LastFmTopTags.serializer(), response.body.string())
            when (body.error) {
                null -> Unit
                ERROR_NOT_FOUND -> return emptyMap()
                else -> return null // Rate limit, bad key, service down: try again another time.
            }
            val tags = body.toptags?.tag.orEmpty()
                .filter { it.count >= MIN_COUNT }
                .mapNotNull { tag -> TagVocabulary.clean(tag.name)?.let { it to tag.count } }
            val max = tags.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: return emptyMap()
            return tags.groupBy({ it.first }, { it.second }).mapValues { (_, counts) -> counts.max().toFloat() / max }
        }
    }

    private companion object {
        const val ERROR_NOT_FOUND = 6
        const val MIN_COUNT = 5
        const val MIN_SONG_TAGS = 3
        const val MAX_TAGS = 12
        const val ARTIST_WEIGHT = 0.6f
    }
}
