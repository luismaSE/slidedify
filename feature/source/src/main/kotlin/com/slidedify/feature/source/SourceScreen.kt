package com.slidedify.feature.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.CompassMark
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.ProviderId
import com.slidedify.core.platform.ProviderSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A source the user can pick, with the title to show while sorting it. */
data class SourceOption(val source: TrackSource, val title: String, val subtitle: String)

data class ProviderChoice(val id: ProviderId, val name: String)

data class SourceUiState(
    val isLoading: Boolean = true,
    val providers: List<ProviderChoice> = emptyList(),
    val activeProvider: ProviderChoice? = null,
    val auth: AuthState = AuthState.SignedOut,
    val canSignIn: Boolean = false,
    val options: List<SourceOption> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SourceViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val signIns: Set<@JvmSuppressWildcards ProviderSignIn>,
) : ViewModel() {
    private val _state = MutableStateFlow(SourceUiState())
    val state: StateFlow<SourceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            registry.active
                .flatMapLatest { provider -> provider.auth.state.map { auth -> provider to auth } }
                .collectLatest { (provider, auth) -> load(provider, auth) }
        }
    }

    fun selectProvider(id: ProviderId) = registry.select(id)

    fun signIn(context: android.content.Context) {
        signIns.firstOrNull { it.providerId == registry.active.value.id }?.start(context)
    }

    fun signOut() {
        viewModelScope.launch { registry.active.value.auth.signOut() }
    }

    private suspend fun load(provider: MusicProvider, auth: AuthState) {
        val base = SourceUiState(
            isLoading = auth is AuthState.SignedIn,
            providers = registry.all.map { ProviderChoice(it.id, it.displayName) },
            activeProvider = ProviderChoice(provider.id, provider.displayName),
            auth = auth,
            canSignIn = signIns.any { it.providerId == provider.id },
        )
        _state.value = base
        if (auth !is AuthState.SignedIn) return
        _state.value = try {
            base.copy(isLoading = false, options = optionsFor(provider))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base.copy(isLoading = false, error = "No se pudieron cargar tus playlists (${e.message})")
        }
    }

    private suspend fun optionsFor(provider: MusicProvider): List<SourceOption> {
        val kinds = provider.capabilities.sources
        return buildList {
            if (TrackSource.Kind.LikedSongs in kinds) {
                add(SourceOption(TrackSource.LikedSongs, "Me gusta", "Tus canciones guardadas"))
            }
            if (TrackSource.Kind.Recommended in kinds) {
                add(SourceOption(TrackSource.Recommended, "Lo que más escuchás", "Tus canciones más reproducidas"))
            }
            if (TrackSource.Kind.Playlist in kinds) {
                provider.playlists.writablePlaylists().forEach { playlist ->
                    add(SourceOption(TrackSource.FromPlaylist(playlist.id), playlist.name, "Playlist"))
                }
            }
        }
    }
}

@Composable
fun SourceRoute(
    onSourceSelected: (SourceOption) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransfer: () -> Unit,
    viewModel: SourceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SourceScreen(
        state = state,
        onSourceSelected = onSourceSelected,
        onOpenSettings = onOpenSettings,
        onOpenTransfer = onOpenTransfer,
        onSelectProvider = viewModel::selectProvider,
        onSignIn = { viewModel.signIn(context) },
        onSignOut = viewModel::signOut,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(
    state: SourceUiState,
    onSourceSelected: (SourceOption) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTransfer: () -> Unit,
    onSelectProvider: (ProviderId) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { CompassMark(Modifier.padding(start = 16.dp, end = 4.dp)) },
                title = {
                    Column {
                        Text("Compass", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "¿Qué querés ordenar?",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTransfer) { Icon(AppIcons.Swap, contentDescription = "Transferir playlists entre servicios") }
                    IconButton(onClick = onOpenSettings) { Icon(AppIcons.Tune, contentDescription = "Ajustes") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.providers.size > 1) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.providers.forEach { provider ->
                        FilterChip(
                            selected = provider.id == state.activeProvider?.id,
                            onClick = { onSelectProvider(provider.id) },
                            label = { Text(provider.name) },
                        )
                    }
                }
            }
            val providerName = state.activeProvider?.name.orEmpty()
            when (val auth = state.auth) {
                is AuthState.SignedIn -> SourceList(state, auth.userName, onSourceSelected, onSignOut)
                is AuthState.Error -> SignInPrompt(providerName, "Error: ${auth.message}", state.canSignIn, onSignIn)
                AuthState.SignedOut -> SignInPrompt(providerName, "Conectá tu cuenta para ver tus playlists.", state.canSignIn, onSignIn)
            }
        }
    }
}

@Composable
private fun SignInPrompt(providerName: String, message: String, canSignIn: Boolean, onSignIn: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        if (canSignIn) {
            Button(onClick = onSignIn, modifier = Modifier.padding(top = 16.dp)) { Text("Conectar con $providerName") }
        }
    }
}

@Composable
private fun SourceList(state: SourceUiState, userName: String, onSourceSelected: (SourceOption) -> Unit, onSignOut: () -> Unit) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.activeProvider?.name} · $userName",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                if (state.canSignIn) TextButton(onClick = onSignOut) { Text("Cerrar sesión") }
            }
        }
        state.error?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        }
        items(state.options, key = { it.source.toString() }) { option ->
            ListItem(
                leadingContent = {
                    Icon(
                        when (option.source) {
                            TrackSource.LikedSongs -> AppIcons.Heart
                            TrackSource.Recommended -> AppIcons.Queue
                            is TrackSource.FromPlaylist -> AppIcons.Library
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                headlineContent = { Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(option.subtitle) },
                trailingContent = { Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { onSourceSelected(option) },
            )
            HorizontalDivider()
        }
    }
}
