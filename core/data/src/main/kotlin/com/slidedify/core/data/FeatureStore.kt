package com.slidedify.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.slidedify.core.domain.suggest.FeatureCache
import com.slidedify.core.domain.suggest.TrackFeatures
import javax.inject.Inject
import javax.inject.Singleton

/** How a song sounds (for suggestions), keyed by artist + title so it is shared across services. */
@Entity(tableName = "track_features")
data class TrackFeaturesEntity(
    @PrimaryKey val featureKey: String,
    /** "tag=weight;tag=weight". */
    val tags: String,
    val bpm: Float?,
    val audioEnergy: Float?,
    val brightness: Float?,
    val loudness: Float?,
    /** Comma-separated source ids. */
    val analyzedBy: String,
    val updatedAt: Long,
)

@Dao
interface TrackFeaturesDao {
    @Query("SELECT * FROM track_features WHERE featureKey = :key")
    suspend fun get(key: String): TrackFeaturesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: TrackFeaturesEntity)
}

internal fun TrackFeatures.toEntity(key: String, now: Long) = TrackFeaturesEntity(
    featureKey = key,
    tags = tags.entries.joinToString(";") { (tag, weight) -> "${tag.replace(";", " ").replace("=", " ")}=$weight" },
    bpm = bpm,
    audioEnergy = audioEnergy,
    brightness = brightness,
    loudness = loudness,
    analyzedBy = analyzedBy.joinToString(","),
    updatedAt = now,
)

internal fun TrackFeaturesEntity.toFeatures() = TrackFeatures(
    tags = tags.split(';').mapNotNull { part ->
        val tag = part.substringBeforeLast('=', "")
        val weight = part.substringAfterLast('=', "").toFloatOrNull()
        if (tag.isBlank() || weight == null) null else tag to weight
    }.toMap(),
    bpm = bpm,
    audioEnergy = audioEnergy,
    brightness = brightness,
    loudness = loudness,
    analyzedBy = analyzedBy.split(',').filter { it.isNotBlank() }.toSet(),
)

@Singleton
class RoomFeatureCache @Inject constructor(private val dao: TrackFeaturesDao) : FeatureCache {
    override suspend fun get(key: String): TrackFeatures? = dao.get(key)?.toFeatures()

    override suspend fun put(key: String, features: TrackFeatures) = dao.put(features.toEntity(key, System.currentTimeMillis()))
}
