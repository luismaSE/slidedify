package com.slidedify.core.domain.suggest

import com.slidedify.core.model.Track
import java.text.Normalizer

/**
 * What is known about how a track sounds. Every field is optional: each [FeatureSource] fills
 * what it can (tags from Last.fm, tempo from Deezer, energy from analysing the preview audio).
 */
data class TrackFeatures(
    /** Style/mood tags with a weight in 0..1 ("chill" to 1.0, "acoustic" to 0.4…). */
    val tags: Map<String, Float> = emptyMap(),
    val bpm: Float? = null,
    /** 0 calm … 1 intense, measured from the audio. */
    val audioEnergy: Float? = null,
    /** 0 soft/dark … 1 bright/harsh, measured from the audio. */
    val brightness: Float? = null,
    /** 0 quiet … 1 loud master, from the service's loudness data. */
    val loudness: Float? = null,
    /** Sources that already looked at this track (even if they found nothing). */
    val analyzedBy: Set<String> = emptySet(),
) {
    /** Combined energy estimate from every signal available, or null when nothing is known. */
    val energy: Float?
        get() {
            val parts = buildList {
                audioEnergy?.let { add(it to 0.5f) }
                TagVocabulary.energyOf(tags)?.let { add(it to 0.35f) }
                bpm?.let { add(TagVocabulary.energyOfBpm(it) to 0.1f) }
                loudness?.let { add(it to 0.05f) }
            }
            if (parts.isEmpty()) return null
            return parts.sumOf { (value, weight) -> (value * weight).toDouble() }.toFloat() / parts.sumOf { it.second.toDouble() }.toFloat()
        }

    val isEmpty: Boolean get() = tags.isEmpty() && bpm == null && audioEnergy == null && brightness == null && loudness == null

    /** Union of both; for single values the receiver wins. */
    fun merge(other: TrackFeatures) = TrackFeatures(
        tags = (tags.keys + other.tags.keys).associateWith { maxOf(tags[it] ?: 0f, other.tags[it] ?: 0f) },
        bpm = bpm ?: other.bpm,
        audioEnergy = audioEnergy ?: other.audioEnergy,
        brightness = brightness ?: other.brightness,
        loudness = loudness ?: other.loudness,
        analyzedBy = analyzedBy + other.analyzedBy,
    )
}

/** Something that can describe a track: a tag service, a catalog with tempo data, audio analysis. */
interface FeatureSource {
    /** Stable id, remembered in [TrackFeatures.analyzedBy] so a source runs once per track. */
    val id: String

    /** Expensive sources (downloading audio) run for fewer tracks. */
    val isExpensive: Boolean get() = false

    /**
     * Features found for [track]; an empty [TrackFeatures] when the source knows nothing about it,
     * null on a temporary failure (network down) so it is retried later.
     */
    suspend fun features(track: Track): TrackFeatures?
}

/** Persistent store of features, so each track is analysed once. */
interface FeatureCache {
    suspend fun get(key: String): TrackFeatures?

    suspend fun put(key: String, features: TrackFeatures)
}

class InMemoryFeatureCache : FeatureCache {
    private val map = java.util.concurrent.ConcurrentHashMap<String, TrackFeatures>()

    override suspend fun get(key: String): TrackFeatures? = map[key]

    override suspend fun put(key: String, features: TrackFeatures) {
        map[key] = features
    }
}

/** Same song on any service gets the same key: main artist + title without "- Remastered" style suffixes. */
val Track.featureKey: String
    get() = normalizeText(artists.firstOrNull()?.name.orEmpty()) + "|" +
        normalizeText(title.substringBefore(" - ").substringBefore(" (").substringBefore(" ["))

/** Lowercase, no accents, single spaces. */
fun normalizeText(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9ñ+& -]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** How tags relate to energy, and which tags are just noise. */
object TagVocabulary {
    private val high = mapOf(
        "energetic" to 0.95f, "workout" to 0.95f, "gym" to 0.95f, "hardcore" to 1f, "metal" to 0.95f,
        "heavy metal" to 1f, "punk" to 0.9f, "hard rock" to 0.85f, "edm" to 0.9f, "electro" to 0.8f,
        "techno" to 0.85f, "house" to 0.75f, "trance" to 0.8f, "drum and bass" to 0.95f, "dubstep" to 0.95f,
        "dance" to 0.8f, "party" to 0.85f, "upbeat" to 0.8f, "hype" to 0.9f, "aggressive" to 1f,
        "rock" to 0.7f, "hip-hop" to 0.65f, "rap" to 0.7f, "trap" to 0.75f, "reggaeton" to 0.75f,
        "pop" to 0.6f, "funk" to 0.7f, "running" to 0.9f, "power" to 0.85f, "fast" to 0.85f,
        "cumbia" to 0.7f, "latin" to 0.65f, "ska" to 0.8f, "grunge" to 0.75f, "alternative rock" to 0.65f,
    )
    private val low = mapOf(
        "chill" to 0.2f, "chillout" to 0.2f, "calm" to 0.1f, "relax" to 0.1f, "relaxing" to 0.1f,
        "ambient" to 0.1f, "acoustic" to 0.3f, "sleep" to 0.05f, "mellow" to 0.2f, "soft" to 0.2f,
        "sad" to 0.25f, "melancholic" to 0.25f, "melancholy" to 0.25f, "piano" to 0.2f, "lo-fi" to 0.2f,
        "lofi" to 0.2f, "jazz" to 0.35f, "classical" to 0.25f, "folk" to 0.35f, "singer-songwriter" to 0.3f,
        "ballad" to 0.25f, "downtempo" to 0.2f, "soul" to 0.4f, "bossa nova" to 0.25f, "instrumental" to 0.35f,
        "dreamy" to 0.25f, "slow" to 0.15f, "romantic" to 0.3f, "love" to 0.35f, "study" to 0.15f,
        "focus" to 0.15f, "meditation" to 0.05f, "indie folk" to 0.3f, "r&b" to 0.45f, "trip-hop" to 0.3f,
    )
    private val synonyms = mapOf(
        "hip hop" to "hip-hop", "chill out" to "chillout", "lo fi" to "lo-fi", "electronic dance music" to "edm",
        "rnb" to "r&b", "rhythm and blues" to "r&b", "relaxation" to "relax", "chillhop" to "lo-fi",
        "work out" to "workout", "electronica" to "electro", "reggaeton" to "reggaeton", "reguetón" to "reggaeton",
    )
    private val noise = setOf(
        "seen live", "favorite", "favorites", "favourite", "favourites", "love it", "awesome", "my favorite",
        "beautiful", "amazing", "best", "good", "great", "cool", "under 2000 listeners", "spotify", "albums i own",
    )

    /** Lowercased, synonyms folded, noise dropped; null for tags not worth keeping. */
    fun clean(tag: String): String? {
        val t = normalizeText(tag).let { synonyms[it] ?: it }
        if (t.isBlank() || t in noise || t.length > 30 || t.all { it.isDigit() }) return null
        return t
    }

    fun energyOfTag(tag: String): Float? = high[tag] ?: low[tag]

    /** Weighted energy of the tags that carry it; null if none does. */
    fun energyOf(tags: Map<String, Float>): Float? {
        val known = tags.mapNotNull { (tag, weight) -> energyOfTag(tag)?.let { it to weight } }
        if (known.isEmpty()) return null
        val total = known.sumOf { it.second.toDouble() }
        if (total <= 0) return null
        return (known.sumOf { (energy, weight) -> (energy * weight).toDouble() } / total).toFloat()
    }

    /** Loose mapping: slow songs are calmer. Half/double tempo makes this a weak signal. */
    fun energyOfBpm(bpm: Float): Float = ((bpm - 70f) / 80f).coerceIn(0f, 1f)
}
