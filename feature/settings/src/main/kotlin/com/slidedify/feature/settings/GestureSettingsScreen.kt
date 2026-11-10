package com.slidedify.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.CompassMark
import com.slidedify.core.designsystem.CoverArt
import com.slidedify.core.designsystem.OverlineStyle
import com.slidedify.core.designsystem.SkipColor
import com.slidedify.core.designsystem.arrowFor
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.gesture.SectorLayout
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.settings.AudioAnalysis
import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.domain.settings.ThemeMode
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToLong

data class SettingsUiState(
    val isLoading: Boolean = true,
    val providerName: String = "",
    val playlists: List<Playlist> = emptyList(),
    val gestureMap: GestureMap = GestureMap.Empty,
    val settings: SortSettings = SortSettings(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GestureSettingsViewModel @Inject constructor(
    registry: ProviderRegistry,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    /** Follows the active service, which can change from other tabs. null playlists = loading. */
    private val library: StateFlow<Pair<MusicProvider, List<Playlist>?>> = registry.active
        .flatMapLatest { provider ->
            flow<Pair<MusicProvider, List<Playlist>?>> {
                emit(provider to null)
                emit(provider to runCatching { provider.playlists.writablePlaylists() }.getOrDefault(emptyList()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, registry.active.value to null)

    val state: StateFlow<SettingsUiState> = combine(library, settingsRepository.settings) { (provider, playlists), settings ->
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

    /** Replaces the playlists of a destination; an empty selection removes it. */
    fun assign(slot: Slot, ids: Set<PlaylistId>) = editMap { it.replace(slot, ids) }

    fun addDestination(ids: Set<PlaylistId>) = editMap { it.add(ids) }

    fun removeDestination(slot: Slot) = editMap { it.remove(slot) }

    fun moveDestination(from: Slot, to: Slot) = editMap { it.move(from, to) }

    private fun editMap(edit: (GestureMap) -> GestureMap) {
        val (provider, playlists) = library.value
        update { settings ->
            val current = settings.gestureMapFor(provider.id, playlists.orEmpty())
            settings.withGestureMap(provider.id, edit(current))
        }
    }

    fun resetGestureMap() {
        val provider = library.value.first
        update { it.copy(gestureMaps = it.gestureMaps - provider.id) }
    }

    fun setLeftHanded(value: Boolean) = update { it.copy(leftHanded = value) }

    fun setHaptics(value: Boolean) = update { it.copy(haptics = value) }

    fun setSuggestions(value: Boolean) = update { it.copy(suggestions = value) }

    fun setMoveFromSource(value: Boolean) = update { it.copy(moveFromSource = value) }

    fun setAudioAnalysis(value: AudioAnalysis) = update { it.copy(audioAnalysis = value) }

    fun setTheme(value: ThemeMode) = update { it.copy(theme = value) }

    fun setHoldTimeout(ms: Long) = update { it.withHoldTimeout(ms) }

    fun setButtonMode(mode: ButtonMode) = update { it.copy(buttonMode = mode) }

    fun replayTutorial() = update { it.copy(tutorialCompleted = false) }

    private fun update(transform: (SortSettings) -> SortSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}

// --- Main settings screen --------------------------------------------------------------------

@Composable
fun SettingsRoute(
    onOpenDestinations: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenPrivacy: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: GestureSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenDestinations = onOpenDestinations,
        onOpenConnections = onOpenConnections,
        onOpenPrivacy = onOpenPrivacy,
        onOpenLicenses = onOpenLicenses,
        onLeftHanded = viewModel::setLeftHanded,
        onHaptics = viewModel::setHaptics,
        onSuggestions = viewModel::setSuggestions,
        onMoveFromSource = viewModel::setMoveFromSource,
        onAudioAnalysis = viewModel::setAudioAnalysis,
        onTheme = viewModel::setTheme,
        onHoldTimeout = viewModel::setHoldTimeout,
        onButtonMode = viewModel::setButtonMode,
        onReplayTutorial = viewModel::replayTutorial,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: (() -> Unit)?,
    onOpenDestinations: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLicenses: () -> Unit,
    onLeftHanded: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onSuggestions: (Boolean) -> Unit,
    onMoveFromSource: (Boolean) -> Unit,
    onAudioAnalysis: (AudioAnalysis) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onHoldTimeout: (Long) -> Unit,
    onButtonMode: (ButtonMode) -> Unit,
    onReplayTutorial: () -> Unit,
) {
    val settings = state.settings
    var tutorialQueued by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.settings_back)) }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { DestinationsCard(state, onOpenDestinations) }

            item {
                SettingsGroup(stringResource(R.string.settings_group_sorting)) {
                    SwitchRow(
                        AppIcons.Sparkle,
                        stringResource(R.string.settings_suggest),
                        stringResource(R.string.settings_suggest_detail),
                        settings.suggestions,
                        onSuggestions,
                    )
                    if (settings.suggestions) {
                        GroupDivider()
                        ChoiceRow(
                            icon = AppIcons.Vibration,
                            title = stringResource(R.string.settings_listen),
                            subtitle = stringResource(R.string.settings_listen_detail),
                            options = AudioAnalysis.entries,
                            selected = settings.audioAnalysis,
                            labels = mapOf(
                                AudioAnalysis.WifiOnly to stringResource(R.string.settings_listen_wifi),
                                AudioAnalysis.Always to stringResource(R.string.settings_listen_always),
                                AudioAnalysis.Off to stringResource(R.string.settings_listen_never),
                            ),
                            onSelect = onAudioAnalysis,
                        )
                    }
                    GroupDivider()
                    SwitchRow(
                        AppIcons.Swap,
                        stringResource(R.string.settings_move),
                        stringResource(R.string.settings_move_detail),
                        settings.moveFromSource,
                        onMoveFromSource,
                    )
                }
            }

            item {
                val wheelDescription = stringResource(R.string.settings_wheel_a11y)
                SettingsGroup(stringResource(R.string.settings_group_gestures)) {
                    SwitchRow(
                        AppIcons.Hand,
                        stringResource(R.string.settings_left_handed),
                        stringResource(R.string.settings_left_handed_detail),
                        settings.leftHanded,
                        onLeftHanded,
                    )
                    GroupDivider()
                    SwitchRow(
                        AppIcons.Vibration,
                        stringResource(R.string.settings_haptics),
                        stringResource(R.string.settings_haptics_detail),
                        settings.haptics,
                        onHaptics,
                    )
                    GroupDivider()
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowIcon(AppIcons.Timer)
                            Column(
                                Modifier
                                    .padding(start = 16.dp)
                                    .weight(1f),
                            ) {
                                Text(stringResource(R.string.settings_wheel), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.settings_wheel_detail, settings.holdTimeoutMs.toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Slider(
                            value = settings.holdTimeoutMs.toFloat(),
                            onValueChange = { onHoldTimeout((it / 50f).roundToLong() * 50) },
                            valueRange = SortSettings.HOLD_TIMEOUT_RANGE.first.toFloat()..SortSettings.HOLD_TIMEOUT_RANGE.last.toFloat(),
                            steps = ((SortSettings.HOLD_TIMEOUT_RANGE.last - SortSettings.HOLD_TIMEOUT_RANGE.first) / 50 - 1).toInt(),
                            modifier = Modifier.semantics { contentDescription = wheelDescription },
                        )
                    }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_accessibility)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RowIcon(AppIcons.Accessibility)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(stringResource(R.string.settings_controls), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.settings_controls_detail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val modes = ButtonMode.entries
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        ) {
                            modes.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = settings.buttonMode == mode,
                                    onClick = { onButtonMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                ) {
                                    Text(
                                        when (mode) {
                                            ButtonMode.Auto -> stringResource(R.string.settings_controls_auto)
                                            ButtonMode.On -> stringResource(R.string.settings_controls_buttons)
                                            ButtonMode.Off -> stringResource(R.string.settings_controls_gestures)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    GroupDivider()
                    NavRow(
                        AppIcons.Refresh,
                        stringResource(R.string.settings_tutorial),
                        stringResource(
                            if (tutorialQueued || !settings.tutorialCompleted) R.string.settings_tutorial_queued else R.string.settings_tutorial_detail,
                        ),
                    ) {
                        tutorialQueued = true
                        onReplayTutorial()
                    }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                    ChoiceRow(
                        icon = AppIcons.Tune,
                        title = stringResource(R.string.settings_theme),
                        subtitle = stringResource(R.string.settings_theme_detail),
                        options = listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System),
                        selected = settings.theme,
                        labels = mapOf(
                            ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                            ThemeMode.Light to stringResource(R.string.settings_theme_light),
                            ThemeMode.System to stringResource(R.string.settings_theme_system),
                        ),
                        onSelect = onTheme,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_account)) {
                    NavRow(
                        AppIcons.Link,
                        stringResource(R.string.settings_connections),
                        stringResource(R.string.settings_active_service, state.providerName),
                        onOpenConnections,
                    )
                }
            }

            item {
                SettingsGroup(stringResource(R.string.settings_group_about)) {
                    NavRow(AppIcons.Info, stringResource(R.string.about_privacy), stringResource(R.string.about_privacy_detail), onOpenPrivacy)
                    GroupDivider()
                    NavRow(AppIcons.Library, stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_detail), onOpenLicenses)
                }
            }

            item { About() }
        }
    }
}

/** Entry to the destinations editor, with a live preview of the gesture circle. */
@Composable
private fun DestinationsCard(state: SettingsUiState, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectorPreview(state.gestureMap.size, state.settings.leftHanded, size = 72.dp)
            Column(
                Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
            ) {
                Text(stringResource(R.string.settings_destinations_overline), style = OverlineStyle, color = MaterialTheme.colorScheme.primary)
                Text(
                    when (val n = state.gestureMap.size) {
                        0 -> stringResource(R.string.settings_no_destinations)
                        else -> pluralStringResource(R.plurals.settings_destinations_count, n, n)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    stringResource(R.string.settings_destinations_detail, state.providerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompassMark(size = 40.dp)
        Text("Compass", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text(
            listOfNotNull(version?.let { stringResource(R.string.settings_version, it) }, stringResource(R.string.settings_tagline)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Destinations editor ---------------------------------------------------------------------

@Composable
fun DestinationsRoute(onBack: () -> Unit, viewModel: GestureSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DestinationsScreen(
        state = state,
        onBack = onBack,
        onAssign = viewModel::assign,
        onAddDestination = viewModel::addDestination,
        onRemoveDestination = viewModel::removeDestination,
        onMoveDestination = viewModel::moveDestination,
        onResetMap = viewModel::resetGestureMap,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAssign: (Slot, Set<PlaylistId>) -> Unit,
    onAddDestination: (Set<PlaylistId>) -> Unit,
    onRemoveDestination: (Slot) -> Unit,
    onMoveDestination: (Slot, Slot) -> Unit,
    onResetMap: () -> Unit,
) {
    // null = closed, NEW = adding a destination, otherwise the slot being edited.
    var editing by remember { mutableStateOf<Slot?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.destinations_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.settings_back)) } },
                actions = { TextButton(onClick = onResetMap) { Text(stringResource(R.string.destinations_reset)) } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val layout = SectorLayout(state.gestureMap.size, state.settings.leftHanded)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    SectorPreview(state.gestureMap.size, state.settings.leftHanded, size = 168.dp)
                    Text(
                        stringResource(R.string.destinations_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 8.dp, end = 8.dp),
                    )
                }
            }
            items(state.gestureMap.slots, key = { it.index }) { slot ->
                val ids = state.gestureMap.playlistsAt(slot)
                val chosen = state.playlists.filter { it.id in ids }
                Surface(
                    onClick = { editing = slot },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SlotBadge(slot, arrowFor(layout.centerOf(slot)))
                        Column(
                            Modifier
                                .padding(start = 14.dp)
                                .weight(1f),
                        ) {
                            Text(
                                chosen.joinToString(" + ") { it.name }.ifEmpty { stringResource(R.string.destinations_no_playlist) },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (chosen.size > 1) stringResource(R.string.destinations_combo, chosen.size) else stringResource(R.string.destinations_tap_to_change),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Move around the circle (clockwise, starting next to the grey zone): earlier / later.
                        IconButton(onClick = { onMoveDestination(slot, Slot(slot.index - 1)) }, enabled = slot.index > 0) {
                            Icon(AppIcons.Chevron, contentDescription = stringResource(R.string.destinations_move_earlier), modifier = Modifier.rotate(-90f))
                        }
                        IconButton(
                            onClick = { onMoveDestination(slot, Slot(slot.index + 1)) },
                            enabled = slot.index < state.gestureMap.size - 1,
                        ) {
                            Icon(AppIcons.Chevron, contentDescription = stringResource(R.string.destinations_move_later), modifier = Modifier.rotate(90f))
                        }
                        IconButton(onClick = { onRemoveDestination(slot) }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.destinations_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                if (state.gestureMap.isFull) {
                    Text(
                        stringResource(R.string.destinations_max, GestureMap.MAX_DESTINATIONS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    OutlinedButton(
                        onClick = { editing = NEW },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(52.dp),
                    ) {
                        Icon(AppIcons.Add, contentDescription = null)
                        Text(stringResource(R.string.destinations_add), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    editing?.let { slot ->
        val isNew = slot == NEW
        PlaylistPickerSheet(
            title = stringResource(if (isNew) R.string.destinations_new else R.string.destinations_edit),
            playlists = state.playlists,
            initial = if (isNew) emptySet() else state.gestureMap.playlistsAt(slot),
            onDismiss = { editing = null },
            onConfirm = {
                if (isNew) onAddDestination(it) else onAssign(slot, it)
                editing = null
            },
        )
    }
}

/** Marker for "adding a new destination" in the editor state. */
private val NEW = Slot(-1)

/** The gesture circle as a ring: one coloured sector per destination, grey for skip. */
@Composable
private fun SectorPreview(destinations: Int, mirrored: Boolean, size: Dp) {
    val layout = SectorLayout(destinations, mirrored)
    val slotColors = (0 until destinations).map { Slot(it).color() }
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.16f
        val inset = stroke / 2
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val gap = if (destinations == 0) 0f else 3f
        fun sector(centerDegrees: Float, color: Color) {
            // Screen angles grow counter-clockwise; Canvas angles grow clockwise.
            drawArc(
                color = color,
                startAngle = -(centerDegrees + layout.sectorWidth / 2f) + gap / 2f,
                sweepAngle = layout.sectorWidth - gap,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        slotColors.forEachIndexed { index, color -> sector(layout.centerOf(Slot(index)), color) }
        sector(layout.extraCenter, SkipColor.copy(alpha = 0.6f))
    }
}

@Composable
private fun SlotBadge(slot: Slot, arrow: String) {
    Surface(shape = CircleShape, color = slot.color(), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(arrow, color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    title: String,
    playlists: List<Playlist>,
    initial: Set<PlaylistId>,
    onDismiss: () -> Unit,
    onConfirm: (Set<PlaylistId>) -> Unit,
) {
    var selected by remember(title) { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty()) {
                    Text(if (selected.size > 1) stringResource(R.string.destinations_done_count, selected.size) else stringResource(R.string.destinations_done))
                }
            }
            if (playlists.size > 6) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.destinations_search)) },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
        val visible = playlists.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(visible, key = { it.id.value }) { playlist ->
                val checked = playlist.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox) {
                            selected = if (it) selected + playlist.id else selected - playlist.id
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArt(playlist.imageUrl, playlist.name, size = 44.dp, corner = 8.dp)
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 14.dp)
                            .weight(1f),
                    )
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
    }
}

// --- Building blocks -------------------------------------------------------------------------

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = OverlineStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(
            Modifier
                .padding(start = 16.dp, end = 12.dp)
                .weight(1f),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A setting with a few exclusive options, as a segmented control under its title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    labels: Map<T, String>,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(labels[option].orEmpty(), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Column(
            Modifier
                .padding(start = 16.dp, end = 12.dp)
                .weight(1f),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
