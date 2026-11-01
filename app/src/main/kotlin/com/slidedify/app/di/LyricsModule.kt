package com.slidedify.app.di

import com.slidedify.core.domain.lyrics.LyricsRepository
import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.lyrics.lrclib.CachingLyricsRepository
import com.slidedify.lyrics.lrclib.LrclibLyricsRepository
import com.slidedify.preview.deezer.DeezerPreviewSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Lyrics are provider-independent: one source for Spotify, YouTube Music, Tidal... */
    @Provides
    @Singleton
    fun lyricsRepository(client: OkHttpClient): LyricsRepository =
        CachingLyricsRepository(LrclibLyricsRepository(client))

    /** Short previews when a provider can't play the full track (any provider, no account needed). */
    @Provides
    @Singleton
    fun previewSource(client: OkHttpClient): PreviewSource = DeezerPreviewSource(client)
}
