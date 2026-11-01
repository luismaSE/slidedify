package com.slidedify.core.domain.gesture

import com.slidedify.core.model.Slot
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SectorLayoutTest {

    /** Screen vector pointing at [degrees] (math convention, 0 = right, 90 = up). */
    private fun vectorAt(degrees: Double, length: Float = 100f): Point {
        val rad = Math.toRadians(degrees)
        return Point((cos(rad) * length).toFloat(), (-sin(rad) * length).toFloat())
    }

    @Test
    fun `screen up is 90 degrees`() {
        assertEquals(90f, SectorLayout.angleOf(Point(0f, -10f)), 0.001f)
        assertEquals(270f, SectorLayout.angleOf(Point(0f, 10f)), 0.001f)
        assertEquals(180f, SectorLayout.angleOf(Point(-10f, 0f)), 0.001f)
    }

    @ParameterizedTest(name = "{0}° -> {1}")
    @CsvSource(
        "0, RIGHT", "15, RIGHT", "345, RIGHT",
        "45, UP_RIGHT", "30, UP_RIGHT", "60, UP_RIGHT",
        "90, UP", "75, UP", "105, UP",
        "315, DOWN_RIGHT", "300, DOWN_RIGHT", "330, DOWN_RIGHT",
        "270, DOWN", "255, DOWN", "285, DOWN",
    )
    fun `right half maps to slots`(degrees: Double, slot: Slot) {
        assertEquals(Sector.Destination(slot), SectorLayout.sectorOf(vectorAt(degrees), deadZoneDegrees = 5f))
    }

    @ParameterizedTest
    @CsvSource("120", "180", "240", "135", "225")
    fun `left half is the Left sector`(degrees: Double) {
        assertEquals(Sector.Left, SectorLayout.sectorOf(vectorAt(degrees), deadZoneDegrees = 5f))
    }

    @ParameterizedTest
    @CsvSource("22.5", "24", "67.5", "112.5", "110", "247.5", "292.5", "337.5")
    fun `angles near a boundary fall in the dead zone`(degrees: Double) {
        assertNull(SectorLayout.sectorOf(vectorAt(degrees), deadZoneDegrees = 5f))
    }

    @Test
    fun `zero vector has no sector`() {
        assertNull(SectorLayout.sectorOf(Point.Zero, deadZoneDegrees = 5f))
    }
}
