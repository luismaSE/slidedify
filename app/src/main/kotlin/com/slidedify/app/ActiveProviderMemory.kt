package com.slidedify.app

import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Reopens the app on the service the user picked last, and remembers every change. */
@Singleton
class ActiveProviderMemory @Inject constructor(
    private val registry: ProviderRegistry,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            runCatching {
                settings.settings.first().lastProvider?.let { id -> if (registry.find(id) != null) registry.select(id) }
            }
            registry.active.collect { provider ->
                runCatching { settings.update { it.copy(lastProvider = provider.id) } }
            }
        }
    }
}
