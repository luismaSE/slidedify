package com.slidedify.app.di

import com.slidedify.app.BuildConfig
import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.domain.suggest.FeatureCache
import com.slidedify.core.domain.suggest.FeatureSource
import com.slidedify.core.domain.suggest.Suggester
import com.slidedify.core.media.PreviewAudioFeatureSource
import com.slidedify.preview.deezer.DeezerFeatureSource
import com.slidedify.suggest.lastfm.LastFmTagSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Where suggestions learn how a song sounds. Order matters: earlier sources win on tempo. */
@Module
@InstallIn(SingletonComponent::class)
object SuggestionModule {
    @Provides
    @Singleton
    fun featureSources(client: OkHttpClient, previews: PreviewSource): List<FeatureSource> = buildList {
        add(DeezerFeatureSource(client))
        BuildConfig.LASTFM_API_KEY.takeIf { it.isNotBlank() }?.let { add(LastFmTagSource(client, it)) }
        add(PreviewAudioFeatureSource(previews))
    }

    @Provides
    @Singleton
    fun suggester(sources: List<@JvmSuppressWildcards FeatureSource>, cache: FeatureCache): Suggester = Suggester(sources, cache)
}
