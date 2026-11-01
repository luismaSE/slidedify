package com.slidedify.feature.swipe

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.slidedify.core.domain.gesture.GestureConfig
import com.slidedify.core.domain.gesture.GestureReducer
import com.slidedify.core.domain.gesture.GestureState
import com.slidedify.core.domain.gesture.GestureStep
import com.slidedify.core.domain.gesture.Point
import com.slidedify.core.domain.gesture.PointerInput
import com.slidedify.core.model.Slot
import kotlinx.coroutines.CancellationException

/** Visual radius of the wheel; the activation radius is much smaller so gestures stay short. */
val WheelRadius = 112.dp
val WheelActivationRadius = 48.dp
private val WheelResetRadius = 32.dp

@Composable
fun rememberGestureConfig(enabledSlots: Set<Slot>): GestureConfig {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    return remember(density, viewConfiguration, enabledSlots) {
        with(density) {
            GestureConfig(
                touchSlop = viewConfiguration.touchSlop,
                holdTimeoutMs = 300,
                swipeDistance = 120.dp.toPx(),
                flingVelocity = 1_200.dp.toPx(),
                wheelActivationRadius = WheelActivationRadius.toPx(),
                wheelResetRadius = WheelResetRadius.toPx(),
                enabledSlots = enabledSlots,
            )
        }
    }
}

fun Offset.toPoint() = Point(x, y)

fun Point.toOffset() = Offset(x, y)

/**
 * Bridges Compose pointer events to the pure [GestureReducer]. All the decisions (tap, swipe,
 * wheel) live in the reducer; this only feeds it events, the hold timeout and release velocity.
 */
fun Modifier.sortGestures(config: GestureConfig, onStep: (GestureStep) -> Unit): Modifier =
    pointerInput(config) {
        val reducer = GestureReducer(config)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val holdDeadline = down.uptimeMillis + config.holdTimeoutMs
            var lastEventTime = down.uptimeMillis
            var state: GestureState = GestureState.Idle

            fun dispatch(input: PointerInput) {
                val step = reducer.reduce(state, input)
                state = step.state
                onStep(step)
            }

            dispatch(PointerInput.Down(down.position.toPoint(), down.uptimeMillis))
            try {
                while (state != GestureState.Idle) {
                    val event = if (state is GestureState.Pressing) {
                        val remaining = (holdDeadline - lastEventTime).coerceAtLeast(1)
                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    } else {
                        awaitPointerEvent()
                    }
                    if (event == null) {
                        dispatch(PointerInput.HoldTimeout)
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        dispatch(PointerInput.Cancel)
                        break
                    }
                    lastEventTime = change.uptimeMillis
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val position = change.position.toPoint()
                    when {
                        change.changedToUp() -> {
                            val v = velocity.calculateVelocity()
                            dispatch(PointerInput.Up(position, change.uptimeMillis, Point(v.x, v.y)))
                            change.consume()
                        }

                        change.positionChanged() -> {
                            dispatch(PointerInput.Move(position, change.uptimeMillis))
                            if (state !is GestureState.Pressing) change.consume()
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (state != GestureState.Idle) onStep(GestureStep(GestureState.Idle))
                throw e
            }
        }
    }
