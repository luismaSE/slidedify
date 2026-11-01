package com.slidedify.core.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.slidedify.core.domain.outbox.OutboxStore
import com.slidedify.core.domain.outbox.PendingWrite
import com.slidedify.core.domain.outbox.WriteOp
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val type: String,
    val trackId: String,
    val playlistId: String?,
    val liked: Boolean?,
    val createdAt: Long,
) {
    companion object {
        const val ADD = "add"
        const val REMOVE = "remove"
        const val LIKE = "like"
    }
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insertAll(rows: List<OutboxEntity>)

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>
}

@Database(entities = [OutboxEntity::class], version = 1, exportSchema = true)
abstract class SlidedifyDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
}

internal fun WriteOp.toEntity(now: Long): OutboxEntity = when (this) {
    is WriteOp.AddToPlaylist ->
        OutboxEntity(provider = provider.value, type = OutboxEntity.ADD, trackId = track.value, playlistId = playlist.value, liked = null, createdAt = now)
    is WriteOp.RemoveFromPlaylist ->
        OutboxEntity(provider = provider.value, type = OutboxEntity.REMOVE, trackId = track.value, playlistId = playlist.value, liked = null, createdAt = now)
    is WriteOp.SetLiked ->
        OutboxEntity(provider = provider.value, type = OutboxEntity.LIKE, trackId = track.value, playlistId = null, liked = liked, createdAt = now)
}

/** Unknown or incomplete rows (e.g. from a newer app version) map to `null` and are skipped. */
internal fun OutboxEntity.toPendingWrite(): PendingWrite? {
    val provider = ProviderId(provider)
    val track = TrackId(trackId)
    val op = when (type) {
        OutboxEntity.ADD -> playlistId?.let { WriteOp.AddToPlaylist(provider, PlaylistId(it), track) }
        OutboxEntity.REMOVE -> playlistId?.let { WriteOp.RemoveFromPlaylist(provider, PlaylistId(it), track) }
        OutboxEntity.LIKE -> liked?.let { WriteOp.SetLiked(provider, track, it) }
        else -> null
    }
    return op?.let { PendingWrite(id, it) }
}

@Singleton
class RoomOutboxStore @Inject constructor(private val dao: OutboxDao) : OutboxStore {
    override val pendingCount: Flow<Int> = dao.count()

    override suspend fun enqueue(ops: List<WriteOp>) {
        val now = System.currentTimeMillis()
        dao.insertAll(ops.map { it.toEntity(now) })
    }

    override suspend fun pending(): List<PendingWrite> = dao.all().mapNotNull { it.toPendingWrite() }

    override suspend fun delete(ids: Collection<Long>) {
        // SQLite limits bound parameters; delete in chunks.
        ids.chunked(500).forEach { dao.delete(it) }
    }
}
