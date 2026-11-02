package com.slidedify.core.domain.settings

import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.repository.key
import com.slidedify.core.model.ProviderId
import kotlinx.coroutines.flow.Flow

enum class ButtonMode {
    /** On when a screen reader (TalkBack) is active. */
    Auto,
    On,
    Off,
}

/** User preferences for the sorting screen. Gesture maps are stored per provider. */
data class SortSettings(
    val gestureMaps: Map<ProviderId, GestureMap> = emptyMap(),
    val holdTimeoutMs: Long = DEFAULT_HOLD_TIMEOUT_MS,
    val leftHanded: Boolean = false,
    val haptics: Boolean = true,
    val autoplay: Boolean = true,
    val buttonMode: ButtonMode = ButtonMode.Auto,
    val tutorialCompleted: Boolean = false,
    /** Sources opened lately, newest first (all providers mixed). */
    val recent: List<RecentSource> = emptyList(),
    /** Service the user picked last, restored on the next launch. */
    val lastProvider: ProviderId? = null,
) {
    /**
     * Gesture map for [provider], keeping only playlists that still exist.
     * Without a saved map it starts with a single destination (the first playlist): half the
     * circle for it, half for cancel. The user adds more from the main screen.
     */
    fun gestureMapFor(provider: ProviderId, playlists: List<Playlist>): GestureMap {
        val saved = gestureMaps[provider]
            ?: return playlists.firstOrNull()?.let { GestureMap.of(it.id) } ?: GestureMap.Empty
        return saved.retainOnly(playlists.mapTo(mutableSetOf()) { it.id })
    }

    fun withGestureMap(provider: ProviderId, map: GestureMap): SortSettings =
        copy(gestureMaps = gestureMaps + (provider to map))

    /**
     * Puts [entry] first in [recent], replacing the older entry of the same source. A missing
     * total or cover keeps the one known from before.
     */
    fun withRecent(entry: RecentSource): SortSettings {
        val previous = recent.firstOrNull { it.sameSourceAs(entry) }
        val merged = entry.copy(
            total = entry.total ?: previous?.total,
            imageUrl = entry.imageUrl ?: previous?.imageUrl,
        )
        return copy(recent = (listOf(merged) + recent.filterNot { it.sameSourceAs(entry) }).take(MAX_RECENT))
    }

    fun withHoldTimeout(ms: Long): SortSettings = copy(holdTimeoutMs = ms.coerceIn(HOLD_TIMEOUT_RANGE))

    companion object {
        const val DEFAULT_HOLD_TIMEOUT_MS = 300L
        val HOLD_TIMEOUT_RANGE = 200L..600L
        const val MAX_RECENT = 12
    }
}

/** A source the user sorted lately, to offer "continue where you left off". */
data class RecentSource(
    val provider: ProviderId,
    val source: TrackSource,
    val title: String,
    val imageUrl: String? = null,
    /** Songs in the source, once it was fully loaded. */
    val total: Int? = null,
    val openedAt: Long = 0,
) {
    fun sameSourceAs(other: RecentSource) = provider == other.provider && source.key == other.source.key
}

interface SettingsRepository {
    val settings: Flow<SortSettings>

    suspend fun update(transform: (SortSettings) -> SortSettings)
}
