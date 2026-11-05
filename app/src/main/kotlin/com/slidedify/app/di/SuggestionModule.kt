package com.slidedify.app.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.slidedify.app.BuildConfig
import com.slidedify.core.domain.settings.AudioAnalysis
import com.slidedify.core.domain.settings.SettingsRepository
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Singleton

/** Where suggestions learn how a song sounds. Order matters: earlier sources win on tempo. */
@Module
@InstallIn(SingletonComponent::class)
object SuggestionModule {
    @Provides
    @Singleton
    fun featureSources(
        client: OkHttpClient,
        previews: PreviewSource,
        settings: SettingsRepository,
        @ApplicationContext context: Context,
    ): List<FeatureSource> = buildList {
        add(DeezerFeatureSource(client))
        BuildConfig.LASTFM_API_KEY.takeIf { it.isNotBlank() }?.let { add(LastFmTagSource(client, it)) }
        add(
            PreviewAudioFeatureSource(previews) {
                when (settings.settings.first().audioAnalysis) {
                    AudioAnalysis.Always -> true
                    AudioAnalysis.Off -> false
                    AudioAnalysis.WifiOnly -> isUnmetered(context)
                }
            },
        )
    }

    /** Wi-Fi or Ethernet (not mobile data, not a metered hotspot). */
    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    @Provides
    @Singleton
    fun suggester(sources: List<@JvmSuppressWildcards FeatureSource>, cache: FeatureCache): Suggester = Suggester(sources, cache)
}
