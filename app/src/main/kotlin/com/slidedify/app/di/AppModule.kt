package com.slidedify.app.di

import com.slidedify.app.data.fake.FakePlayer
import com.slidedify.app.data.fake.FakePlaylistRepository
import com.slidedify.app.data.fake.FakeTrackRepository
import com.slidedify.app.data.fake.InMemoryLibraryRepository
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the fake data layer so the gestures can be tried without a Spotify account.
 * The Spotify implementations (Web API + App Remote) will replace these bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun trackRepository(impl: FakeTrackRepository): TrackRepository

    @Binds
    @Singleton
    abstract fun playlistRepository(impl: FakePlaylistRepository): PlaylistRepository

    @Binds
    @Singleton
    abstract fun libraryRepository(impl: InMemoryLibraryRepository): LibraryRepository

    @Binds
    @Singleton
    abstract fun player(impl: FakePlayer): Player
}
