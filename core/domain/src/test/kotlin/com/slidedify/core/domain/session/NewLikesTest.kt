package com.slidedify.core.domain.session

import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.Album
import com.slidedify.core.model.Artist
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NewLikesTest {
    private fun track(n: Int, addedAt: Long?) =
        Track(TrackId("t$n"), ProviderId("p"), "Song $n", listOf(Artist("a", "A")), Album("al", "Al", null, null), 1000, addedAt = addedAt)

    /** Newest first, like the services, in pages of 2; counts the pages read. */
    private class Liked(val all: List<Track>) : TrackRepository {
        var pagesRead = 0

        override suspend fun tracks(source: TrackSource) = all

        override fun pages(source: TrackSource): Flow<List<Track>> = flow {
            all.chunked(2).forEach { pagesRead++; emit(it) }
        }
    }

    private val liked = Liked((10 downTo 1).map { track(it, addedAt = it * 100L) })

    @Test
    fun `songs newer than the last one sorted are new, and reading stops there`() = runTest {
        val new = NewLikes.load(liked, sorted = setOf(TrackId("t7"), TrackId("t2")), baseline = null)
        assertEquals(listOf("t10", "t9", "t8"), new.map { it.id.value })
        assertEquals(2, liked.pagesRead)
    }

    @Test
    fun `without anything sorted, the baseline limits what counts as new`() = runTest {
        val new = NewLikes.load(liked, sorted = emptySet(), baseline = 750)
        assertEquals(listOf("t10", "t9", "t8"), new.map { it.id.value })
    }

    @Test
    fun `services without dates have no new likes`() = runTest {
        val undated = Liked(listOf(track(1, addedAt = null)))
        assertEquals(emptyList(), NewLikes.load(undated, emptySet(), baseline = null))
    }
}
