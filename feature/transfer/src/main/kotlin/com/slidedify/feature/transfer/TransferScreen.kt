package com.slidedify.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.EmptyState
import androidx.compose.foundation.layout.Box
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.transfer.PlaylistTransfer
import com.slidedify.core.domain.transfer.TransferMode
import com.slidedify.core.domain.transfer.TransferProgress
import com.slidedify.core.domain.transfer.TransferRequest
import com.slidedify.core.domain.transfer.TransferTarget
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.ProviderId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServiceChoice(val id: ProviderId, val name: String, val canSearch: Boolean)

data class SourceChoice(val source: TrackSource, val name: String)

data class TransferUiState(
    val services: List<ServiceChoice> = emptyList(),
    val from: ProviderId? = null,
    val sources: List<SourceChoice> = emptyList(),
    val source: SourceChoice? = null,
    val to: ProviderId? = null,
    val targetPlaylists: List<Playlist> = emptyList(),
    /** null = create a new playlist named [newName]. */
    val existingTarget: Playlist? = null,
    val newName: String = "",
    val mode: TransferMode = TransferMode.Copy,
    val progress: TransferProgress? = null,
    /** Name of the service whose playlists couldn't be read, if that happened. */
    val readErrorService: String? = null,
) {
    val running: Boolean get() = progress != null && progress !is TransferProgress.Done && progress !is TransferProgress.Failed
    val canStart: Boolean
        get() = !running && from != null && to != null && from != to && source != null &&
            (existingTarget != null || newName.isNotBlank()) &&
            (mode != TransferMode.Sync || existingTarget != null)
}

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val registry: ProviderRegistry,
    private val transfer: PlaylistTransfer,
) : ViewModel() {
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        // Only services the user is connected to can take part.
        val services = registry.all
            .filter { it.auth.state.value is AuthState.SignedIn }
            .map { ServiceChoice(it.id, it.displayName, it.capabilities.canSearch) }
        _state.value = TransferUiState(services = services)
        services.firstOrNull()?.let { selectFrom(it.id) }
        services.firstOrNull { it.id != services.firstOrNull()?.id && it.canSearch }?.let { selectTo(it.id) }
    }

    fun selectFrom(id: ProviderId) {
        _state.update { it.copy(from = id, sources = emptyList(), source = null, readErrorService = null) }
        val provider = registry.find(id) ?: return
        viewModelScope.launch {
            val choices = try {
                // Liked songs get their (translated) name from the screen.
                listOf(SourceChoice(TrackSource.LikedSongs, "")) +
                    provider.playlists.allPlaylists().map { SourceChoice(TrackSource.FromPlaylist(it.id), it.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(readErrorService = provider.displayName) }
                return@launch
            }
            _state.update { it.copy(sources = choices) }
        }
    }

    /** [displayName]: how the screen shows it (liked songs are named there). */
    fun selectSource(choice: SourceChoice, displayName: String = choice.name) =
        _state.update { it.copy(source = choice, newName = it.newName.ifBlank { displayName }) }

    fun selectTo(id: ProviderId) {
        _state.update { it.copy(to = id, targetPlaylists = emptyList(), existingTarget = null, readErrorService = null) }
        val provider = registry.find(id) ?: return
        viewModelScope.launch {
            val playlists = try {
                provider.playlists.writablePlaylists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            _state.update { it.copy(targetPlaylists = playlists) }
        }
    }

    fun selectExisting(playlist: Playlist?) = _state.update {
        it.copy(existingTarget = playlist, mode = if (playlist == null && it.mode == TransferMode.Sync) TransferMode.Copy else it.mode)
    }

    fun setNewName(name: String) = _state.update { it.copy(newName = name) }

    fun setMode(mode: TransferMode) = _state.update { it.copy(mode = mode) }

    fun start() {
        val s = _state.value
        if (!s.canStart) return
        val request = TransferRequest(
            from = s.from!!,
            source = s.source!!.source,
            to = s.to!!,
            target = s.existingTarget?.let { TransferTarget.Existing(it.id) } ?: TransferTarget.NewPlaylist(s.newName.trim()),
            mode = s.mode,
        )
        job?.cancel()
        job = viewModelScope.launch {
            transfer.run(request).collect { progress -> _state.update { it.copy(progress = progress) } }
        }
    }

    fun reset() = _state.update { it.copy(progress = null) }
}

@Composable
fun TransferRoute(
    onBack: (() -> Unit)? = null,
    onOpenConnections: () -> Unit = {},
    viewModel: TransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransferScreen(state, viewModel, onBack, onOpenConnections)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TransferScreen(
    state: TransferUiState,
    vm: TransferViewModel,
    onBack: (() -> Unit)?,
    onOpenConnections: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.transfer_back)) }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.services.size < 2) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = AppIcons.Swap,
                    title = stringResource(R.string.transfer_empty_title),
                    message = stringResource(R.string.transfer_empty_text),
                    actionLabel = stringResource(R.string.transfer_go_connections),
                    onAction = onOpenConnections,
                )
            }
            return@Scaffold
        }
        state.progress?.let { progress ->
            ProgressView(progress, onDone = vm::reset, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item { Section(stringResource(R.string.transfer_from)) }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.services.forEach { s ->
                        FilterChip(selected = s.id == state.from, onClick = { vm.selectFrom(s.id) }, label = { Text(s.name) })
                    }
                }
            }
            items(state.sources, key = { it.source.toString() }) { choice ->
                val name = if (choice.source == TrackSource.LikedSongs) stringResource(R.string.transfer_liked) else choice.name
                RadioRow(name, selected = choice == state.source) { vm.selectSource(choice, name) }
            }

            item { Section(stringResource(R.string.transfer_to)) }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.services.filter { it.id != state.from }.forEach { s ->
                        FilterChip(
                            selected = s.id == state.to,
                            enabled = s.canSearch,
                            onClick = { vm.selectTo(s.id) },
                            label = { Text(if (s.canSearch) s.name else stringResource(R.string.transfer_unavailable, s.name)) },
                        )
                    }
                }
            }
            item { RadioRow(stringResource(R.string.transfer_new_playlist), selected = state.existingTarget == null) { vm.selectExisting(null) } }
            if (state.existingTarget == null) {
                item {
                    OutlinedTextField(
                        value = state.newName,
                        onValueChange = vm::setNewName,
                        label = { Text(stringResource(R.string.transfer_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            items(state.targetPlaylists, key = { "t-" + it.id.value }) { playlist ->
                RadioRow(playlist.name, selected = playlist == state.existingTarget) { vm.selectExisting(playlist) }
            }

            item { Section(stringResource(R.string.transfer_how)) }
            items(TransferMode.entries) { mode ->
                val (title, detail) = when (mode) {
                    TransferMode.Copy -> stringResource(R.string.transfer_copy) to stringResource(R.string.transfer_copy_detail)
                    TransferMode.Move -> stringResource(R.string.transfer_move) to stringResource(R.string.transfer_move_detail)
                    TransferMode.Sync -> stringResource(R.string.transfer_sync) to stringResource(R.string.transfer_sync_detail)
                }
                val enabled = mode != TransferMode.Sync || state.existingTarget != null
                RadioRow(title, detail, selected = mode == state.mode, enabled = enabled) { vm.setMode(mode) }
            }

            state.readErrorService?.let { item { Text(stringResource(R.string.transfer_read_error, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            item {
                Button(onClick = vm::start, enabled = state.canStart, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.transfer_start))
                }
            }
        }
    }
}

@Composable
private fun ProgressView(progress: TransferProgress, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (progress) {
            is TransferProgress.Reading -> {
                Text(stringResource(R.string.transfer_reading, progress.serviceName))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is TransferProgress.Matching -> {
                Text(stringResource(R.string.transfer_matching, progress.done, progress.total))
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            }
            TransferProgress.Writing -> {
                Text(stringResource(R.string.transfer_writing))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is TransferProgress.Failed -> {
                Text(
                    stringResource(R.string.transfer_failed) + progress.message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onDone) { Text(stringResource(R.string.transfer_retry)) }
            }
            is TransferProgress.Done -> {
                val r = progress.report
                Text(stringResource(R.string.transfer_done, r.target.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.transfer_summary, r.addedToTarget, r.alreadyInTarget, r.notFound.size))
                if (r.removedFromSource > 0) Text(stringResource(R.string.transfer_removed, r.removedFromSource))
                if (r.addedToSource > 0) Text(stringResource(R.string.transfer_added_back, r.addedToSource))
                Text(
                    stringResource(R.string.transfer_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.notFound.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.transfer_not_found), fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f)) {
                        items(r.notFound) { t -> Text("${t.title} · ${t.artistNames}", modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                }
                Button(onClick = onDone) { Text(stringResource(R.string.transfer_again)) }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
    )
}

@Composable
private fun RadioRow(title: String, detail: String? = null, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
