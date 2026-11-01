package com.slidedify.core.domain.gesture

import com.slidedify.core.model.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GestureReducerTest {

    private val config = GestureConfig(
        touchSlop = 10f,
        holdTimeoutMs = 300,
        swipeDistance = 300f,
        flingVelocity = 1000f,
        wheelActivationRadius = 50f,
        wheelResetRadius = 30f,
        deadZoneDegrees = 5f,
    )
    private val reducer = GestureReducer(config)
    private val origin = Point(500f, 1000f)

    // Screen offsets from the origin (y grows downwards).
    private val up = Point(0f, -80f)
    private val down = Point(0f, 80f)
    private val right = Point(80f, 0f)
    private val left = Point(-80f, 0f)
    private val center = Point(0f, 0f)

    /** Feeds inputs in order and returns every step. */
    private fun run(vararg inputs: PointerInput, from: GestureState = GestureState.Idle): List<GestureStep> {
        var state = from
        return inputs.map { input -> reducer.reduce(state, input).also { state = it.state } }
    }

    private fun downAt(time: Long = 0) = PointerInput.Down(origin, time)

    private fun moveBy(offset: Point, time: Long = 0) =
        PointerInput.Move(Point(origin.x + offset.x, origin.y + offset.y), time)

    private fun upBy(offset: Point, velocity: Point = Point.Zero) =
        PointerInput.Up(Point(origin.x + offset.x, origin.y + offset.y), 0, velocity)

    // --- Tap & swipe -------------------------------------------------------------------------

    @Test
    fun `press and release without moving is a tap`() {
        val steps = run(downAt(), upBy(Point(3f, 3f)))
        assertIs<GestureOutcome.Tap>(steps.last().outcome)
        assertEquals(GestureState.Idle, steps.last().state)
    }

    @Test
    fun `long drag to the left is a skip`() {
        val steps = run(downAt(), moveBy(Point(-350f, 0f)), upBy(Point(-350f, 0f)))
        assertEquals(GestureOutcome.Swipe(SwipeResult.Skip), steps.last().outcome)
    }

    @Test
    fun `long diagonal drag reaches the diagonal slot`() {
        val steps = run(downAt(), moveBy(Point(250f, 250f)), upBy(Point(250f, 250f)))
        assertEquals(GestureOutcome.Swipe(SwipeResult.ToSlot(Slot.DOWN_RIGHT)), steps.last().outcome)
    }

    @Test
    fun `short fast flick uses the velocity direction`() {
        val steps = run(downAt(), moveBy(Point(0f, -40f)), upBy(Point(0f, -40f), velocity = Point(0f, -2000f)))
        assertEquals(GestureOutcome.Swipe(SwipeResult.ToSlot(Slot.UP)), steps.last().outcome)
    }

    @Test
    fun `short slow drag springs back`() {
        val steps = run(downAt(), moveBy(Point(100f, 0f)), upBy(Point(100f, 0f)))
        assertEquals(GestureOutcome.SwipeAborted, steps.last().outcome)
    }

    @Test
    fun `swipe toward a disabled slot springs back`() {
        val limited = GestureReducer(config.copy(enabledSlots = setOf(Slot.UP)))
        assertNull(limited.classifySwipe(Point(400f, 0f), Point.Zero))
        assertEquals(SwipeResult.ToSlot(Slot.UP), limited.classifySwipe(Point(0f, -400f), Point.Zero))
    }

    @Test
    fun `moving before the hold timeout starts a drag instead of the wheel`() {
        val steps = run(downAt(), moveBy(Point(30f, 0f)), PointerInput.HoldTimeout)
        assertIs<GestureState.Dragging>(steps.last().state)
    }

    // --- Wheel -------------------------------------------------------------------------------

    private fun openWheel(): List<GestureStep> = run(downAt(), moveBy(Point(4f, 0f)), PointerInput.HoldTimeout)

    private fun wheelState(): GestureState = openWheel().last().state

    @Test
    fun `holding still opens the wheel`() {
        val step = openWheel().last()
        assertIs<GestureState.Wheel>(step.state)
        assertEquals(GestureFeedback.WheelOpened, step.feedback)
    }

    @Test
    fun `push out and release adds to a single playlist`() {
        val steps = run(moveBy(right), upBy(right), from = wheelState())
        assertEquals(GestureFeedback.SlotSelected, steps.first().feedback)
        assertEquals(GestureOutcome.WheelCommit(setOf(Slot.RIGHT)), steps.last().outcome)
    }

    @Test
    fun `push out and back several times selects several playlists`() {
        val steps = run(
            moveBy(up), moveBy(center),
            moveBy(right), moveBy(center),
            moveBy(down), moveBy(center),
            upBy(center),
            from = wheelState(),
        )
        assertEquals(GestureOutcome.WheelCommit(setOf(Slot.UP, Slot.RIGHT, Slot.DOWN)), steps.last().outcome)
    }

    @Test
    fun `going from up to down through the centre does not select the slots in between`() {
        val steps = run(
            moveBy(up),
            moveBy(Point(10f, -10f)), // back near centre
            moveBy(Point(20f, 20f)), // still inside the activation radius
            moveBy(down),
            upBy(down),
            from = wheelState(),
        )
        assertEquals(GestureOutcome.WheelCommit(setOf(Slot.UP, Slot.DOWN)), steps.last().outcome)
    }

    @Test
    fun `sliding along the rim without returning does not select more slots`() {
        val steps = run(
            moveBy(up),
            moveBy(Point(60f, -60f)), // up-right, still outside
            moveBy(right),
            upBy(right),
            from = wheelState(),
        )
        assertEquals(GestureOutcome.WheelCommit(setOf(Slot.UP)), steps.last().outcome)
    }

    @Test
    fun `jitter between reset and activation radius does not toggle twice`() {
        val steps = run(
            moveBy(right), // select
            moveBy(Point(40f, 0f)), // between reset (30) and activation (50): stays disarmed
            moveBy(right), // must not deselect
            upBy(right),
            from = wheelState(),
        )
        assertEquals(GestureOutcome.WheelCommit(setOf(Slot.RIGHT)), steps.last().outcome)
    }

    @Test
    fun `pushing again on a selected slot deselects it`() {
        val steps = run(
            moveBy(up), moveBy(center),
            moveBy(right), moveBy(center),
            moveBy(up), // deselect
            upBy(center),
            from = wheelState(),
        )
        assertEquals(GestureFeedback.SlotDeselected, steps[4].feedback)
        assertEquals(GestureOutcome.WheelCommit(setOf(Slot.RIGHT)), steps.last().outcome)
    }

    @Test
    fun `releasing on the left half cancels even with selections`() {
        val steps = run(moveBy(up), moveBy(center), moveBy(left), upBy(left), from = wheelState())
        assertEquals(GestureFeedback.EnteredCancel, steps[2].feedback)
        assertEquals(GestureOutcome.WheelCancelled, steps.last().outcome)
    }

    @Test
    fun `releasing in the centre with nothing selected dismisses the wheel`() {
        val steps = run(upBy(center), from = wheelState())
        assertEquals(GestureOutcome.WheelDismissed, steps.last().outcome)
    }

    @Test
    fun `disabled slots cannot be selected on the wheel`() {
        val limited = GestureReducer(config.copy(enabledSlots = setOf(Slot.UP)))
        var state: GestureState = wheelState()
        listOf(moveBy(right), moveBy(center), moveBy(up), upBy(up)).forEach { input ->
            val step = limited.reduce(state, input)
            state = step.state
            step.outcome?.let { assertEquals(GestureOutcome.WheelCommit(setOf(Slot.UP)), it) }
        }
    }

    @Test
    fun `system cancel resets to idle`() {
        val steps = run(moveBy(up), PointerInput.Cancel, from = wheelState())
        assertEquals(GestureState.Idle, steps.last().state)
        assertNull(steps.last().outcome)
    }

    // --- Left-handed (mirrored) -----------------------------------------------------------------

    @Test
    fun `mirrored swipe to the right skips and to the left reaches RIGHT slot`() {
        val mirrored = GestureReducer(config.copy(mirrored = true))
        assertEquals(SwipeResult.Skip, mirrored.classifySwipe(Point(400f, 0f), Point.Zero))
        assertEquals(SwipeResult.ToSlot(Slot.RIGHT), mirrored.classifySwipe(Point(-400f, 0f), Point.Zero))
        assertEquals(SwipeResult.ToSlot(Slot.UP_RIGHT), mirrored.classifySwipe(Point(-300f, -300f), Point.Zero))
        assertEquals(SwipeResult.ToSlot(Slot.UP), mirrored.classifySwipe(Point(0f, -400f), Point.Zero))
    }

    @Test
    fun `mirrored wheel cancels on the right half`() {
        val mirrored = GestureReducer(config.copy(mirrored = true))
        var state: GestureState = wheelState()
        var last: GestureStep? = null
        listOf(moveBy(left), moveBy(center), moveBy(right), upBy(right)).forEach { input ->
            last = mirrored.reduce(state, input).also { state = it.state }
        }
        assertEquals(GestureOutcome.WheelCancelled, last?.outcome)
    }
}
