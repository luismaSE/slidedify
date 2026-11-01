package com.slidedify.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slidedify.core.designsystem.arrow
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToLong

data class SettingsUiState(
    val isLoading: Boolean = true,
    val providerName: String = "",
    val playlists: List<Playlist> = emptyList(),
    val gestureMap: GestureMap = GestureMap(emptyMap()),
    val settings: SortSettings = SortSettings(),
)

@HiltViewModel
class GestureSettingsViewModel @Inject constructor(
    registry: ProviderRegistry,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val provider = registry.active.value
    private val playlists = MutableStateFlow<List<Playlist>?>(null)

    val state: StateFlow<SettingsUiState> = combine(playlists, settingsRepository.settings) { playlists, settings ->
        if (playlists == null) {
            SettingsUiState(providerName = provider.displayName, settings = settings)
        } else {
            SettingsUiState(
                isLoading = false,
                providerName = provider.displayName,
                playlists = playlists,
                gestureMap = settings.gestureMapFor(provider.id, playlists),
                settings = settings,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { playlists.value = provider.playlists.writablePlaylists() }
    }

    fun assign(slot: Slot, ids: Set<PlaylistId>) = update { settings ->
        val current = settings.gestureMapFor(provider.id, playlists.value.orEmpty())
        settings.withGestureMap(provider.id, current.withSlot(slot, ids))
    }

    fun resetGestureMap() = update { it.copy(gestureMaps = it.gestureMaps - provider.id) }

    fun setLeftHanded(value: Boolean) = update { it.copy(leftHanded = value) }

    fun setHaptics(value: Boolean) = update { it.copy(haptics = value) }

    fun setAutoplay(value: Boolean) = update { it.copy(autoplay = value) }

    fun setHoldTimeout(ms: Long) = update { it.withHoldTimeout(ms) }

    fun setButtonMode(mode: ButtonMode) = update { it.copy(buttonMode = mode) }

    fun replayTutorial() = update { it.copy(tutorialCompleted = false) }

    private fun update(transform: (SortSettings) -> SortSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}

@Composable
fun GestureSettingsRoute(onBack: () -> Unit, viewModel: GestureSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GestureSettingsScreen(
        state = state,
        onBack = onBack,
        onAssign = viewModel::assign,
        onResetMap = viewModel::resetGestureMap,
        onLeftHanded = viewModel::setLeftHanded,
        onHaptics = viewModel::setHaptics,
        onAutoplay = viewModel::setAutoplay,
        onHoldTimeout = viewModel::setHoldTimeout,
        onButtonMode = viewModel::setButtonMode,
        onReplayTutorial = viewModel::replayTutorial,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAssign: (Slot, Set<PlaylistId>) -> Unit,
    onResetMap: () -> Unit,
    onLeftHanded: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onAutoplay: (Boolean) -> Unit,
    onHoldTimeout: (Long) -> Unit,
    onButtonMode: (ButtonMode) -> Unit,
    onReplayTutorial: () -> Unit,
) {
    var editing by remember { mutableStateOf<Slot?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Volver") } },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val settings = state.settings
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionTitle("Direcciones (${state.providerName})") }
            item {
                Text(
                    "Tocá una dirección para elegir sus playlists. Si elegís varias, se agrega a todas (combo).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(Slot.entries, key = { it.name }) { slot ->
                val ids = state.gestureMap.targets[slot].orEmpty()
                val names = state.playlists.filter { it.id in ids }.joinToString(" + ") { it.name }
                ListItem(
                    leadingContent = { SlotBadge(slot, settings.leftHanded) },
                    headlineContent = { Text(names.ifEmpty { "Sin asignar" }) },
                    supportingContent = { Text(slotDescription(slot, settings.leftHanded)) },
                    modifier = Modifier.clickable(role = Role.Button) { editing = slot },
                )
            }
            item {
                TextButton(onClick = onResetMap, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Restablecer direcciones")
                }
                HorizontalDivider()
            }

            item { SectionTitle("Uso") }
            item { SwitchRow("Modo zurdo", "Playlists a la izquierda, omitir/cancelar a la derecha", settings.leftHanded, onLeftHanded) }
            item { SwitchRow("Vibración", "Al abrir la rueda y al marcar playlists", settings.haptics, onHaptics) }
            item { SwitchRow("Reproducción automática", "Cada canción empieza a sonar al aparecer", settings.autoplay, onAutoplay) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Tiempo para abrir la rueda: ${settings.holdTimeoutMs} ms")
                    Slider(
                        value = settings.holdTimeoutMs.toFloat(),
                        onValueChange = { onHoldTimeout((it / 50f).roundToLong() * 50) },
                        valueRange = SortSettings.HOLD_TIMEOUT_RANGE.first.toFloat()..SortSettings.HOLD_TIMEOUT_RANGE.last.toFloat(),
                        steps = ((SortSettings.HOLD_TIMEOUT_RANGE.last - SortSettings.HOLD_TIMEOUT_RANGE.first) / 50 - 1).toInt(),
                    )
                }
                HorizontalDivider()
            }

            item { SectionTitle("Accesibilidad") }
            item {
                Text(
                    "El modo botones muestra botones en lugar de gestos (útil con TalkBack).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(ButtonMode.entries, key = { it.name }) { mode ->
                val label = when (mode) {
                    ButtonMode.Auto -> "Automático (si TalkBack está activo)"
                    ButtonMode.On -> "Siempre botones"
                    ButtonMode.Off -> "Siempre gestos"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.buttonMode == mode, role = Role.RadioButton) { onButtonMode(mode) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.buttonMode == mode, onClick = null)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
            item {
                OutlinedButton(onClick = onReplayTutorial, modifier = Modifier.padding(16.dp)) {
                    Text(if (settings.tutorialCompleted) "Ver el tutorial de nuevo" else "El tutorial se mostrará al ordenar")
                }
            }
        }
    }

    editing?.let { slot ->
        PlaylistPickerDialog(
            slot = slot,
            playlists = state.playlists,
            initial = state.gestureMap.targets[slot].orEmpty(),
            onDismiss = { editing = null },
            onConfirm = {
                onAssign(slot, it)
                editing = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun SlotBadge(slot: Slot, leftHanded: Boolean) {
    Surface(shape = CircleShape, color = slot.color(), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(mirroredArrow(slot, leftHanded), color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

private fun mirroredArrow(slot: Slot, leftHanded: Boolean): String = if (!leftHanded) {
    slot.arrow()
} else {
    when (slot) {
        Slot.UP -> "↑"
        Slot.UP_RIGHT -> "↖"
        Slot.RIGHT -> "←"
        Slot.DOWN_RIGHT -> "↙"
        Slot.DOWN -> "↓"
    }
}

private fun slotDescription(slot: Slot, leftHanded: Boolean): String {
    val side = if (leftHanded) "izquierda" else "derecha"
    return when (slot) {
        Slot.UP -> "Deslizar hacia arriba"
        Slot.UP_RIGHT -> "Deslizar en diagonal arriba a la $side"
        Slot.RIGHT -> "Deslizar hacia la $side"
        Slot.DOWN_RIGHT -> "Deslizar en diagonal abajo a la $side"
        Slot.DOWN -> "Deslizar hacia abajo"
    }
}

@Composable
private fun PlaylistPickerDialog(
    slot: Slot,
    playlists: List<Playlist>,
    initial: Set<PlaylistId>,
    onDismiss: () -> Unit,
    onConfirm: (Set<PlaylistId>) -> Unit,
) {
    var selected by remember(slot) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${slot.arrow()} Playlists para esta dirección") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(playlists, key = { it.id.value }) { playlist ->
                    val checked = playlist.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, role = Role.Checkbox) {
                                selected = if (it) selected + playlist.id else selected - playlist.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(playlist.name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Listo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
