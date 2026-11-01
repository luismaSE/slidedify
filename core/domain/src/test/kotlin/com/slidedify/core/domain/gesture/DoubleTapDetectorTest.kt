package com.slidedify.core.domain.gesture

import kotlin.test.Test
import kotlin.test.assertEquals

class DoubleTapDetectorTest {
    private val detector = DoubleTapDetector(timeoutMs = 300, maxDistance = 50f)

    private fun tap(time: Long, x: Float = 0f) = GestureOutcome.Tap(Point(x, 0f), time)

    @Test
    fun `two quick close taps are a double tap`() {
        assertEquals(TapKind.FirstTap, detector.onTap(tap(0)))
        assertEquals(TapKind.DoubleTap, detector.onTap(tap(200)))
    }

    @Test
    fun `slow second tap starts a new sequence`() {
        detector.onTap(tap(0))
        assertEquals(TapKind.FirstTap, detector.onTap(tap(500)))
        assertEquals(TapKind.DoubleTap, detector.onTap(tap(600)))
    }

    @Test
    fun `far apart taps are not a double tap`() {
        detector.onTap(tap(0))
        assertEquals(TapKind.FirstTap, detector.onTap(tap(100, x = 200f)))
    }

    @Test
    fun `a third tap after a double starts over`() {
        detector.onTap(tap(0))
        detector.onTap(tap(100))
        assertEquals(TapKind.FirstTap, detector.onTap(tap(200)))
    }
}
