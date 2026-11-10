package com.slidedify.feature.swipe

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.gesture.DoubleTapDetector
import com.slidedify.core.domain.gesture.GestureFeedback
import com.slidedify.core.domain.gesture.GestureOutcome
import com.slidedify.core.domain.gesture.GestureState
import com.slidedify.core.domain.gesture.GestureStep
import com.slidedify.core.domain.gesture.Sector
import com.slidedify.core.domain.gesture.SectorLayout
import com.slidedify.core.domain.gesture.TapKind
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Callbacks of the sorting screen, grouped to keep signatures short. */
data class SwipeActions(
    val onSwipe: (Slot) -> Unit = {},
    val onSkip: () -> Unit = {},
    val onAddDestination: (Playlist) -> Unit = {},
    val onCreatePlaylist: (String) -> Unit = {},
    val onWheelOpened: () -> Unit = {},
    val onWheelCommit: (Set<Slot>) -> Unit = {},
    val onDoubleTap: () -> Unit = {},
    val onTogglePlayback: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onSkipTutorial: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onOpenList: () -> Unit = {},
    val onJumpTo: (Int) -> Unit = {},
    val onAcceptSuggestion: () -> Unit = {},
    val onSendTo: (Set<PlaylistId>) -> Unit = {},
    val onQueueOrder: (TrackOrder, Boolean) -> Unit = { _, _ -> },
    val onOpenExternally: () -> Unit = {},
)

@Composable
fun SwipeRoute(onBack: () -> Unit, onOpenSettings: () -> Unit, viewModel: SwipeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val maxDestinations = stringResource(R.string.swipe_max_destinations, GestureMap.MAX_DESTINATIONS)
    val createFailed = stringResource(R.string.swipe_create_failed)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            // Successful actions are already visible (card moves, ♥, circle); no toasts or snackbars
            // that would cover the bottom buttons. Only problems get a short notice in the top bar.
            notice = when (event) {
                SwipeEvent.DestinationsFull -> maxDestinations
                SwipeEvent.CreatePlaylistFailed -> createFailed
                else -> null
            } ?: return@collect
            launch {
                val shown = notice
                delay(3_000)
                if (notice == shown) notice = null
            }
        }
    }

    SwipeScreen(
        state = state,
        notice = notice,
        actions = SwipeActions(
            onSwipe = viewModel::onSwipe,
            onSkip = viewModel::onSkip,
            onAddDestination = viewModel::onAddDestination,
            onCreatePlaylist = viewModel::onCreatePlaylist,
            onWheelOpened = viewModel::onWheelOpened,
            onWheelCommit = viewModel::onWheelCommit,
            onDoubleTap = viewModel::onDoubleTap,
            onTogglePlayback = viewModel::onTogglePlayback,
            onUndo = viewModel::onUndo,
            onSkipTutorial = viewModel::skipTutorial,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onRetry = viewModel::load,
            onRestart = viewModel::restart,
            onJumpTo = viewModel::jumpTo,
            onAcceptSuggestion = viewModel::onAcceptSuggestion,
            onSendTo = viewModel::onSendTo,
            onQueueOrder = viewModel::setQueueOrder,
            onOpenExternally = viewModel::onOpenExternally,
        ),
    )
}

@Composable
fun SwipeScreen(state: SwipeUiState, actions: SwipeActions, notice: String? = null) {
    val buttonMode = rememberButtonModeActive(state.settings.buttonMode)
    var showList by remember { mutableStateOf(false) }
    var coverColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(state.current?.id) { coverColor = null }
    val baseBackground = MaterialTheme.colorScheme.background
    val background by animateColorAsState(
        targetValue = coverColor?.let { lerp(baseBackground, it, 0.16f) } ?: baseBackground,
        animationSpec = tween(400),
        label = "cover tint",
    )
    val screenActions = actions.copy(onOpenList = { showList = true })
    var showAddDestination by remember { mutableStateOf(false) }
    var showMultiSend by remember { mutableStateOf(false) }
    var listOptions by remember { mutableStateOf(TrackListOptions()) }

    Scaffold(containerColor = background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TopBar(state, screenActions)
            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            when {
                state.loadError != null -> LoadError(
                    stringResource(R.string.swipe_load_error) + state.loadError.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                    actions,
                    Modifier.fillMaxSize(),
                )
                state.isLoading || state.isWaitingForTracks ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.isFinished -> FinishedMessage(actions, Modifier.fillMaxSize())
                else -> {
                    val track = state.current ?: return@Column
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        // Landscape (or a wide tablet): card on the left, controls on the right.
                        val wide = maxWidth > maxHeight && maxWidth >= 560.dp
                        val cardArea: @Composable (Modifier) -> Unit = { areaModifier ->
                            Column(areaModifier) {
                                if (state.settings.suggestions) SuggestionBar(state.suggestion, actions.onAcceptSuggestion)
                                if (buttonMode) {
                                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        TrackCard(track, state, actions, Modifier.fillMaxSize().padding(CardInset), onCoverColor = { coverColor = it })
                                    }
                                    ButtonPanel(track, state, actions)
                                } else {
                                    GestureArea(state, track, actions, Modifier.weight(1f).fillMaxWidth(), onCoverColor = { coverColor = it })
                                }
                            }
                        }
                        val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
                            Box(controlsModifier, contentAlignment = Alignment.Center) {
                                BottomBar(
                                    state,
                                    actions,
                                    onAddDestination = { showAddDestination = true },
                                    onMultiSend = { showMultiSend = true },
                                )
                            }
                        }
                        if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                cardArea(Modifier.weight(1.5f).fillMaxHeight())
                                controls(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            // On tablets in portrait the card doesn't stretch to the whole width.
                            Column(Modifier.fillMaxHeight().widthIn(max = 640.dp)) {
                                cardArea(Modifier.weight(1f).fillMaxWidth())
                                controls(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }

    if (showList) {
        TrackListSheet(
            state = state,
            filters = listOptions,
            onFiltersChange = { listOptions = it },
            onOrderChange = actions.onQueueOrder,
            onPick = { index ->
                actions.onJumpTo(index)
                showList = false
            },
            onDismiss = { showList = false },
        )
    }
    if (showMultiSend && state.current != null) {
        MultiSendSheet(
            state = state,
            onSend = {
                actions.onSendTo(it)
                showMultiSend = false
            },
            onDismiss = { showMultiSend = false },
        )
    }
    if (showAddDestination) {
        AddDestinationSheet(
            state = state,
            onPick = {
                actions.onAddDestination(it)
                showAddDestination = false
            },
            onCreate = {
                actions.onCreatePlaylist(it)
                showAddDestination = false
            },
            onDismiss = { showAddDestination = false },
        )
    }
}

@Composable
private fun TopBar(state: SwipeUiState, actions: SwipeActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.swipe_back)) }
        Column(Modifier.weight(1f)) {
            Text(
                state.sourceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val progress = if (state.total > 0) {
                    " · " + stringResource(R.string.swipe_progress, state.sortedCount, state.total) + if (state.isLoadingMore) "+" else ""
                } else {
                    ""
                }
                val moving = if (state.movesFromSource) " · " + stringResource(R.string.swipe_moving) else ""
                val progressDescription = stringResource(R.string.swipe_progress_a11y, state.sortedCount, state.total)
                Text(
                    state.providerName + progress + moving,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = progressDescription },
                )
                if (state.pendingSync > 0) {
                    Icon(
                        AppIcons.Sync,
                        contentDescription = stringResource(R.string.swipe_pending_sync, state.pendingSync),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(14.dp),
                    )
                    Text(" ${state.pendingSync}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Full song in the service's own app (also the link back its terms ask for).
        state.current?.externalUrl?.let { url ->
            val context = LocalContext.current
            IconButton(onClick = {
                actions.onOpenExternally()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }) {
                Icon(AppIcons.OpenExternal, contentDescription = stringResource(R.string.swipe_open_full, state.providerName))
            }
        }
        IconButton(onClick = actions.onOpenList, enabled = state.total > 0) { Icon(AppIcons.Queue, contentDescription = stringResource(R.string.swipe_song_list)) }
        IconButton(onClick = actions.onUndo, enabled = state.canUndo) { Icon(AppIcons.Undo, contentDescription = stringResource(R.string.swipe_undo)) }
        IconButton(onClick = actions.onOpenSettings) { Icon(AppIcons.Tune, contentDescription = stringResource(R.string.swipe_settings)) }
    }
}

@Composable
private fun GestureArea(
    state: SwipeUiState,
    track: Track,
    actions: SwipeActions,
    modifier: Modifier = Modifier,
    onCoverColor: (Color?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val settings = state.settings
    val mirrored = settings.leftHanded
    val config = rememberGestureConfig(state.targets.size, settings.holdTimeoutMs, mirrored)
    val layout = config.layout
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
                    cardOffset.animateTo(flyOutTarget(layout.centerOf(outcome.slot), flyDistance), tween(180))
                    actions.onSwipe(outcome.slot)
                    cardOffset.snapTo(Offset.Zero)
                }

                GestureOutcome.Skip -> scope.launch {
                    (previous as? GestureState.Dragging)?.let { cardOffset.snapTo(it.offset.toOffset()) }
                    cardOffset.animateTo(flyOutTarget(layout.extraCenter, flyDistance), tween(180))
                    actions.onSkip()
                    cardOffset.snapTo(Offset.Zero)
                }

                GestureOutcome.SwipeAborted -> scope.launch { cardOffset.animateTo(Offset.Zero, spring()) }
                is GestureOutcome.WheelCommit -> {
                    val drag = previous as? GestureState.Dragging
                    if (drag == null) {
                        actions.onWheelCommit(outcome.slots)
                    } else {
                        // Multi-select drag: the card flies towards the last zone, then goes to all of them.
                        scope.launch {
                            cardOffset.snapTo(drag.offset.toOffset())
                            val toward = (drag.hovered as? Sector.Destination)?.slot ?: outcome.slots.first()
                            cardOffset.animateTo(flyOutTarget(layout.centerOf(toward), flyDistance), tween(180))
                            actions.onWheelCommit(outcome.slots)
                            cardOffset.snapTo(Offset.Zero)
                        }
                    }
                }

                GestureOutcome.WheelCancelled -> if (previous is GestureState.Dragging) {
                    scope.launch { cardOffset.animateTo(Offset.Zero, spring()) }
                }

                GestureOutcome.WheelDismissed, null -> Unit
            }
        }
        val latestHandleStep by rememberUpdatedState(handleStep)
        Box(
            Modifier
                .fillMaxSize()
                .sortGestures(config) { latestHandleStep(it) },
        ) {
            val drag = gesture as? GestureState.Dragging
            val splitGrey = drag?.hadSelection == true
            val skipLabel = stringResource(R.string.swipe_skip)
            val cancelLabel = stringResource(R.string.swipe_cancel)
            val zones = remember(state.targets, layout, splitGrey, skipLabel) {
                swipeZones(state.targets, layout, withCancel = splitGrey, skipLabel = skipLabel, cancelLabel = cancelLabel)
            }
            // Before reaching a zone, preview where the drag is heading.
            val hoveredZone = drag?.let { d ->
                d.hovered?.let { if (!splitGrey && it == Sector.Cancel) Sector.Skip else it }
                    ?: d.offset.takeIf { it.length >= config.touchSlop * 3 }?.let { layout.sectorOf(it) }
            }
            val selectedZones = drag?.selected?.mapTo(mutableSetOf<Sector>()) { Sector.Destination(it) }.orEmpty()
            // A hint of the next card underneath, so the queue feels like a deck.
            if (state.position + 1 < state.total) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(start = CardInset + 14.dp, top = CardInset + 14.dp, end = CardInset + 14.dp, bottom = CardInset + 22.dp - 10.dp)
                        .graphicsLayer { alpha = if (gesture is GestureState.Wheel) 0.3f else 1f }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(CardCorner)),
                )
            }
            TrackCard(
                track = track,
                state = state,
                actions = actions,
                onCoverColor = onCoverColor,
                modifier = Modifier
                    .fillMaxSize()
                    // Inside the contour line (see EdgeGlow), leaving room at the bottom for the hint.
                    .padding(start = CardInset, top = CardInset, end = CardInset, bottom = CardInset + 22.dp)
                    .graphicsLayer {
                        translationX = cardOffset.value.x
                        translationY = cardOffset.value.y
                        rotationZ = cardOffset.value.x / 40f
                        alpha = if (gesture is GestureState.Wheel) 0.5f else 1f
                    },
            )

            // In front of the card: the contour line and the zone name must never be hidden.
            EdgeGlow(
                zones,
                hoveredZone,
                Modifier.fillMaxSize(),
                suggested = state.suggestion?.let { Sector.Destination(it.slot) },
                selected = selectedZones,
            )

            state.tutorialStep?.let { step ->
                if (gesture == GestureState.Idle) {
                    TutorialCard(step, state.canLike, actions.onSkipTutorial, Modifier.align(Alignment.TopCenter))
                }
            }

            if (gesture == GestureState.Idle && state.tutorialStep == null) {
                Text(
                    stringResource(if (state.targets.isEmpty()) R.string.swipe_hint_empty else R.string.swipe_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = ContourInset + 10.dp),
                )
            }

            (gesture as? GestureState.Wheel)?.let { wheel ->
                WheelOverlay(wheel, state.targets, layout, Modifier.fillMaxSize(), suggested = state.suggestion?.slot)
            }
        }
    }
}

private fun flyOutTarget(screenDegrees: Float, distance: Float): Offset {
    val rad = Math.toRadians(screenDegrees.toDouble())
    return Offset((cos(rad) * distance).toFloat(), (-sin(rad) * distance).toFloat())
}

private fun GestureFeedback.hapticType(): HapticFeedbackType = when (this) {
    GestureFeedback.WheelOpened -> HapticFeedbackType.LongPress
    GestureFeedback.SlotSelected -> HapticFeedbackType.ToggleOn
    GestureFeedback.SlotDeselected -> HapticFeedbackType.ToggleOff
    GestureFeedback.EnteredCancel -> HapticFeedbackType.Reject
    GestureFeedback.EnteredSkip -> HapticFeedbackType.SegmentTick
}

@Composable
private fun TrackCard(
    track: Track,
    state: SwipeUiState,
    actions: SwipeActions,
    modifier: Modifier = Modifier,
    onCoverColor: (Color?) -> Unit = {},
) {
    // Every gesture is also reachable from TalkBack's actions menu.
    val a11yActions = buildList {
        add(CustomAccessibilityAction(stringResource(R.string.swipe_skip)) { actions.onSkip(); true })
        state.targets.forEach { target ->
            add(CustomAccessibilityAction(stringResource(R.string.swipe_add_to, target.label)) { actions.onSwipe(target.slot); true })
        }
        if (state.canLike) {
            add(CustomAccessibilityAction(stringResource(if (state.isLiked) R.string.swipe_unlike else R.string.swipe_like)) { actions.onDoubleTap(); true })
        }
        add(CustomAccessibilityAction(stringResource(if (state.player.isPlaying) R.string.swipe_pause else R.string.swipe_play)) { actions.onTogglePlayback(); true })
    }
    val cardDescription = stringResource(R.string.swipe_card_a11y, track.title, track.artistNames, track.album.name)
    Card(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = cardDescription
                customActions = a11yActions
            },
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner))
                .background(placeholderBrush(track)),
            contentAlignment = Alignment.Center,
        ) {
            if (track.album.imageUrl != null) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val request = remember(track.album.imageUrl) {
                    // Software bitmap so its colours can be read for the background tint.
                    ImageRequest.Builder(context).data(track.album.imageUrl).allowHardware(false).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { success ->
                        val image = success.result.image
                        scope.launch {
                            // Purely cosmetic: any failure just leaves the plain background.
                            val rgb = withContext(Dispatchers.Default) {
                                runCatching {
                                    val palette = Palette.from(image.toBitmap()).generate()
                                    palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb
                                }.getOrNull()
                            }
                            onCoverColor(rgb?.let(::Color))
                        }
                    },
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
                // Attribution for the service's data (its terms ask for it); the full song opens from the top bar.
                if (track.externalUrl != null) {
                    Text(
                        stringResource(R.string.swipe_data_from, state.providerName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (state.canLike) {
                Icon(
                    if (state.isLiked) AppIcons.Heart else AppIcons.HeartOutline,
                    contentDescription = stringResource(if (state.isLiked) R.string.swipe_liked else R.string.swipe_not_liked),
                    tint = if (state.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Deterministic gradient per album, used until real cover art is loaded. */
private fun placeholderBrush(track: Track): Brush {
    val palette = (0 until GestureMap.MAX_DESTINATIONS).map { Slot(it).color() }
    val hash = abs(track.album.id.hashCode())
    val first = palette[hash % palette.size]
    val second = palette[(hash / palette.size + 1) % palette.size]
    return Brush.linearGradient(listOf(first.copy(alpha = 0.9f), second.copy(alpha = 0.6f), Color(0xFF17191E)))
}

@Composable
private fun TutorialCard(step: TutorialStep, canLike: Boolean, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val (title, body) = when (step) {
        TutorialStep.Swipe -> stringResource(R.string.tutorial_swipe_title) to stringResource(R.string.tutorial_swipe_text)
        TutorialStep.Wheel -> stringResource(R.string.tutorial_wheel_title) to stringResource(R.string.tutorial_wheel_text)
        TutorialStep.DoubleTap -> stringResource(R.string.tutorial_tap_title) to
            stringResource(if (canLike) R.string.tutorial_tap_like else R.string.tutorial_tap_no_like)
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
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.tutorial_skip), color = Color.Black) }
        }
    }
}

/** Progress plus the bottom controls: skip (left), play/pause (centre, big), add playlist (right). */
@Composable
private fun BottomBar(state: SwipeUiState, actions: SwipeActions, onAddDestination: () -> Unit, onMultiSend: () -> Unit) {
    val player = state.player
    val duration = player.durationMs
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(player.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { if (duration > 0) player.positionMs.toFloat() / duration else 0f },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundAction(AppIcons.Skip, stringResource(R.string.swipe_skip), actions.onSkip, Modifier.weight(1f))
            RoundAction(AppIcons.MultiSelect, stringResource(R.string.swipe_several), onMultiSend, Modifier.weight(1f))
            FilledIconButton(
                onClick = actions.onTogglePlayback,
                modifier = Modifier.size(68.dp),
            ) {
                Icon(
                    if (player.isPlaying) AppIcons.Pause else AppIcons.Play,
                    contentDescription = stringResource(if (player.isPlaying) R.string.swipe_pause else R.string.swipe_play),
                    modifier = Modifier.size(34.dp),
                )
            }
            if (state.canLike) {
                RoundAction(if (state.isLiked) AppIcons.Heart else AppIcons.HeartOutline, stringResource(R.string.swipe_like), actions.onDoubleTap, Modifier.weight(1f))
            }
            RoundAction(AppIcons.Add, stringResource(R.string.swipe_playlist), onAddDestination, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The whole thing (circle and label) is one button, so tapping the word works too.
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Adds an existing playlist as a new destination, or creates a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDestinationSheet(
    state: SwipeUiState,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.add_destination_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.add_destination_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (!state.canAddDestination) {
                Text(stringResource(R.string.add_destination_full, GestureMap.MAX_DESTINATIONS))
                return@Column
            }
            if (state.canCreatePlaylists) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.add_destination_new)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onCreate(newName) }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.add_destination_create)) }
                }
                Spacer(Modifier.height(8.dp))
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(state.addablePlaylists, key = { it.id.value }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name) },
                        modifier = Modifier.clickable { onPick(playlist) },
                    )
                }
            }
        }
    }
}

/** Card geometry, aligned with the contour line drawn by [EdgeGlow]. */
private val CardInset = ContourInset + 8.dp
private val CardCorner = ContourCorner - 8.dp

@Composable
private fun LoadError(message: String, actions: SwipeActions, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Button(onClick = actions.onRetry) { Text(stringResource(R.string.swipe_retry)) }
        TextButton(onClick = actions.onBack) { Text(stringResource(R.string.swipe_back)) }
    }
}

@Composable
private fun FinishedMessage(actions: SwipeActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.swipe_done_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.swipe_done_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = actions.onBack) { Text(stringResource(R.string.swipe_done_other)) }
        TextButton(onClick = actions.onRestart) { Text(stringResource(R.string.swipe_done_restart)) }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
