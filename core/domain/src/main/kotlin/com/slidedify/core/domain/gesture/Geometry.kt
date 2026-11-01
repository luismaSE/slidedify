package com.slidedify.core.domain.gesture

import com.slidedify.core.model.Slot
import kotlin.math.atan2
import kotlin.math.floor
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

    /** Releasing here does nothing (swipe springs back, wheel is cancelled). */
    data object Cancel : Sector
}

/**
 * Splits the circle evenly between [destinationCount] destinations plus one cancel sector:
 * 1 destination → halves, 2 → thirds, 3 → quarters (↑ → ↓ + cancel)...
 *
 * Angles use the math convention: 0° = right, 90° = up, counter-clockwise, range [0, 360).
 * The cancel sector is centred on the left (180°); destinations follow clockwise from the top.
 * With [mirrored] (left-handed mode) everything is flipped horizontally.
 */
data class SectorLayout(
    val destinationCount: Int,
    val mirrored: Boolean = false,
    /** Ambiguous zone around each boundary; capped so small sectors stay usable. */
    val deadZoneDegrees: Float = DEFAULT_DEAD_ZONE,
) {
    init {
        require(destinationCount >= 0) { "destinationCount must be >= 0" }
    }

    val sectorWidth: Float = 360f / (destinationCount + 1)
    private val effectiveDeadZone = minOf(deadZoneDegrees, sectorWidth / 6f)

    /** Screen angle (math convention) of the centre of [slot]. */
    fun centerOf(slot: Slot): Float = toScreen(normalize(CANCEL_CENTER - (slot.index + 1) * sectorWidth))

    val cancelCenter: Float get() = toScreen(CANCEL_CENTER)

    /**
     * Sector for a screen vector, or `null` when it has no length or falls in the dead zone
     * between two sectors (better to do nothing than guess wrong).
     */
    fun sectorOf(vector: Point): Sector? {
        if (vector.length == 0f || destinationCount == 0) return if (vector.length == 0f) null else Sector.Cancel
        val angle = toLayout(angleOf(vector))
        // Distance travelled clockwise from the cancel centre, shifted so sector 0 starts at 0.
        val fromCancel = normalize(CANCEL_CENTER - angle + sectorWidth / 2f)
        val position = fromCancel / sectorWidth
        val index = floor(position).toInt().coerceIn(0, destinationCount)
        val intoSector = (position - index) * sectorWidth
        if (intoSector < effectiveDeadZone || intoSector > sectorWidth - effectiveDeadZone) return null
        return if (index == 0) Sector.Cancel else Sector.Destination(Slot(index - 1))
    }

    private fun toScreen(layoutDegrees: Float) = if (mirrored) normalize(180f - layoutDegrees) else layoutDegrees

    private fun toLayout(screenDegrees: Float) = toScreen(screenDegrees) // Mirroring is its own inverse.

    companion object {
        const val CANCEL_CENTER = 180f
        const val DEFAULT_DEAD_ZONE = 5f

        /** Angle of a screen-space vector in degrees, with y flipped so that "up" is 90°. */
        fun angleOf(vector: Point): Float {
            val degrees = Math.toDegrees(atan2(-vector.y.toDouble(), vector.x.toDouble())).toFloat()
            return normalize(degrees)
        }

        fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    }
}
