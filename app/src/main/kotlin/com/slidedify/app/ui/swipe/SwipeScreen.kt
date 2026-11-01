package com.slidedify.app.ui.swipe

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.slidedify.app.ui.theme.CancelColor
import com.slidedify.app.ui.theme.arrow
import com.slidedify.app.ui.theme.color
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

@Composable
fun SwipeRoute(viewModel: SwipeViewModel = hiltViewModel()) {
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
        onSwipe = viewModel::onSwipe,
        onWheelCommit = viewModel::onWheelCommit,
        onDoubleTap = viewModel::onDoubleTap,
        onTogglePlayback = viewModel::onTogglePlayback,
        onUndo = viewModel::onUndo,
    )
}

@Composable
fun SwipeScreen(
    state: SwipeUiState,
    snackbar: SnackbarHostState,
    onSwipe: (SwipeResult) -> Unit,
    onWheelCommit: (Set<Slot>) -> Unit,
    onDoubleTap: () -> Unit,
    onTogglePlayback: () -> Unit,
    onUndo: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(state, onUndo)
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.isFinished -> FinishedMessage(Modifier.fillMaxSize())
                else -> {
                    GestureArea(
                        state = state,
                        onSwipe = onSwipe,
                        onWheelCommit = onWheelCommit,
                        onDoubleTap = onDoubleTap,
                        onTogglePlayback = onTogglePlayback,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    PlayerBar(state, onTogglePlayback)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: SwipeUiState, onUndo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ordenando", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(state.sourceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (state.total > 0) {
            Text(
                "${(state.position + 1).coerceAtMost(state.total)}/${state.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUndo, enabled = state.canUndo) { Text("↶ Deshacer") }
    }
}

@Composable
private fun GestureArea(
    state: SwipeUiState,
    onSwipe: (SwipeResult) -> Unit,
    onWheelCommit: (Set<Slot>) -> Unit,
    onDoubleTap: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val config = rememberGestureConfig(state.enabledSlots)
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
            step.feedback?.let { haptics.performHapticFeedback(it.hapticType()) }
            when (val outcome = step.outcome) {
                is GestureOutcome.Tap -> when (tapDetector.onTap(outcome)) {
                    TapKind.FirstTap -> {
                        singleTapJob = scope.launch {
                            delay(tapDetector.timeoutMs)
                            onTogglePlayback()
                        }
                    }

                    TapKind.DoubleTap -> {
                        singleTapJob?.cancel()
                        onDoubleTap()
                    }
                }

                is GestureOutcome.Swipe -> scope.launch {
                    (previous as? GestureState.Dragging)?.let { cardOffset.snapTo(it.offset.toOffset()) }
                    cardOffset.animateTo(flyOutTarget(outcome.result, flyDistance), tween(180))
                    onSwipe(outcome.result)
                    cardOffset.snapTo(Offset.Zero)
                }

                GestureOutcome.SwipeAborted -> scope.launch { cardOffset.animateTo(Offset.Zero, spring()) }
                is GestureOutcome.WheelCommit -> onWheelCommit(outcome.slots)
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
                isLiked = state.isLiked,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .graphicsLayer {
                        translationX = cardOffset.value.x
                        translationY = cardOffset.value.y
                        rotationZ = cardOffset.value.x / 40f
                        val wheelOpen = gesture is GestureState.Wheel
                        alpha = if (wheelOpen) 0.5f else 1f
                    },
            )

            (gesture as? GestureState.Dragging)?.let { drag ->
                DragHint(drag, state.targets, config.touchSlop * 3, Modifier.align(Alignment.TopCenter))
            }

            if (gesture == GestureState.Idle) {
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
                WheelOverlay(wheel, state.targets, Modifier.fillMaxSize())
            }
        }
    }
}

private fun flyOutTarget(result: SwipeResult, distance: Float): Offset = when (result) {
    SwipeResult.Skip -> Offset(-distance, 0f)
    is SwipeResult.ToSlot -> {
        val rad = Math.toRadians(result.slot.centerDegrees.toDouble())
        Offset((cos(rad) * distance).toFloat(), (-sin(rad) * distance).toFloat())
    }
}

private fun GestureFeedback.hapticType(): HapticFeedbackType = when (this) {
    GestureFeedback.WheelOpened -> HapticFeedbackType.LongPress
    GestureFeedback.SlotSelected -> HapticFeedbackType.ToggleOn
    GestureFeedback.SlotDeselected -> HapticFeedbackType.ToggleOff
    GestureFeedback.EnteredCancel -> HapticFeedbackType.Reject
}

/** Shows where the card will go if released now. */
@Composable
private fun DragHint(drag: GestureState.Dragging, targets: Map<Slot, SlotTarget>, minDistance: Float, modifier: Modifier) {
    val offset = drag.offset
    if (offset.length < minDistance) return
    val (text, color) = when (val sector = SectorLayout.sectorOf(offset, deadZoneDegrees = 5f)) {
        Sector.Left -> "✕ Omitir" to CancelColor
        is Sector.Destination -> {
            val target = targets[sector.slot] ?: return
            "${sector.slot.arrow()} ${target.label}" to sector.slot.color()
        }

        null -> return
    }
    Surface(
        modifier = modifier.padding(top = 8.dp),
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.9f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.Black,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TrackCard(track: Track, isLiked: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
                    contentDescription = "Portada de ${track.album.name}",
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
                    listOfNotNull(track.artistNames, track.album.name, track.album.releaseYear?.toString())
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (isLiked) "♥" else "♡",
                style = MaterialTheme.typography.headlineSmall,
                color = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun PlayerBar(state: SwipeUiState, onTogglePlayback: () -> Unit) {
    val player = state.player
    val duration = player.track?.durationMs ?: 0L
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
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
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Letra sincronizada: próximamente (LRCLIB)",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FinishedMessage(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("¡Listo!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Ordenaste todas las canciones.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
