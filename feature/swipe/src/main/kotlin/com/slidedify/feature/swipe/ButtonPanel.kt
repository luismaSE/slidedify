package com.slidedify.feature.swipe

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track

/** Whether to replace gestures with buttons. In [ButtonMode.Auto] it follows TalkBack (touch exploration). */
@Composable
fun rememberButtonModeActive(mode: ButtonMode): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var touchExploration by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return when (mode) {
        ButtonMode.On -> true
        ButtonMode.Off -> false
        ButtonMode.Auto -> touchExploration
    }
}

/**
 * Accessible alternative to gestures: toggle the playlists, then "Agregar" ("Omitir" is in the bottom bar).
 * Same result as the wheel, but every control is a large, labelled button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ButtonPanel(track: Track, state: SwipeUiState, actions: SwipeActions) {
    var selected by remember(track.id) { mutableStateOf(emptySet<Slot>()) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.targets.forEach { target ->
                val isSelected = target.slot in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { selected = if (isSelected) selected - target.slot else selected + target.slot },
                    label = { Text(target.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = target.slot.color(),
                        selectedLabelColor = Color.Black,
                    ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canLike) {
                OutlinedButton(onClick = actions.onDoubleTap) { Text(if (state.isLiked) "♥ Me gusta" else "♡ Me gusta") }
            }
            Button(
                onClick = { actions.onWheelCommit(selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(if (selected.isEmpty()) "Agregar" else "Agregar (${selected.size})") }
        }
    }
}
