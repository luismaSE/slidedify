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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
    val error: String? = null,
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
        _state.update { it.copy(from = id, sources = emptyList(), source = null, error = null) }
        val provider = registry.find(id) ?: return
        viewModelScope.launch {
            val choices = try {
                listOf(SourceChoice(TrackSource.LikedSongs, "Me gusta")) +
                    provider.playlists.writablePlaylists().map { SourceChoice(TrackSource.FromPlaylist(it.id), it.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "No se pudieron leer las playlists de ${provider.displayName}") }
                return@launch
            }
            _state.update { it.copy(sources = choices) }
        }
    }

    fun selectSource(choice: SourceChoice) = _state.update { it.copy(source = choice, newName = it.newName.ifBlank { choice.name }) }

    fun selectTo(id: ProviderId) {
        _state.update { it.copy(to = id, targetPlaylists = emptyList(), existingTarget = null, error = null) }
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
fun TransferRoute(onBack: () -> Unit, viewModel: TransferViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TransferScreen(state, viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TransferScreen(state: TransferUiState, vm: TransferViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transferir playlists") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } },
            )
        },
    ) { padding ->
        if (state.services.size < 2) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Conectá al menos dos servicios para mover, copiar o sincronizar playlists entre ellos.")
            }
            return@Scaffold
        }
        state.progress?.let { progress ->
            ProgressView(progress, onDone = vm::reset, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item { Section("1. Desde") }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.services.forEach { s ->
                        FilterChip(selected = s.id == state.from, onClick = { vm.selectFrom(s.id) }, label = { Text(s.name) })
                    }
                }
            }
            items(state.sources, key = { it.source.toString() }) { choice ->
                RadioRow(choice.name, selected = choice == state.source) { vm.selectSource(choice) }
            }

            item { Section("2. Hacia") }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.services.filter { it.id != state.from }.forEach { s ->
                        FilterChip(
                            selected = s.id == state.to,
                            enabled = s.canSearch,
                            onClick = { vm.selectTo(s.id) },
                            label = { Text(if (s.canSearch) s.name else "${s.name} (no disponible)") },
                        )
                    }
                }
            }
            item { RadioRow("Nueva playlist", selected = state.existingTarget == null) { vm.selectExisting(null) } }
            if (state.existingTarget == null) {
                item {
                    OutlinedTextField(
                        value = state.newName,
                        onValueChange = vm::setNewName,
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
            items(state.targetPlaylists, key = { "t-" + it.id.value }) { playlist ->
                RadioRow(playlist.name, selected = playlist == state.existingTarget) { vm.selectExisting(playlist) }
            }

            item { Section("3. Cómo") }
            items(TransferMode.entries) { mode ->
                val (title, detail) = when (mode) {
                    TransferMode.Copy -> "Copiar" to "Agrega las canciones al destino; el origen no cambia."
                    TransferMode.Move -> "Mover" to "Copia y después saca del origen las canciones que encontró."
                    TransferMode.Sync -> "Sincronizar" to "Las dos playlists quedan con todas las canciones. Requiere una playlist existente."
                }
                val enabled = mode != TransferMode.Sync || state.existingTarget != null
                RadioRow(title, detail, selected = mode == state.mode, enabled = enabled) { vm.setMode(mode) }
            }

            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            item {
                Button(onClick = vm::start, enabled = state.canStart, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Empezar")
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
                Text("Leyendo ${progress.serviceName}…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is TransferProgress.Matching -> {
                Text("Buscando canciones: ${progress.done} de ${progress.total}")
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            }
            TransferProgress.Writing -> {
                Text("Guardando cambios…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is TransferProgress.Failed -> {
                Text(progress.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDone) { Text("Volver a intentar") }
            }
            is TransferProgress.Done -> {
                val r = progress.report
                Text("Listo: \"${r.target.name}\"", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${r.addedToTarget} agregadas · ${r.alreadyInTarget} ya estaban · ${r.notFound.size} no encontradas")
                if (r.removedFromSource > 0) Text("${r.removedFromSource} quitadas del origen")
                if (r.addedToSource > 0) Text("${r.addedToSource} agregadas al origen")
                Text(
                    "Los cambios se envían en segundo plano (⟳ en la pantalla de ordenar).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.notFound.isNotEmpty()) {
                    HorizontalDivider()
                    Text("No encontradas", fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f)) {
                        items(r.notFound) { t -> Text("${t.title} · ${t.artistNames}", modifier = Modifier.padding(vertical = 4.dp)) }
                    }
                }
                Button(onClick = onDone) { Text("Hacer otra") }
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
