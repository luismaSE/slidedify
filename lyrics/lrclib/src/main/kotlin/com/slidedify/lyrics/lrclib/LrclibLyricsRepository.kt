package com.slidedify.lyrics.lrclib

import com.slidedify.core.domain.lyrics.Lyrics
import com.slidedify.core.domain.lyrics.LyricsRepository
import com.slidedify.core.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.abs

@Serializable
internal data class LrclibTrack(
    val id: Long,
    val trackName: String = "",
    val artistName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * Lyrics from LRCLIB (https://lrclib.net): free, no API key, synced lyrics when available.
 * Lookup is by title/artist/duration, so it works for tracks from any music provider.
 *
 * Network errors return `null` (lyrics are optional, never block the sorting flow).
 */
class LrclibLyricsRepository(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://lrclib.net/".toHttpUrl(),
    private val userAgent: String = "Slidedify (https://github.com/luismaSE/slidedify)",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LyricsRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lyricsFor(track: Track): Lyrics? = withContext(dispatcher) {
        try {
            val match = exactMatch(track) ?: bestSearchMatch(track)
            match?.toLyrics()
        } catch (e: IOException) {
            null
        }
    }

    private fun exactMatch(track: Track): LrclibTrack? {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/get")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artists.firstOrNull()?.name.orEmpty())
            .addQueryParameter("album_name", track.album.name)
            .addQueryParameter("duration", (track.durationMs / 1000).toString())
            .build()
        val body = get(url) ?: return null
        return json.decodeFromString(LrclibTrack.serializer(), body)
    }

    private fun bestSearchMatch(track: Track): LrclibTrack? {
        val url = baseUrl.newBuilder()
            .addPathSegments("api/search")
            .addQueryParameter("track_name", track.title)
            .addQueryParameter("artist_name", track.artists.firstOrNull()?.name.orEmpty())
            .build()
        val body = get(url) ?: return null
        val results = json.decodeFromString(ListSerializer(LrclibTrack.serializer()), body)
        val durationSeconds = track.durationMs / 1000.0
        return results
            .filter { abs(it.duration - durationSeconds) <= MAX_DURATION_DIFF_SECONDS }
            .minByOrNull { abs(it.duration - durationSeconds) }
    }

    /** Body of a successful response, `null` on 404, exception on other errors. */
    private fun get(url: HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            return when {
                response.isSuccessful -> response.body.string()
                response.code == 404 -> null
                else -> throw IOException("LRCLIB HTTP ${response.code}")
            }
        }
    }

    private fun LrclibTrack.toLyrics(): Lyrics? = when {
        instrumental -> null
        !syncedLyrics.isNullOrBlank() -> LrcParser.parseSynced(syncedLyrics)
        !plainLyrics.isNullOrBlank() -> LrcParser.parsePlain(plainLyrics)
        else -> null
    }

    private companion object {
        const val MAX_DURATION_DIFF_SECONDS = 3.0
    }
}

/** Keeps the last [maxEntries] results (including "no lyrics") so swiping back and forth is free. */
class CachingLyricsRepository(
    private val delegate: LyricsRepository,
    private val maxEntries: Int = 64,
) : LyricsRepository {
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<Pair<String, String>, Lyrics?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Lyrics?>) = size > maxEntries
    }

    override suspend fun lyricsFor(track: Track): Lyrics? {
        val key = track.provider.value to track.id.value
        mutex.withLock { if (cache.containsKey(key)) return cache[key] }
        val lyrics = delegate.lyricsFor(track)
        mutex.withLock { cache[key] = lyrics }
        return lyrics
    }
}
