package com.slidedify.app.di

import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.transfer.PlaylistTransfer
import com.slidedify.core.platform.ProviderSignIn
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

/**
 * Collects every provider contributed by the `:provider:*` modules on the classpath.
 * The app never references a concrete provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    @Provides
    @Singleton
    fun providerRegistry(providers: Set<@JvmSuppressWildcards MusicProvider>): ProviderRegistry =
        // Prefer a real music service over the demo one when both are available.
        ProviderRegistry(providers, preferred = providers.firstOrNull { it.id != DEMO_ID }?.id)

    private val DEMO_ID = com.slidedify.core.model.ProviderId("demo")

    /** Cross-service copy/move/sync; writes go through the offline outbox. */
    @Provides
    @Singleton
    fun playlistTransfer(registry: ProviderRegistry, library: LibraryRepository): PlaylistTransfer =
        PlaylistTransfer(registry, library)
}

/** Declares the sets so they exist even when no module contributes to them. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MultibindingDeclarations {
    @Multibinds
    abstract fun providers(): Set<MusicProvider>

    @Multibinds
    abstract fun signIns(): Set<ProviderSignIn>
}
