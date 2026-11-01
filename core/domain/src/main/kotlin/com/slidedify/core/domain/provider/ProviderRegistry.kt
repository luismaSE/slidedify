package com.slidedify.core.domain.provider

import com.slidedify.core.model.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All providers available in this build and the one currently in use.
 * Providers are discovered through DI (each `:provider:*` module adds itself to a set).
 */
class ProviderRegistry(
    providers: Set<MusicProvider>,
    preferred: ProviderId? = null,
) {
    val all: List<MusicProvider> = providers.sortedBy { it.displayName }

    init {
        require(all.isNotEmpty()) { "At least one MusicProvider must be registered" }
        val duplicated = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicated provider ids: $duplicated" }
    }

    private val _active = MutableStateFlow(preferred?.let(::find) ?: all.first())
    val active: StateFlow<MusicProvider> = _active.asStateFlow()

    fun find(id: ProviderId): MusicProvider? = all.firstOrNull { it.id == id }

    fun select(id: ProviderId) {
        _active.value = requireNotNull(find(id)) { "Unknown provider: ${id.value}" }
    }
}
