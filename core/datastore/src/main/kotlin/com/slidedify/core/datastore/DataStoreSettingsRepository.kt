package com.slidedify.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Slot
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
    val version: Int = 1,
    /** providerId -> slot name -> playlist ids */
    val gestureMaps: Map<String, Map<String, List<String>>> = emptyMap(),
    val holdTimeoutMs: Long = SortSettings.DEFAULT_HOLD_TIMEOUT_MS,
    val leftHanded: Boolean = false,
    val haptics: Boolean = true,
    val autoplay: Boolean = true,
    val buttonMode: String = ButtonMode.Auto.name,
    val tutorialCompleted: Boolean = false,
)

internal object SettingsMapper {
    val json = Json { ignoreUnknownKeys = true }

    fun toDomain(stored: StoredSettings) = SortSettings(
        gestureMaps = stored.gestureMaps.entries.associate { (provider, slots) ->
            ProviderId(provider) to GestureMap(
                slots.mapNotNull { (slot, ids) ->
                    Slot.entries.firstOrNull { it.name == slot }?.let { it to ids.mapTo(linkedSetOf(), ::PlaylistId) }
                }.toMap(),
            )
        },
        holdTimeoutMs = stored.holdTimeoutMs.coerceIn(SortSettings.HOLD_TIMEOUT_RANGE),
        leftHanded = stored.leftHanded,
        haptics = stored.haptics,
        autoplay = stored.autoplay,
        buttonMode = ButtonMode.entries.firstOrNull { it.name == stored.buttonMode } ?: ButtonMode.Auto,
        tutorialCompleted = stored.tutorialCompleted,
    )

    fun toStored(settings: SortSettings) = StoredSettings(
        gestureMaps = settings.gestureMaps.entries.associate { (provider, map) ->
            provider.value to map.targets.entries.associate { (slot, ids) -> slot.name to ids.map { it.value } }
        },
        holdTimeoutMs = settings.holdTimeoutMs,
        leftHanded = settings.leftHanded,
        haptics = settings.haptics,
        autoplay = settings.autoplay,
        buttonMode = settings.buttonMode.name,
        tutorialCompleted = settings.tutorialCompleted,
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
