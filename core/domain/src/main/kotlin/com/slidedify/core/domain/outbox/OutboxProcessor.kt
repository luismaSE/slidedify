package com.slidedify.core.domain.outbox

import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.CancellationException

/**
 * Removes writes that cancel each other before they reach the network:
 * - "add to playlist" followed by "remove" of the same track/playlist (an undo before syncing) → both dropped;
 * - several "like" changes on the same track → only the last one is kept.
 *
 * Returns the writes to keep (in order) and the ids that can be deleted without syncing.
 */
object OutboxCompactor {
    data class Result(val keep: List<PendingWrite>, val drop: Set<Long>)

    fun compact(pending: List<PendingWrite>): Result {
        val drop = mutableSetOf<Long>()
        // Latest still-unmatched "add" per (provider, playlist, track).
        val openAdds = mutableMapOf<Triple<ProviderId, PlaylistId, TrackId>, PendingWrite>()
        val lastLike = mutableMapOf<Pair<ProviderId, TrackId>, PendingWrite>()

        for (write in pending) {
            when (val op = write.op) {
                is WriteOp.AddToPlaylist -> openAdds[Triple(op.provider, op.playlist, op.track)] = write
                is WriteOp.RemoveFromPlaylist -> {
                    val add = openAdds.remove(Triple(op.provider, op.playlist, op.track))
                    if (add != null) {
                        drop += add.id
                        drop += write.id
                    }
                }

                is WriteOp.SetLiked -> lastLike.put(op.provider to op.track, write)?.let { drop += it.id }
            }
        }
        return Result(keep = pending.filterNot { it.id in drop }, drop = drop)
    }
}

/** One network call: same provider, same kind of write, up to the provider's batch size. */
data class WriteBatch(val provider: ProviderId, val kind: Kind, val tracks: List<TrackId>, val ids: List<Long>) {
    sealed interface Kind {
        data class Add(val playlist: PlaylistId) : Kind

        data class Remove(val playlist: PlaylistId) : Kind

        data class Like(val liked: Boolean) : Kind
    }
}

object OutboxBatcher {
    /**
     * Groups consecutive writes of the same kind, keeping the original order between groups
     * (so an add and a later remove of the same track never swap).
     */
    fun batches(pending: List<PendingWrite>, maxPerBatch: (ProviderId) -> Int): List<WriteBatch> {
        val result = mutableListOf<WriteBatch>()
        for (write in pending) {
            val kind = write.op.kind()
            val last = result.lastOrNull()
            val max = maxPerBatch(write.op.provider).coerceAtLeast(1)
            if (last != null && last.provider == write.op.provider && last.kind == kind && last.tracks.size < max) {
                result[result.lastIndex] = last.copy(tracks = last.tracks + write.op.track, ids = last.ids + write.id)
            } else {
                result += WriteBatch(write.op.provider, kind, listOf(write.op.track), listOf(write.id))
            }
        }
        return result
    }

    private fun WriteOp.kind(): WriteBatch.Kind = when (this) {
        is WriteOp.AddToPlaylist -> WriteBatch.Kind.Add(playlist)
        is WriteOp.RemoveFromPlaylist -> WriteBatch.Kind.Remove(playlist)
        is WriteOp.SetLiked -> WriteBatch.Kind.Like(liked)
    }
}

/**
 * Sends pending writes to their providers. Stops at the first failure so order is preserved;
 * the caller (a background job) retries later with backoff.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val writerFor: (ProviderId) -> LibraryWriter?,
    private val maxPerBatch: (ProviderId) -> Int,
) {
    sealed interface Result {
        data class Done(val sent: Int, val discarded: Int) : Result

        data class Failed(val sent: Int, val error: Throwable) : Result
    }

    suspend fun process(): Result {
        val compacted = OutboxCompactor.compact(store.pending())
        if (compacted.drop.isNotEmpty()) store.delete(compacted.drop)

        var sent = 0
        var discarded = compacted.drop.size
        for (batch in OutboxBatcher.batches(compacted.keep, maxPerBatch)) {
            val writer = writerFor(batch.provider)
            if (writer == null) {
                // Provider no longer installed: nothing can ever send these.
                store.delete(batch.ids)
                discarded += batch.ids.size
                continue
            }
            try {
                when (val kind = batch.kind) {
                    is WriteBatch.Kind.Add -> writer.addToPlaylist(kind.playlist, batch.tracks)
                    is WriteBatch.Kind.Remove -> writer.removeFromPlaylist(kind.playlist, batch.tracks)
                    is WriteBatch.Kind.Like -> writer.setLiked(batch.tracks, kind.liked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.Failed(sent, e)
            }
            store.delete(batch.ids)
            sent += batch.ids.size
        }
        return Result.Done(sent, discarded)
    }
}

/** [LibraryRepository] that never touches the network: it stores writes and schedules a sync. */
class OutboxLibraryRepository(
    private val store: OutboxStore,
    private val scheduler: SyncScheduler,
) : LibraryRepository {
    override suspend fun apply(effect: LibraryEffect) {
        store.enqueue(effect.toWriteOps())
        scheduler.schedule()
    }
}
