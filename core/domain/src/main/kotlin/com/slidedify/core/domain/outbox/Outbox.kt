package com.slidedify.core.domain.outbox

import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.Flow

/**
 * Remote write API that each provider implements. Calls are batched by the outbox, so they
 * receive lists of tracks (never more than [com.slidedify.core.domain.provider.ProviderCapabilities.maxTracksPerWrite]).
 */
interface LibraryWriter {
    suspend fun addToPlaylist(playlist: PlaylistId, tracks: List<TrackId>)

    suspend fun removeFromPlaylist(playlist: PlaylistId, tracks: List<TrackId>)

    suspend fun setLiked(tracks: List<TrackId>, liked: Boolean)

    /**
     * Tracks currently in [playlist], used to avoid adding duplicates (services like Spotify accept
     * the same track twice). `null` = this service can't tell, so nothing is filtered.
     */
    suspend fun playlistTrackIds(playlist: PlaylistId): Set<TrackId>? = null
}

/** A single pending write against one provider. */
sealed interface WriteOp {
    val provider: ProviderId
    val track: TrackId

    data class AddToPlaylist(override val provider: ProviderId, val playlist: PlaylistId, override val track: TrackId) : WriteOp

    data class RemoveFromPlaylist(override val provider: ProviderId, val playlist: PlaylistId, override val track: TrackId) : WriteOp

    data class SetLiked(override val provider: ProviderId, override val track: TrackId, val liked: Boolean) : WriteOp
}

/** Splits a session effect into individual write operations. */
fun LibraryEffect.toWriteOps(): List<WriteOp> = when (this) {
    is LibraryEffect.AddToPlaylists -> playlists.map { WriteOp.AddToPlaylist(track.provider, it, track.id) }
    is LibraryEffect.RemoveFromPlaylists -> playlists.map { WriteOp.RemoveFromPlaylist(track.provider, it, track.id) }
    is LibraryEffect.SetLiked -> listOf(WriteOp.SetLiked(track.provider, track.id, liked))
}

/** A stored operation; [id] increases with insertion order. */
data class PendingWrite(val id: Long, val op: WriteOp)

/** Number of writes waiting to be synced, for the UI. */
interface SyncStatus {
    val pendingCount: Flow<Int>
}

/** Persistence for pending writes (Room in the app, in-memory in tests). */
interface OutboxStore : SyncStatus {
    suspend fun enqueue(ops: List<WriteOp>)

    /** Oldest first. */
    suspend fun pending(): List<PendingWrite>

    suspend fun delete(ids: Collection<Long>)
}

/** Asks the platform to run [OutboxProcessor] soon (WorkManager in the app). */
fun interface SyncScheduler {
    fun schedule()
}
