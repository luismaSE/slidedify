package com.slidedify.core.domain.session

import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId

/** What the user decided for a track. Both options move on to the next track. */
sealed interface Decision {
    data object Skip : Decision

    data class AddTo(val playlists: Set<PlaylistId>) : Decision {
        init {
            require(playlists.isNotEmpty()) { "AddTo needs at least one playlist" }
        }
    }
}

/** Undoable things that happened during a session. */
sealed interface HistoryEntry {
    /**
     * [fromIndex] is where the track was in the queue (undo goes back there); [wasSorted] tells
     * whether it had already been reviewed before (re-reviewing from the list), so undo keeps it.
     */
    data class Decided(val track: Track, val decision: Decision, val fromIndex: Int, val wasSorted: Boolean) : HistoryEntry

    data class LikeToggled(val track: Track, val liked: Boolean) : HistoryEntry
}

/** Changes to apply to the user's Spotify library. */
sealed interface LibraryEffect {
    data class AddToPlaylists(val track: Track, val playlists: Set<PlaylistId>) : LibraryEffect

    data class RemoveFromPlaylists(val track: Track, val playlists: Set<PlaylistId>) : LibraryEffect

    data class SetLiked(val track: Track, val liked: Boolean) : LibraryEffect
}

/** Result of a session transition: the new session plus what must be written to Spotify. */
data class SessionUpdate(val session: SortSession, val effects: List<LibraryEffect>)

/**
 * Immutable sorting session: a queue of tracks, a cursor and an undo history.
 * Every transition returns the [LibraryEffect]s to apply, so the caller decides how/when to sync.
 */
data class SortSession(
    val tracks: List<Track>,
    val position: Int = 0,
    val liked: Set<TrackId> = emptySet(),
    val history: List<HistoryEntry> = emptyList(),
    /** Tracks already reviewed (added or skipped), in this or previous sessions. */
    val sorted: Set<TrackId> = emptySet(),
) {
    val current: Track? get() = tracks.getOrNull(position)
    val upcoming: List<Track> get() = tracks.drop(position + 1)
    val isFinished: Boolean get() = position >= tracks.size
    val canUndo: Boolean get() = history.isNotEmpty()
    val sortedCount: Int get() = tracks.count { it.id in sorted }

    fun isLiked(track: Track): Boolean = track.id in liked

    fun isSorted(track: Track): Boolean = track.id in sorted

    /**
     * Adds tracks loaded later (next pages), skipping duplicates. If the session was waiting at the
     * end, it moves on to the first pending track of the new page.
     */
    fun append(more: List<Track>): SortSession {
        val known = tracks.mapTo(HashSet()) { it.id }
        val fresh = more.filter { known.add(it.id) }
        if (fresh.isEmpty()) return this
        val grown = copy(tracks = tracks + fresh)
        return if (isFinished) grown.copy(position = grown.nextPendingFrom(position)) else grown
    }

    /** Starts at the first track not reviewed yet (resume where the user left off). */
    fun startAtFirstPending(): SortSession = copy(position = nextPendingFrom(0))

    /** Jump to any track from the list, reviewed or not. */
    fun jumpTo(index: Int): SortSession = if (index in tracks.indices) copy(position = index) else this

    /**
     * Same tracks in another order (by date, title…). The current track stays current, so the
     * user keeps their place; what comes next follows the new order.
     */
    fun reordered(comparator: Comparator<Track>): SortSession {
        val now = current
        val ordered = tracks.sortedWith(comparator)
        val moved = copy(tracks = ordered)
        val index = now?.let { track -> ordered.indexOfFirst { it.id == track.id } } ?: -1
        return if (index >= 0) moved.copy(position = index) else moved.copy(position = moved.nextPendingFrom(0))
    }

    fun decide(decision: Decision): SessionUpdate {
        val track = current ?: return SessionUpdate(this, emptyList())
        val effects = when (decision) {
            Decision.Skip -> emptyList()
            is Decision.AddTo -> listOf(LibraryEffect.AddToPlaylists(track, decision.playlists))
        }
        val nowSorted = sorted + track.id
        val next = copy(
            sorted = nowSorted,
            position = nextPendingAfter(position, nowSorted),
            history = history + HistoryEntry.Decided(track, decision, fromIndex = position, wasSorted = track.id in sorted),
        )
        return SessionUpdate(next, effects)
    }

    /** Double tap: toggles "liked" on the current track without leaving it. */
    fun toggleLike(): SessionUpdate {
        val track = current ?: return SessionUpdate(this, emptyList())
        val nowLiked = track.id !in liked
        val next = copy(
            liked = if (nowLiked) liked + track.id else liked - track.id,
            history = history + HistoryEntry.LikeToggled(track, nowLiked),
        )
        return SessionUpdate(next, listOf(LibraryEffect.SetLiked(track, nowLiked)))
    }

    /** Reverts the last entry and returns the compensating effects. */
    fun undo(): SessionUpdate {
        val last = history.lastOrNull() ?: return SessionUpdate(this, emptyList())
        val rest = history.dropLast(1)
        return when (last) {
            is HistoryEntry.Decided -> {
                val effects = when (val decision = last.decision) {
                    Decision.Skip -> emptyList()
                    is Decision.AddTo -> listOf(LibraryEffect.RemoveFromPlaylists(last.track, decision.playlists))
                }
                val restoredSorted = if (last.wasSorted) sorted else sorted - last.track.id
                // Find the track itself: the queue may have been reordered since.
                val back = tracks.indexOfFirst { it.id == last.track.id }.takeIf { it >= 0 } ?: last.fromIndex
                SessionUpdate(copy(position = back, history = rest, sorted = restoredSorted), effects)
            }

            is HistoryEntry.LikeToggled -> {
                val restored = !last.liked
                val likedSet = if (restored) liked + last.track.id else liked - last.track.id
                SessionUpdate(
                    copy(liked = likedSet, history = rest),
                    listOf(LibraryEffect.SetLiked(last.track, restored)),
                )
            }
        }
    }

    /**
     * Next pending track after [index]; when there is none further down (for example after
     * jumping ahead from the list), it goes back to the first one still pending earlier.
     */
    private fun nextPendingAfter(index: Int, sortedSet: Set<TrackId>): Int {
        val ahead = nextPendingFrom(index + 1, sortedSet)
        if (ahead < tracks.size) return ahead
        val earlier = nextPendingFrom(0, sortedSet)
        return if (earlier < index) earlier else tracks.size
    }

    /** First index >= [from] whose track isn't reviewed; `tracks.size` when there is none. */
    private fun nextPendingFrom(from: Int, sortedSet: Set<TrackId> = sorted): Int {
        var index = from.coerceAtLeast(0)
        while (index < tracks.size && tracks[index].id in sortedSet) index++
        return index
    }
}
