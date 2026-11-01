package com.slidedify.feature.swipe

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.slidedify.core.designsystem.CancelColor
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.gesture.DoubleTapDetector
import com.slidedify.core.domain.gesture.GestureFeedback
import com.slidedify.core.domain.gesture.GestureOutcome
import com.slidedify.core.domain.gesture.GestureState
import com.slidedify.core.domain.gesture.GestureStep
import com.slidedify.core.domain.gesture.Sector
import com.slidedify.core.domain.gesture.SectorLayout
import com.slidedify.core.domain.gesture.SwipeResult
import com.slidedify.core.domain.gesture.TapKind
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Callbacks of the sorting screen, grouped to keep signatures short. */
data class SwipeActions(
    val onSwipe: (SwipeResult) -> Unit = {},
    val onWheelOpened: () -> Unit = {},
    val onWheelCommit: (Set<Slot>) -> Unit = {},
    val onDoubleTap: () -> Unit = {},
    val onTogglePlayback: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onSkipTutorial: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
fun SwipeRoute(onBack: () -> Unit, onOpenSettings: () -> Unit, viewModel: SwipeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            val (message, undoable) = when (event) {
                is SwipeEvent.Added -> "Agregada a ${event.playlistNames.joinToString(", ")}" to true
                SwipeEvent.Skipped -> "Omitida" to true
                is SwipeEvent.LikeChanged -> (if (event.liked) "♥ Agregada a Me gusta" else "Quitada de Me gusta") to true
                SwipeEvent.Undone -> "Deshecho" to false
            }
            launch {
                val result = snackbar.showSnackbar(
                    message = message,
                    actionLabel = if (undoable) "Deshacer" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.onUndo()
            }
        }
    }

    SwipeScreen(
        state = state,
        snackbar = snackbar,
        actions = SwipeActions(
            onSwipe = viewModel::onSwipe,
            onWheelOpened = viewModel::onWheelOpened,
            onWheelCommit = viewModel::onWheelCommit,
            onDoubleTap = viewModel::onDoubleTap,
            onTogglePlayback = viewModel::onTogglePlayback,
            onUndo = viewModel::onUndo,
            onSkipTutorial = viewModel::skipTutorial,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
        ),
    )
}

@Composable
fun SwipeScreen(state: SwipeUiState, snackbar: SnackbarHostState, actions: SwipeActions) {
    val buttonMode = rememberButtonModeActive(state.settings.buttonMode)
    var showLyrics by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(state, actions)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.isFinished -> FinishedMessage(actions.onBack, Modifier.fillMaxSize())
                else -> {
                    val track = state.current ?: return@Column
                    if (buttonMode) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TrackCard(track, state, actions, Modifier.padding(horizontal = 24.dp))
                        }
                        ButtonPanel(track, state, actions)
                    } else {
                        GestureArea(state, track, actions, Modifier.weight(1f).fillMaxWidth())
                    }
                    PlayerBar(state, actions.onTogglePlayback)
                    LyricsPanel(state.lyrics, state.player.positionMs, onOpen = { showLyrics = true })
                }
            }
        }
    }

    val lyrics = state.lyrics
    if (showLyrics && lyrics is LyricsState.Available) {
        LyricsSheet(lyrics.lyrics, state.player.positionMs, onDismiss = { showLyrics = false })
    }
}

@Composable
private fun TopBar(state: SwipeUiState, actions: SwipeActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions.onBack) { Text("←") }
        Column(Modifier.weight(1f)) {
            Text(
                "Ordenando · ${state.providerName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.sourceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.pendingSync > 0) {
            Text(
                "⟳ ${state.pendingSync}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .semantics { contentDescription = "${state.pendingSync} cambios pendientes de sincronizar" },
            )
        }
        if (state.total > 0) {
            Text(
                "${(state.position + 1).coerceAtMost(state.total)}/${state.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = actions.onUndo, enabled = state.canUndo) { Text("↶") }
        TextButton(onClick = actions.onOpenSettings) { Text("⚙") }
    }
}

@Composable
private fun GestureArea(state: SwipeUiState, track: Track, actions: SwipeActions, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val settings = state.settings
    val mirrored = settings.leftHanded
    val config = rememberGestureConfig(state.enabledSlots, settings.holdTimeoutMs, mirrored)
    val tapDetector = remember(density) { DoubleTapDetector(300, with(density) { 48.dp.toPx() }) }
    var singleTapJob by remember { mutableStateOf<Job?>(null) }
    var gesture by remember { mutableStateOf<GestureState>(GestureState.Idle) }
    val cardOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    BoxWithConstraints(modifier) {
        val flyDistance = with(density) { (maxWidth + maxHeight).toPx() }
        val handleStep: (GestureStep) -> Unit = { step ->
            val previous = gesture
            gesture = step.state
            (step.state as? GestureState.Dragging)?.let { drag ->
                scope.launch { cardOffset.snapTo(drag.offset.toOffset()) }
            }
            step.feedback?.let { feedback ->
                if (settings.haptics) haptics.performHapticFeedback(feedback.hapticType())
                if (feedback == GestureFeedback.WheelOpened) actions.onWheelOpened()
            }
            when (val outcome = step.outcome) {
                is GestureOutcome.Tap -> when (tapDetector.onTap(outcome)) {
                    TapKind.FirstTap -> {
                        singleTapJob = scope.launch {
                            delay(tapDetector.timeoutMs)
                            actions.onTogglePlayback()
                        }
                    }

                    TapKind.DoubleTap -> {
                        singleTapJob?.cancel()
                        actions.onDoubleTap()
                    }
                }

                is GestureOutcome.Swipe -> scope.launch {
                    (previous as? GestureState.Dragging)?.let { cardOffset.snapTo(it.offset.toOffset()) }
                    cardOffset.animateTo(flyOutTarget(outcome.result, flyDistance, mirrored), tween(180))
                    actions.onSwipe(outcome.result)
                    cardOffset.snapTo(Offset.Zero)
                }

                GestureOutcome.SwipeAborted -> scope.launch { cardOffset.animateTo(Offset.Zero, spring()) }
                is GestureOutcome.WheelCommit -> actions.onWheelCommit(outcome.slots)
                GestureOutcome.WheelCancelled, GestureOutcome.WheelDismissed, null -> Unit
            }
        }
        val latestHandleStep by rememberUpdatedState(handleStep)
        Box(
            Modifier
                .fillMaxSize()
                .sortGestures(config) { latestHandleStep(it) },
        ) {
            TrackCard(
                track = track,
                state = state,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        translationX = cardOffset.value.x
                        translationY = cardOffset.value.y
                        rotationZ = cardOffset.value.x / 40f
                        alpha = if (gesture is GestureState.Wheel) 0.5f else 1f
                    },
            )

            (gesture as? GestureState.Dragging)?.let { drag ->
                DragHint(drag, state.targets, config.touchSlop * 3, mirrored, Modifier.align(Alignment.TopCenter))
            }

            state.tutorialStep?.let { step ->
                if (gesture == GestureState.Idle) {
                    TutorialCard(step, state.canLike, actions.onSkipTutorial, Modifier.align(Alignment.TopCenter))
                }
            }

            if (gesture == GestureState.Idle && state.tutorialStep == null) {
                Text(
                    "Mantené presionado para elegir varias playlists",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }

            (gesture as? GestureState.Wheel)?.let { wheel ->
                WheelOverlay(wheel, state.targets, mirrored, Modifier.fillMaxSize())
            }
        }
    }
}

private fun flyOutTarget(result: SwipeResult, distance: Float, mirrored: Boolean): Offset {
    val layoutAngle = when (result) {
        SwipeResult.Skip -> 180f
        is SwipeResult.ToSlot -> result.slot.centerDegrees
    }
    val rad = Math.toRadians(SectorLayout.displayAngle(layoutAngle, mirrored).toDouble())
    return Offset((cos(rad) * distance).toFloat(), (-sin(rad) * distance).toFloat())
}

private fun GestureFeedback.hapticType(): HapticFeedbackType = when (this) {
    GestureFeedback.WheelOpened -> HapticFeedbackType.LongPress
    GestureFeedback.SlotSelected -> HapticFeedbackType.ToggleOn
    GestureFeedback.SlotDeselected -> HapticFeedbackType.ToggleOff
    GestureFeedback.EnteredCancel -> HapticFeedbackType.Reject
}

/** Shows where the card will go if released now. */
@Composable
private fun DragHint(
    drag: GestureState.Dragging,
    targets: Map<Slot, SlotTarget>,
    minDistance: Float,
    mirrored: Boolean,
    modifier: Modifier,
) {
    val offset = drag.offset
    if (offset.length < minDistance) return
    val (text, color) = when (val sector = SectorLayout.sectorOf(offset, deadZoneDegrees = 5f, mirrored = mirrored)) {
        Sector.Left -> "✕ Omitir" to CancelColor
        is Sector.Destination -> {
            val target = targets[sector.slot] ?: return
            target.label to sector.slot.color()
        }

        null -> return
    }
    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.9f),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TrackCard(track: Track, state: SwipeUiState, actions: SwipeActions, modifier: Modifier = Modifier) {
    // Every gesture is also reachable from TalkBack's actions menu.
    val a11yActions = buildList {
        add(CustomAccessibilityAction("Omitir") { actions.onSwipe(SwipeResult.Skip); true })
        state.targets.values.forEach { target ->
            add(CustomAccessibilityAction("Agregar a ${target.label}") { actions.onSwipe(SwipeResult.ToSlot(target.slot)); true })
        }
        if (state.canLike) {
            add(CustomAccessibilityAction(if (state.isLiked) "Quitar de Me gusta" else "Me gusta") { actions.onDoubleTap(); true })
        }
        add(CustomAccessibilityAction(if (state.player.isPlaying) "Pausar" else "Reproducir") { actions.onTogglePlayback(); true })
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${track.title}, de ${track.artistNames}, álbum ${track.album.name}"
                customActions = a11yActions
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(placeholderBrush(track)),
            contentAlignment = Alignment.Center,
        ) {
            if (track.album.imageUrl != null) {
                AsyncImage(
                    model = track.album.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(track.album.name, style = MaterialTheme.typography.headlineSmall, color = Color.White.copy(alpha = 0.85f))
            }
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(track.artistNames, track.album.name, track.album.releaseYear?.toString()).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.canLike) {
                Text(
                    if (state.isLiked) "♥" else "♡",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (state.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Deterministic gradient per album, used until real cover art is loaded. */
private fun placeholderBrush(track: Track): Brush {
    val palette = Slot.entries.map { it.color() }
    val hash = abs(track.album.id.hashCode())
    val first = palette[hash % palette.size]
    val second = palette[(hash / palette.size + 1) % palette.size]
    return Brush.linearGradient(listOf(first.copy(alpha = 0.9f), second.copy(alpha = 0.6f), Color(0xFF17191E)))
}

@Composable
private fun TutorialCard(step: TutorialStep, canLike: Boolean, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val (title, body) = when (step) {
        TutorialStep.Swipe -> "1/3 · Deslizá" to "← omitir. ↑ ↗ → ↘ ↓ agregan a una playlist. Podés deshacer con ↶."
        TutorialStep.Wheel -> "2/3 · Mantené presionado" to
            "Aparece la rueda. Empujá el pulgar hacia una playlist y volvé al centro para marcar varias. Soltá para confirmar; soltá a la izquierda para cancelar."
        TutorialStep.DoubleTap -> "3/3 · Doble toque" to
            if (canLike) "Tocá dos veces para agregar a Me gusta. Un toque pausa o reanuda." else "Un toque pausa o reanuda la canción."
    }
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondary,
        contentColor = Color.Black,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End)) { Text("Saltar tutorial", color = Color.Black) }
        }
    }
}

@Composable
private fun PlayerBar(state: SwipeUiState, onTogglePlayback: () -> Unit) {
    val player = state.player
    val duration = player.track?.durationMs ?: 0L
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        LinearProgressIndicator(
            progress = { if (duration > 0) player.positionMs.toFloat() / duration else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(player.positionMs), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onTogglePlayback) { Text(if (player.isPlaying) "❚❚ Pausa" else "▶ Reproducir") }
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FinishedMessage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("¡Listo!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Ordenaste todas las canciones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Elegir otra fuente") }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
