package com.slidedify.provider.fake

import com.slidedify.core.domain.provider.MusicProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Registers the demo provider. Every `:provider:*` module has a module like this one. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FakeProviderModule {
    @Binds
    @IntoSet
    abstract fun bindProvider(impl: FakeMusicProvider): MusicProvider
}
