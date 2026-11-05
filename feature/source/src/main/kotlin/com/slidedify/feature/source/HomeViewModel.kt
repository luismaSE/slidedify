package com.slidedify.feature.source

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.SortProgressRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.repository.key
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.ProviderId
import com.slidedify.core.platform.ProviderSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Something the user can sort: liked songs, most played, or one of their playlists. */
data class SourceCard(
    val source: TrackSource,
    val title: String,
    val imageUrl: String? = null,
    /** Songs in the source, when known. */
    val total: Int? = null,
    /** Songs already sorted (added or skipped). */
    val sorted: Int = 0,
    /** When it was last opened, or null if never. */
    val openedAt: Long? = null,
    /** A playlist the user follows but doesn't own (can be sorted, songs can't be removed from it). */
    val followed: Boolean = false,
) {
    val progress: Float? get() = total?.takeIf { it > 0 }?.let { sorted.toFloat() / it }
    val isDone: Boolean get() = total != null && total > 0 && sorted >= total
}

data class ProviderChoice(val id: ProviderId, val name: String)

data class HomeUiState(
    val provider: ProviderChoice? = null,
    val providers: List<ProviderChoice> = emptyList(),
    val auth: AuthState = AuthState.SignedOut,
    val canSignIn: Boolean = false,
    val isLoading: Boolean = true,
    /** The source sorted last: shown big at the top. */
    val continueCard: SourceCard? = null,
    /** Other sources opened lately. */
    val recent: List<SourceCard> = emptyList(),
    /** Liked songs and top tracks. */
    val shortcuts: List<SourceCard> = emptyList(),
    val playlists: List<SourceCard> = emptyList(),
    /** The playlists couldn't be loaded; [errorDetail] is the technical reason, if any. */
    val loadFailed: Boolean = false,
    val errorDetail: String? = null,
) {
    val userName: String? get() = (auth as? AuthState.SignedIn)?.userName
    val demo: ProviderChoice? get() = providers.firstOrNull { it.id.value == "demo" }
}

private sealed interface LibraryLoad {
    data object Loading : LibraryLoad
    data class Loaded(val playlists: List<Playlist>) : LibraryLoad
    data class Failed(val message: String?) : LibraryLoad
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val signIns: Set<@JvmSuppressWildcards ProviderSignIn>,
    settingsRepository: SettingsRepository,
    progress: SortProgressRepository,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    val state: StateFlow<HomeUiState> = combine(registry.active, refresh) { provider, _ -> provider }
        .flatMapLatest { provider ->
            provider.auth.state.flatMapLatest { auth ->
                combine(
                    library(provider, auth),
                    settingsRepository.settings,
                    progress.sortedCounts(provider.id),
                ) { load, settings, counts -> build(provider, auth, load, settings, counts) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun selectProvider(id: ProviderId) = registry.select(id)

    fun signIn(context: Context) {
        signIns.firstOrNull { it.providerId == registry.active.value.id }?.start(context)
    }

    fun retry() {
        refresh.value++
    }

    private fun library(provider: MusicProvider, auth: AuthState): Flow<LibraryLoad> =
        if (auth !is AuthState.SignedIn) {
            flowOf(LibraryLoad.Loaded(emptyList()))
        } else {
            flow {
                emit(LibraryLoad.Loading)
                val result = try {
                    LibraryLoad.Loaded(
                        if (TrackSource.Kind.Playlist in provider.capabilities.sources) provider.playlists.allPlaylists() else emptyList(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LibraryLoad.Failed(e.message)
                }
                emit(result)
            }
        }

    private fun build(
        provider: MusicProvider,
        auth: AuthState,
        load: LibraryLoad,
        settings: SortSettings,
        counts: Map<String, Int>,
    ): HomeUiState {
        val base = HomeUiState(
            provider = ProviderChoice(provider.id, provider.displayName),
            providers = registry.all.map { ProviderChoice(it.id, it.displayName) },
            auth = auth,
            canSignIn = signIns.any { it.providerId == provider.id },
            isLoading = load is LibraryLoad.Loading,
            loadFailed = load is LibraryLoad.Failed,
            errorDetail = (load as? LibraryLoad.Failed)?.message,
        )
        if (auth !is AuthState.SignedIn) return base.copy(isLoading = false)

        val recent = settings.recent.filter { it.provider == provider.id }
        val recentByKey = recent.associateBy { it.source.key }
        val playlists = (load as? LibraryLoad.Loaded)?.playlists.orEmpty()
        val playlistsById = playlists.associateBy { it.id }

        fun card(source: TrackSource, title: String, imageUrl: String? = null, total: Int? = null, followed: Boolean = false): SourceCard {
            val seen = recentByKey[source.key]
            return SourceCard(
                source = source,
                title = title,
                imageUrl = imageUrl ?: seen?.imageUrl,
                total = total ?: seen?.total,
                sorted = counts[source.key] ?: 0,
                openedAt = seen?.openedAt,
                followed = followed,
            )
        }

        val shortcuts = buildList {
            val kinds = provider.capabilities.sources
            if (TrackSource.Kind.LikedSongs in kinds) add(card(TrackSource.LikedSongs, ""))
            if (TrackSource.Kind.Recommended in kinds) add(card(TrackSource.Recommended, ""))
        }
        // Own playlists first, then followed ones.
        val playlistCards = playlists.sortedBy { !it.canWrite }
            .map { card(TrackSource.FromPlaylist(it.id), it.name, it.imageUrl, it.trackCount, followed = !it.canWrite) }
        // Recent entries use the live name and cover when the playlist still exists.
        val recentCards = recent.mapNotNull { entry ->
            when (val source = entry.source) {
                is TrackSource.FromPlaylist -> playlistsById[source.id]?.let { card(source, it.name, it.imageUrl, it.trackCount) }
                    ?: if (load is LibraryLoad.Loaded) null else card(source, entry.title)
                // Liked songs / top tracks are named by the screen, in the user's language.
                else -> card(source, "")
            }
        }
        return base.copy(
            continueCard = recentCards.firstOrNull(),
            recent = recentCards.drop(1).take(6),
            shortcuts = shortcuts,
            playlists = playlistCards,
        )
    }
}
