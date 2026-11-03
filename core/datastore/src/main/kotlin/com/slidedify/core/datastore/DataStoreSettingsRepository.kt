package com.slidedify.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.domain.settings.RecentSource
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Storage format, kept separate from the domain model so the latter stays free of serialization concerns. */
@Serializable
internal data class StoredSettings(
    val version: Int = 2,
    /** providerId -> ordered destinations -> playlist ids of each destination. (v1 used fixed slots; ignored.) */
    val destinations: Map<String, List<List<String>>> = emptyMap(),
    val holdTimeoutMs: Long = SortSettings.DEFAULT_HOLD_TIMEOUT_MS,
    val leftHanded: Boolean = false,
    val haptics: Boolean = true,
    val autoplay: Boolean = true,
    val buttonMode: String = ButtonMode.Auto.name,
    val tutorialCompleted: Boolean = false,
    val suggestions: Boolean = true,
    val recent: List<StoredRecent> = emptyList(),
    val lastProvider: String? = null,
)

@Serializable
internal data class StoredRecent(
    val provider: String,
    val kind: String,
    val playlistId: String? = null,
    val title: String,
    val imageUrl: String? = null,
    val total: Int? = null,
    val openedAt: Long = 0,
)

internal fun StoredRecent.toDomain(): RecentSource? {
    val source = when (kind) {
        TrackSource.Kind.LikedSongs.name -> TrackSource.LikedSongs
        TrackSource.Kind.Recommended.name -> TrackSource.Recommended
        TrackSource.Kind.Playlist.name -> playlistId?.let { TrackSource.FromPlaylist(PlaylistId(it)) }
        else -> null
    } ?: return null
    return RecentSource(ProviderId(provider), source, title, imageUrl, total, openedAt)
}

internal fun RecentSource.toStored() = StoredRecent(
    provider = provider.value,
    kind = source.kind.name,
    playlistId = (source as? TrackSource.FromPlaylist)?.id?.value,
    title = title,
    imageUrl = imageUrl,
    total = total,
    openedAt = openedAt,
)

internal object SettingsMapper {
    val json = Json { ignoreUnknownKeys = true }

    fun toDomain(stored: StoredSettings) = SortSettings(
        gestureMaps = stored.destinations.entries.associate { (provider, destinations) ->
            ProviderId(provider) to GestureMap(
                destinations
                    .map { ids -> ids.mapTo(linkedSetOf(), ::PlaylistId) }
                    .filter { it.isNotEmpty() }
                    .take(GestureMap.MAX_DESTINATIONS),
            )
        },
        holdTimeoutMs = stored.holdTimeoutMs.coerceIn(SortSettings.HOLD_TIMEOUT_RANGE),
        leftHanded = stored.leftHanded,
        haptics = stored.haptics,
        autoplay = stored.autoplay,
        buttonMode = ButtonMode.entries.firstOrNull { it.name == stored.buttonMode } ?: ButtonMode.Auto,
        tutorialCompleted = stored.tutorialCompleted,
        suggestions = stored.suggestions,
        recent = stored.recent.mapNotNull { it.toDomain() },
        lastProvider = stored.lastProvider?.let(::ProviderId),
    )

    fun toStored(settings: SortSettings) = StoredSettings(
        destinations = settings.gestureMaps.entries.associate { (provider, map) ->
            provider.value to map.destinations.map { ids -> ids.map { it.value } }
        },
        holdTimeoutMs = settings.holdTimeoutMs,
        leftHanded = settings.leftHanded,
        haptics = settings.haptics,
        autoplay = settings.autoplay,
        buttonMode = settings.buttonMode.name,
        tutorialCompleted = settings.tutorialCompleted,
        suggestions = settings.suggestions,
        recent = settings.recent.map { it.toStored() },
        lastProvider = settings.lastProvider?.value,
    )

    fun decode(raw: String?): SortSettings = try {
        raw?.let { toDomain(json.decodeFromString(StoredSettings.serializer(), it)) } ?: SortSettings()
    } catch (e: SerializationException) {
        SortSettings() // Corrupted or incompatible data: fall back to defaults instead of crashing.
    } catch (e: IllegalArgumentException) {
        SortSettings()
    }

    fun encode(settings: SortSettings): String = json.encodeToString(StoredSettings.serializer(), toStored(settings))
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsRepository {
    private val store = context.settingsDataStore
    private val key = stringPreferencesKey("sort_settings")

    override val settings: Flow<SortSettings> = store.data.map { SettingsMapper.decode(it[key]) }

    override suspend fun update(transform: (SortSettings) -> SortSettings) {
        store.edit { prefs -> prefs[key] = SettingsMapper.encode(transform(SettingsMapper.decode(prefs[key]))) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {
    @Binds
    @Singleton
    abstract fun settingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
