package com.slidedify.core.domain.settings

import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Slot
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
) {
    /**
     * Gesture map for [provider], keeping only playlists that still exist.
     * Without a saved map, the first playlists are assigned in slot order.
     */
    fun gestureMapFor(provider: ProviderId, playlists: List<Playlist>): GestureMap {
        val saved = gestureMaps[provider] ?: return GestureMap.inOrder(playlists.take(Slot.entries.size))
        return saved.retainOnly(playlists.mapTo(mutableSetOf()) { it.id })
    }

    fun withGestureMap(provider: ProviderId, map: GestureMap): SortSettings =
        copy(gestureMaps = gestureMaps + (provider to map))

    fun withHoldTimeout(ms: Long): SortSettings = copy(holdTimeoutMs = ms.coerceIn(HOLD_TIMEOUT_RANGE))

    companion object {
        const val DEFAULT_HOLD_TIMEOUT_MS = 300L
        val HOLD_TIMEOUT_RANGE = 200L..600L
    }
}

interface SettingsRepository {
    val settings: Flow<SortSettings>

    suspend fun update(transform: (SortSettings) -> SortSettings)
}
