package com.slidedify.core.domain.gesture

import com.slidedify.core.model.Slot
import kotlin.math.atan2
import kotlin.math.hypot

/** A point or vector in screen pixels (y grows downwards, like Android). */
data class Point(val x: Float, val y: Float) {
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)

    val length: Float get() = hypot(x, y)

    companion object {
        val Zero = Point(0f, 0f)
    }
}

/** A region of the circle around the touch origin. */
sealed interface Sector {
    data class Destination(val slot: Slot) : Sector

    /** Whole left half: "skip" for swipes, "cancel" for the wheel. */
    data object Left : Sector
}

/**
 * Shared sector layout for swipes and the joystick wheel, so the user learns a single mental map.
 *
 * Angles use the math convention: 0° = right, 90° = up, counter-clockwise, range [0, 360).
 * Each of the five slots covers 45° on the right half; the left half (135°) is [Sector.Left].
 */
object SectorLayout {
    const val SLOT_HALF_WIDTH = 22.5f
    const val LEFT_START = 112.5f
    const val LEFT_END = 247.5f

    private val boundaries = floatArrayOf(22.5f, 67.5f, LEFT_START, LEFT_END, 292.5f, 337.5f)

    /** Angle of a screen-space vector in degrees, with y flipped so that "up" is 90°. */
    fun angleOf(vector: Point): Float {
        val degrees = Math.toDegrees(atan2(-vector.y.toDouble(), vector.x.toDouble())).toFloat()
        return if (degrees < 0f) degrees + 360f else degrees
    }

    /**
     * Sector for a vector, or `null` when the angle falls within [deadZoneDegrees] of a boundary
     * (ambiguous gesture: better to do nothing than guess wrong) or the vector has no length.
     * With [mirrored] (left-handed mode) the layout is flipped horizontally: slots on the left, skip/cancel on the right.
     */
    fun sectorOf(vector: Point, deadZoneDegrees: Float, mirrored: Boolean = false): Sector? {
        if (vector.length == 0f) return null
        val angle = angleOf(if (mirrored) Point(-vector.x, vector.y) else vector)
        if (boundaries.any { angularDistance(angle, it) < deadZoneDegrees }) return null
        if (angle > LEFT_START && angle < LEFT_END) return Sector.Left
        val slot = Slot.entries.first { angularDistance(angle, it.centerDegrees) <= SLOT_HALF_WIDTH }
        return Sector.Destination(slot)
    }

    /** Screen angle (math convention) where a layout angle is drawn, taking mirroring into account. */
    fun displayAngle(layoutDegrees: Float, mirrored: Boolean): Float =
        if (mirrored) ((180f - layoutDegrees) % 360f + 360f) % 360f else layoutDegrees

    private fun angularDistance(a: Float, b: Float): Float {
        val diff = ((a - b) % 360f + 360f) % 360f
        return if (diff > 180f) 360f - diff else diff
    }
}
