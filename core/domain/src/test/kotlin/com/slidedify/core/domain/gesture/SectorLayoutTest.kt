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

    private fun SectorLayout.at(degrees: Double) = sectorOf(vectorAt(degrees))

    private fun dest(i: Int) = Sector.Destination(Slot(i))

    @Test
    fun `screen up is 90 degrees`() {
        assertEquals(90f, SectorLayout.angleOf(Point(0f, -10f)), 0.001f)
        assertEquals(270f, SectorLayout.angleOf(Point(0f, 10f)), 0.001f)
        assertEquals(180f, SectorLayout.angleOf(Point(-10f, 0f)), 0.001f)
    }

    @Test
    fun `one playlist splits the circle in halves`() {
        val layout = SectorLayout(1)
        assertEquals(180f, layout.sectorWidth)
        assertEquals(dest(0), layout.at(0.0))
        assertEquals(dest(0), layout.at(60.0))
        assertEquals(dest(0), layout.at(300.0))
        assertEquals(Sector.Cancel, layout.at(180.0))
        assertEquals(Sector.Cancel, layout.at(120.0))
        assertNull(layout.at(90.0)) // boundary
    }

    @Test
    fun `two playlists split the circle in thirds`() {
        val layout = SectorLayout(2)
        assertEquals(120f, layout.sectorWidth)
        assertEquals(60f, layout.centerOf(Slot(0)))
        assertEquals(300f, layout.centerOf(Slot(1)))
        assertEquals(dest(0), layout.at(60.0))
        assertEquals(dest(1), layout.at(300.0))
        assertEquals(Sector.Cancel, layout.at(180.0))
    }

    @ParameterizedTest(name = "3 playlists: {0}° -> {1}")
    @CsvSource("90, 0", "0, 1", "270, 2", "60, 0", "330, 1", "240, 2")
    fun `three playlists are up, right and down`(degrees: Double, index: Int) {
        assertEquals(dest(index), SectorLayout(3).at(degrees))
    }

    @Test
    fun `eight playlists still have usable sectors`() {
        val layout = SectorLayout(8)
        assertEquals(40f, layout.sectorWidth)
        (0 until 8).forEach { i -> assertEquals(dest(i), layout.at(layout.centerOf(Slot(i)).toDouble())) }
        assertEquals(Sector.Cancel, layout.at(180.0))
    }

    @Test
    fun `no playlists means everything cancels`() {
        assertEquals(Sector.Cancel, SectorLayout(0).at(0.0))
        assertNull(SectorLayout(0).sectorOf(Point.Zero))
    }

    @Test
    fun `mirrored layout puts cancel on the right`() {
        val layout = SectorLayout(3, mirrored = true)
        assertEquals(Sector.Cancel, layout.at(0.0))
        assertEquals(dest(1), layout.at(180.0))
        assertEquals(dest(0), layout.at(90.0))
        assertEquals(0f, layout.cancelCenter)
        assertEquals(180f, layout.centerOf(Slot(1)))
    }
}
