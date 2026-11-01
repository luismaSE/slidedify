package com.slidedify.core.domain.outbox

import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutboxTest {

    private val spotify = ProviderId("spotify")
    private val tidal = ProviderId("tidal")
    private val gym = PlaylistId("gym")
    private val focus = PlaylistId("focus")
    private fun t(n: Int) = TrackId("t$n")

    private fun add(id: Long, track: Int, playlist: PlaylistId = gym, provider: ProviderId = spotify) =
        PendingWrite(id, WriteOp.AddToPlaylist(provider, playlist, t(track)))

    private fun remove(id: Long, track: Int, playlist: PlaylistId = gym) =
        PendingWrite(id, WriteOp.RemoveFromPlaylist(spotify, playlist, t(track)))

    private fun like(id: Long, track: Int, liked: Boolean) = PendingWrite(id, WriteOp.SetLiked(spotify, t(track), liked))

    // --- Compactor ------------------------------------------------------------------------------

    @Test
    fun `undo before sync cancels the add`() {
        val result = OutboxCompactor.compact(listOf(add(1, 1), add(2, 2), remove(3, 1)))
        assertEquals(listOf(add(2, 2)), result.keep)
        assertEquals(setOf(1L, 3L), result.drop)
    }

    @Test
    fun `remove without a pending add is kept (track was already synced)`() {
        val result = OutboxCompactor.compact(listOf(remove(1, 1)))
        assertEquals(listOf(remove(1, 1)), result.keep)
    }

    @Test
    fun `add to another playlist is not cancelled`() {
        val result = OutboxCompactor.compact(listOf(add(1, 1, gym), remove(2, 1, focus)))
        assertEquals(2, result.keep.size)
    }

    @Test
    fun `only the last like change survives`() {
        val result = OutboxCompactor.compact(listOf(like(1, 1, true), like(2, 1, false), like(3, 1, true)))
        assertEquals(listOf(like(3, 1, true)), result.keep)
    }

    // --- Batcher --------------------------------------------------------------------------------

    @Test
    fun `consecutive adds to the same playlist are grouped up to the limit`() {
        val writes = (1..5).map { add(it.toLong(), it) }
        val batches = OutboxBatcher.batches(writes) { 2 }
        assertEquals(listOf(2, 2, 1), batches.map { it.tracks.size })
        assertTrue(batches.all { it.kind == WriteBatch.Kind.Add(gym) })
    }

    @Test
    fun `different playlists and providers make separate batches in order`() {
        val writes = listOf(add(1, 1, gym), add(2, 2, focus), add(3, 3, gym, tidal))
        val batches = OutboxBatcher.batches(writes) { 100 }
        assertEquals(
            listOf(spotify to WriteBatch.Kind.Add(gym), spotify to WriteBatch.Kind.Add(focus), tidal to WriteBatch.Kind.Add(gym)),
            batches.map { it.provider to it.kind },
        )
    }

    // --- Processor ------------------------------------------------------------------------------

    private class MemoryStore(initial: List<WriteOp> = emptyList()) : OutboxStore {
        private var nextId = 1L
        val rows = mutableListOf<PendingWrite>()
        override val pendingCount: Flow<Int> = MutableStateFlow(0)

        init {
            initial.forEach { rows += PendingWrite(nextId++, it) }
        }

        override suspend fun enqueue(ops: List<WriteOp>) {
            ops.forEach { rows += PendingWrite(nextId++, it) }
        }

        override suspend fun pending() = rows.toList()

        override suspend fun delete(ids: Collection<Long>) {
            rows.removeAll { it.id in ids }
        }
    }

    private class RecordingWriter(private val failOnCall: Int = -1) : LibraryWriter {
        val calls = mutableListOf<String>()

        private fun record(call: String) {
            if (calls.size == failOnCall) error("network down")
            calls += call
        }

        override suspend fun addToPlaylist(playlist: PlaylistId, tracks: List<TrackId>) =
            record("add ${playlist.value} ${tracks.map { it.value }}")

        override suspend fun removeFromPlaylist(playlist: PlaylistId, tracks: List<TrackId>) =
            record("remove ${playlist.value} ${tracks.map { it.value }}")

        override suspend fun setLiked(tracks: List<TrackId>, liked: Boolean) =
            record("like=$liked ${tracks.map { it.value }}")
    }

    @Test
    fun `processor sends batches and empties the store`() = runTest {
        val store = MemoryStore(
            listOf(
                WriteOp.AddToPlaylist(spotify, gym, t(1)),
                WriteOp.AddToPlaylist(spotify, gym, t(2)),
                WriteOp.SetLiked(spotify, t(3), true),
                WriteOp.AddToPlaylist(spotify, focus, t(4)),
                WriteOp.RemoveFromPlaylist(spotify, focus, t(4)), // undone before sync
            ),
        )
        val writer = RecordingWriter()
        val result = OutboxProcessor(store, { writer }, { 100 }).process()

        assertEquals(OutboxProcessor.Result.Done(sent = 3, discarded = 2), result)
        assertEquals(listOf("add gym [t1, t2]", "like=true [t3]"), writer.calls)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `failure keeps the remaining writes for a retry`() = runTest {
        val store = MemoryStore(
            listOf(WriteOp.AddToPlaylist(spotify, gym, t(1)), WriteOp.AddToPlaylist(spotify, focus, t(2))),
        )
        val writer = RecordingWriter(failOnCall = 1)
        val result = OutboxProcessor(store, { writer }, { 100 }).process()

        assertIs<OutboxProcessor.Result.Failed>(result)
        assertEquals(listOf(WriteOp.AddToPlaylist(spotify, focus, t(2))), store.rows.map { it.op })
    }

    @Test
    fun `writes for an uninstalled provider are discarded`() = runTest {
        val store = MemoryStore(listOf(WriteOp.AddToPlaylist(tidal, gym, t(1))))
        val result = OutboxProcessor(store, { null }, { 100 }).process()
        assertEquals(OutboxProcessor.Result.Done(sent = 0, discarded = 1), result)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `repository enqueues one op per playlist and schedules a sync`() = runTest {
        val store = MemoryStore()
        var scheduled = 0
        val repository = OutboxLibraryRepository(store) { scheduled++ }
        val track = com.slidedify.core.model.Track(
            id = t(1),
            provider = spotify,
            title = "Song",
            artists = emptyList(),
            album = com.slidedify.core.model.Album("a", "Album", null, null),
            durationMs = 1000,
        )
        repository.apply(com.slidedify.core.domain.session.LibraryEffect.AddToPlaylists(track, setOf(gym, focus)))
        assertEquals(2, store.rows.size)
        assertEquals(1, scheduled)
    }
}
