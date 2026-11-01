package com.slidedify.core.domain.gesture

import com.slidedify.core.model.Slot

/** Tunables, in pixels and milliseconds. The UI converts dp to px before building this. */
data class GestureConfig(
    /** Movement below this is still considered "holding still". */
    val touchSlop: Float,
    /** Holding still this long opens the wheel. */
    val holdTimeoutMs: Long = 300,
    /** Distance a released drag must travel to count as a swipe. */
    val swipeDistance: Float,
    /** Release speed (px/s) that counts as a fling even for a short drag. */
    val flingVelocity: Float,
    /** Wheel: pushing beyond this radius toggles the slot under the finger. */
    val wheelActivationRadius: Float,
    /** Wheel: coming back inside this radius re-arms the next toggle. Must be < activation radius. */
    val wheelResetRadius: Float,
    val deadZoneDegrees: Float = 5f,
    /** Slots that have playlists assigned; the others cannot be selected. */
    val enabledSlots: Set<Slot> = Slot.entries.toSet(),
    /** Left-handed mode: playlists on the left half, skip/cancel on the right. */
    val mirrored: Boolean = false,
) {
    init {
        require(wheelResetRadius < wheelActivationRadius) { "Reset radius must be smaller than activation radius" }
    }
}

sealed interface PointerInput {
    data class Down(val position: Point, val timeMs: Long) : PointerInput

    data class Move(val position: Point, val timeMs: Long) : PointerInput

    /** [velocity] in px/s, as measured by the platform velocity tracker. */
    data class Up(val position: Point, val timeMs: Long, val velocity: Point = Point.Zero) : PointerInput

    /** Fired by the UI when [GestureConfig.holdTimeoutMs] elapsed since [Down] without leaving [GestureState.Pressing]. */
    data object HoldTimeout : PointerInput

    /** The system cancelled the gesture (e.g. a parent took over the pointer). */
    data object Cancel : PointerInput
}

sealed interface GestureState {
    data object Idle : GestureState

    data class Pressing(val origin: Point, val downTimeMs: Long) : GestureState

    data class Dragging(val origin: Point, val current: Point) : GestureState {
        val offset: Point get() = current - origin
    }

    /**
     * Joystick wheel centred on [origin]. A slot toggles when the finger crosses the activation radius
     * outwards while [armed]; it re-arms when the finger returns near the centre.
     */
    data class Wheel(
        val origin: Point,
        val current: Point,
        val selected: Set<Slot>,
        val armed: Boolean,
        /** Sector under the finger while it is outside the activation radius. */
        val hovered: Sector?,
    ) : GestureState
}

sealed interface SwipeResult {
    data object Skip : SwipeResult

    data class ToSlot(val slot: Slot) : SwipeResult
}

sealed interface GestureOutcome {
    data class Tap(val position: Point, val timeMs: Long) : GestureOutcome

    data class Swipe(val result: SwipeResult) : GestureOutcome

    /** Drag released without reaching a valid swipe: the card should spring back. */
    data object SwipeAborted : GestureOutcome

    data class WheelCommit(val slots: Set<Slot>) : GestureOutcome

    data object WheelCancelled : GestureOutcome

    /** Wheel released with nothing selected. */
    data object WheelDismissed : GestureOutcome
}

/** Moments worth a haptic tick. */
enum class GestureFeedback { WheelOpened, SlotSelected, SlotDeselected, EnteredCancel }

data class GestureStep(
    val state: GestureState,
    val outcome: GestureOutcome? = null,
    val feedback: GestureFeedback? = null,
)

/**
 * Pure state machine for every gesture on the sorting screen: tap, swipe and the joystick wheel.
 * It knows nothing about Compose, so it can be unit-tested exhaustively.
 */
class GestureReducer(private val config: GestureConfig) {

    fun reduce(state: GestureState, input: PointerInput): GestureStep = when (input) {
        is PointerInput.Cancel -> GestureStep(GestureState.Idle)
        is PointerInput.Down -> onDown(state, input)
        is PointerInput.Move -> onMove(state, input)
        is PointerInput.Up -> onUp(state, input)
        is PointerInput.HoldTimeout -> onHoldTimeout(state)
    }

    private fun onDown(state: GestureState, input: PointerInput.Down): GestureStep =
        if (state is GestureState.Idle) {
            GestureStep(GestureState.Pressing(input.position, input.timeMs))
        } else {
            GestureStep(state) // Extra fingers are ignored.
        }

    private fun onHoldTimeout(state: GestureState): GestureStep {
        if (state !is GestureState.Pressing) return GestureStep(state)
        val wheel = GestureState.Wheel(
            origin = state.origin,
            current = state.origin,
            selected = emptySet(),
            armed = true,
            hovered = null,
        )
        return GestureStep(wheel, feedback = GestureFeedback.WheelOpened)
    }

    private fun onMove(state: GestureState, input: PointerInput.Move): GestureStep = when (state) {
        is GestureState.Pressing ->
            if ((input.position - state.origin).length > config.touchSlop) {
                GestureStep(GestureState.Dragging(state.origin, input.position))
            } else {
                GestureStep(state)
            }

        is GestureState.Dragging -> GestureStep(state.copy(current = input.position))
        is GestureState.Wheel -> moveWheel(state, input.position)
        GestureState.Idle -> GestureStep(state)
    }

    private fun moveWheel(state: GestureState.Wheel, position: Point): GestureStep {
        val vector = position - state.origin
        val radius = vector.length
        val outside = radius >= config.wheelActivationRadius
        val sector = if (outside) SectorLayout.sectorOf(vector, config.deadZoneDegrees, config.mirrored) else null

        var selected = state.selected
        var armed = state.armed
        var feedback: GestureFeedback? = null

        if (radius <= config.wheelResetRadius) armed = true

        if (armed && sector is Sector.Destination && sector.slot in config.enabledSlots) {
            armed = false
            if (sector.slot in selected) {
                selected = selected - sector.slot
                feedback = GestureFeedback.SlotDeselected
            } else {
                selected = selected + sector.slot
                feedback = GestureFeedback.SlotSelected
            }
        }

        if (sector == Sector.Left && state.hovered != Sector.Left) {
            armed = false
            feedback = GestureFeedback.EnteredCancel
        }

        val next = state.copy(current = position, selected = selected, armed = armed, hovered = sector)
        return GestureStep(next, feedback = feedback)
    }

    private fun onUp(state: GestureState, input: PointerInput.Up): GestureStep = when (state) {
        is GestureState.Pressing -> GestureStep(GestureState.Idle, GestureOutcome.Tap(input.position, input.timeMs))
        is GestureState.Dragging -> {
            val result = classifySwipe(input.position - state.origin, input.velocity)
            val outcome = result?.let(GestureOutcome::Swipe) ?: GestureOutcome.SwipeAborted
            GestureStep(GestureState.Idle, outcome)
        }

        is GestureState.Wheel -> {
            val vector = input.position - state.origin
            val releasedOnCancel = vector.length >= config.wheelActivationRadius &&
                SectorLayout.sectorOf(vector, config.deadZoneDegrees, config.mirrored) == Sector.Left
            val outcome = when {
                releasedOnCancel -> GestureOutcome.WheelCancelled
                state.selected.isEmpty() -> GestureOutcome.WheelDismissed
                else -> GestureOutcome.WheelCommit(state.selected)
            }
            GestureStep(GestureState.Idle, outcome)
        }

        GestureState.Idle -> GestureStep(state)
    }

    /**
     * A swipe counts when the drag is long enough, or short but fast (a fling). The direction comes
     * from the displacement, or from the velocity for short flings.
     */
    fun classifySwipe(displacement: Point, velocity: Point): SwipeResult? {
        val direction = when {
            displacement.length >= config.swipeDistance -> displacement
            velocity.length >= config.flingVelocity -> velocity
            else -> return null
        }
        return when (val sector = SectorLayout.sectorOf(direction, config.deadZoneDegrees, config.mirrored)) {
            Sector.Left -> SwipeResult.Skip
            is Sector.Destination -> if (sector.slot in config.enabledSlots) SwipeResult.ToSlot(sector.slot) else null
            null -> null
        }
    }
}
