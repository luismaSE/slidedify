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
    data class Decided(val track: Track, val decision: Decision) : HistoryEntry

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
) {
    val current: Track? get() = tracks.getOrNull(position)
    val upcoming: List<Track> get() = tracks.drop(position + 1)
    val isFinished: Boolean get() = position >= tracks.size
    val canUndo: Boolean get() = history.isNotEmpty()

    fun isLiked(track: Track): Boolean = track.id in liked

    fun decide(decision: Decision): SessionUpdate {
        val track = current ?: return SessionUpdate(this, emptyList())
        val effects = when (decision) {
            Decision.Skip -> emptyList()
            is Decision.AddTo -> listOf(LibraryEffect.AddToPlaylists(track, decision.playlists))
        }
        val next = copy(position = position + 1, history = history + HistoryEntry.Decided(track, decision))
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
                SessionUpdate(copy(position = position - 1, history = rest), effects)
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
}
