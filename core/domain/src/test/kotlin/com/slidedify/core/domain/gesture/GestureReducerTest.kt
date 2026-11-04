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
        // 3 destinations: Slot(0) up, Slot(1) right, Slot(2) down; skip on the left (wheel: skip above, cancel below).
        layout = SectorLayout(3),
    )
    private val reducer = GestureReducer(config)
    private val UP = Slot(0)
    private val RIGHT = Slot(1)
    private val DOWN = Slot(2)
    private val origin = Point(500f, 1000f)

    // Screen offsets from the origin (y grows downwards).
    private val up = Point(0f, -80f)
    private val down = Point(0f, 80f)
    private val right = Point(80f, 0f)
    private val upperLeft = Point(-70f, -40f) // wheel: skip
    private val lowerLeft = Point(-70f, 40f) // wheel: cancel
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
    fun `long drag to the left skips`() {
        val steps = run(downAt(), moveBy(Point(-350f, 0f)), upBy(Point(-350f, 0f)))
        assertEquals(GestureOutcome.Skip, steps.last().outcome)
    }

    @Test
    fun `long drag reaches the destination in that direction`() {
        val steps = run(downAt(), moveBy(Point(0f, 350f)), upBy(Point(0f, 350f)))
        assertEquals(GestureOutcome.Swipe(DOWN), steps.last().outcome)
    }

    @Test
    fun `diagonal on a boundary is ambiguous and springs back`() {
        val steps = run(downAt(), moveBy(Point(250f, 250f)), upBy(Point(250f, 250f)))
        assertEquals(GestureOutcome.SwipeAborted, steps.last().outcome)
    }

    @Test
    fun `short fast flick uses the velocity direction`() {
        val steps = run(downAt(), moveBy(Point(0f, -40f)), upBy(Point(0f, -40f), velocity = Point(0f, -2000f)))
        assertEquals(GestureOutcome.Swipe(UP), steps.last().outcome)
    }

    @Test
    fun `short slow drag springs back`() {
        val steps = run(downAt(), moveBy(Point(100f, 0f)), upBy(Point(100f, 0f)))
        assertEquals(GestureOutcome.SwipeAborted, steps.last().outcome)
    }

    @Test
    fun `with one playlist the whole right half adds to it`() {
        val single = GestureReducer(config.copy(layout = SectorLayout(1)))
        assertEquals(Sector.Destination(Slot(0)), single.classifySwipe(Point(400f, -200f), Point.Zero))
        assertEquals(Sector.Destination(Slot(0)), single.classifySwipe(Point(400f, 300f), Point.Zero))
        assertEquals(Sector.Skip, single.classifySwipe(Point(-400f, 0f), Point.Zero))
    }

    // --- Multi-select by dragging, without the wheel ------------------------------------------

    private val far = 350f

    @Test
    fun `dragging on from one playlist to another adds to both`() {
        val steps = run(downAt(), moveBy(Point(0f, -far)), moveBy(Point(far, 0f)), upBy(Point(far, 0f)))
        assertEquals(GestureOutcome.WheelCommit(setOf(UP, RIGHT)), steps.last().outcome)
        assertEquals(GestureFeedback.SlotSelected, steps[1].feedback)
        assertEquals(GestureFeedback.SlotSelected, steps[2].feedback)
    }

    @Test
    fun `sliding back into a selected playlist deselects it`() {
        val steps = run(downAt(), moveBy(Point(0f, -far)), moveBy(Point(far, 0f)), moveBy(Point(0f, -far)), upBy(Point(0f, -far)))
        assertEquals(GestureOutcome.Swipe(RIGHT), steps.last().outcome)
        assertEquals(GestureFeedback.SlotDeselected, steps[3].feedback)
    }

    @Test
    fun `the cancel half clears the selection and releasing there does nothing`() {
        val lower = Point(-far, far * 0.55f) // grey zone, lower half: cancel
        val steps = run(downAt(), moveBy(Point(0f, -far)), moveBy(lower), upBy(lower))
        assertEquals(GestureFeedback.EnteredCancel, steps[2].feedback)
        assertEquals(GestureOutcome.WheelCancelled, steps.last().outcome)
    }

    @Test
    fun `without a selection the whole grey zone skips`() {
        val lower = Point(-far, far * 0.55f)
        val steps = run(downAt(), moveBy(lower), upBy(lower))
        assertEquals(GestureOutcome.Skip, steps.last().outcome)
        val upper = Point(-far, -far * 0.55f)
        assertEquals(GestureOutcome.Skip, run(downAt(), moveBy(upper), upBy(upper)).last().outcome)
    }

    @Test
    fun `selecting and coming back to the start springs back`() {
        val steps = run(downAt(), moveBy(Point(0f, -far)), moveBy(Point(0f, -40f)), upBy(Point(0f, -40f)))
        assertEquals(GestureOutcome.SwipeAborted, steps.last().outcome)
    }

    @Test
    fun `selecting and then deselecting the only playlist adds nothing`() {
        val steps = run(
            downAt(), moveBy(Point(0f, -far)), moveBy(Point(0f, -40f)), moveBy(Point(0f, -far)), upBy(Point(0f, -far * 0.9f)),
        )
        assertEquals(GestureFeedback.SlotDeselected, steps[3].feedback)
        assertEquals(GestureOutcome.SwipeAborted, steps.last().outcome)
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
        assertEquals(GestureOutcome.WheelCommit(setOf(RIGHT)), steps.last().outcome)
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
        assertEquals(GestureOutcome.WheelCommit(setOf(UP, RIGHT, DOWN)), steps.last().outcome)
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
        assertEquals(GestureOutcome.WheelCommit(setOf(UP, DOWN)), steps.last().outcome)
    }

    @Test
    fun `sliding from one sector to the next toggles it without going back to the centre`() {
        val steps = run(
            moveBy(up),
            moveBy(Point(60f, -60f)), // border between up and right: dead zone, nothing happens
            moveBy(right),
            upBy(right),
            from = wheelState(),
        )
        assertEquals(GestureFeedback.SlotSelected, steps[2].feedback)
        assertEquals(GestureOutcome.WheelCommit(setOf(UP, RIGHT)), steps.last().outcome)
    }

    @Test
    fun `sliding back into a selected sector deselects it`() {
        val steps = run(moveBy(up), moveBy(right), moveBy(up), upBy(up), from = wheelState())
        assertEquals(GestureFeedback.SlotDeselected, steps[2].feedback)
        assertEquals(GestureOutcome.WheelCommit(setOf(RIGHT)), steps.last().outcome)
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
        assertEquals(GestureOutcome.WheelCommit(setOf(RIGHT)), steps.last().outcome)
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
        assertEquals(GestureOutcome.WheelCommit(setOf(RIGHT)), steps.last().outcome)
    }

    @Test
    fun `releasing on the lower left cancels even with selections`() {
        val steps = run(moveBy(up), moveBy(center), moveBy(lowerLeft), upBy(lowerLeft), from = wheelState())
        assertEquals(GestureFeedback.EnteredCancel, steps[2].feedback)
        assertEquals(GestureOutcome.WheelCancelled, steps.last().outcome)
    }

    @Test
    fun `touching cancel clears the selection instantly and the wheel stays open to redo it`() {
        val steps = run(
            moveBy(up), moveBy(center),
            moveBy(right), moveBy(center),
            moveBy(lowerLeft), // clears both
            moveBy(center),
            moveBy(down), // pick again
            upBy(down),
            from = wheelState(),
        )
        assertEquals(emptySet(), (steps[4].state as GestureState.Wheel).selected)
        assertEquals(GestureOutcome.WheelCommit(setOf(DOWN)), steps.last().outcome)
    }

    @Test
    fun `releasing on the upper left skips the track`() {
        val steps = run(moveBy(up), moveBy(center), moveBy(upperLeft), upBy(upperLeft), from = wheelState())
        assertEquals(GestureFeedback.EnteredSkip, steps[2].feedback)
        assertEquals(GestureOutcome.Skip, steps.last().outcome)
    }

    @Test
    fun `releasing in the centre with nothing selected dismisses the wheel`() {
        val steps = run(upBy(center), from = wheelState())
        assertEquals(GestureOutcome.WheelDismissed, steps.last().outcome)
    }

    @Test
    fun `system cancel resets to idle`() {
        val steps = run(moveBy(up), PointerInput.Cancel, from = wheelState())
        assertEquals(GestureState.Idle, steps.last().state)
        assertNull(steps.last().outcome)
    }

    // --- Left-handed (mirrored) -----------------------------------------------------------------

    @Test
    fun `mirrored layout cancels on the right`() {
        val mirrored = GestureReducer(config.copy(layout = SectorLayout(3, mirrored = true)))
        assertEquals(Sector.Skip, mirrored.classifySwipe(Point(400f, 0f), Point.Zero))
        assertEquals(Sector.Destination(RIGHT), mirrored.classifySwipe(Point(-400f, 0f), Point.Zero))
        assertEquals(Sector.Destination(UP), mirrored.classifySwipe(Point(0f, -400f), Point.Zero))

        var state: GestureState = wheelState()
        var last: GestureStep? = null
        val lowerRight = Point(70f, 40f) // mirrored: cancel
        listOf(moveBy(Point(-80f, 0f)), moveBy(center), moveBy(lowerRight), upBy(lowerRight)).forEach { input ->
            last = mirrored.reduce(state, input).also { state = it.state }
        }
        assertEquals(GestureOutcome.WheelCancelled, last?.outcome)
    }
}
