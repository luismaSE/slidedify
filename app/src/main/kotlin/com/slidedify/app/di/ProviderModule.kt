package com.slidedify.app.di

import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
        ProviderRegistry(providers)
}
