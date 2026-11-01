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

    /** Skip the track (whole non-destination sector when swiping; its upper half on the wheel). */
    data object Skip : Sector

    /** Do nothing (only on the wheel: lower half of the non-destination sector). */
    data object Cancel : Sector
}

/**
 * Splits the circle evenly between [destinationCount] destinations plus one extra sector:
 * 1 destination → halves, 2 → thirds, 3 → quarters (↑ → ↓ + extra)...
 *
 * The extra sector is centred on the left (180°). When swiping it means **skip**; on the wheel it is
 * split in two: **skip** (upper half) and **cancel** (lower half). Destinations keep the same place
 * in both modes, so the user learns a single map. Destinations follow clockwise from the top.
 *
 * Angles use the math convention: 0° = right, 90° = up, counter-clockwise, range [0, 360).
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
    private val effectiveDeadZone = minOf(deadZoneDegrees, sectorWidth / 8f)

    /** Screen angle (math convention) of the centre of [slot]. */
    fun centerOf(slot: Slot): Float = toScreen(normalize(EXTRA_CENTER - (slot.index + 1) * sectorWidth))

    /** Centre of the whole extra sector (skip when swiping). */
    val extraCenter: Float get() = toScreen(EXTRA_CENTER)

    /** Wheel only: centres of the skip (upper) and cancel (lower) halves. */
    val wheelSkipCenter: Float get() = toScreen(normalize(EXTRA_CENTER - sectorWidth / 4f))
    val wheelCancelCenter: Float get() = toScreen(normalize(EXTRA_CENTER + sectorWidth / 4f))

    /**
     * Sector for a screen vector, or `null` when it has no length or falls in the dead zone
     * between two sectors (better to do nothing than guess wrong).
     * With [wheel] the extra sector is split into [Sector.Skip] and [Sector.Cancel].
     */
    fun sectorOf(vector: Point, wheel: Boolean = false): Sector? {
        if (vector.length == 0f) return null
        val angle = toLayout(angleOf(vector))
        // Clockwise distance from the lower edge of the extra sector.
        val fromStart = normalize(EXTRA_CENTER - angle + sectorWidth / 2f)
        val position = fromStart / sectorWidth
        val index = floor(position).toInt().coerceIn(0, destinationCount)
        val intoSector = (position - index) * sectorWidth
        if (destinationCount > 0 && (intoSector < effectiveDeadZone || intoSector > sectorWidth - effectiveDeadZone)) return null
        if (index > 0) return Sector.Destination(Slot(index - 1))
        if (!wheel) return Sector.Skip
        val half = sectorWidth / 2f
        if (kotlin.math.abs(intoSector - half) < effectiveDeadZone) return null
        // Going clockwise from the lower edge: first the lower half (cancel), then the upper (skip).
        return if (intoSector < half) Sector.Cancel else Sector.Skip
    }

    private fun toScreen(layoutDegrees: Float) = if (mirrored) normalize(180f - layoutDegrees) else layoutDegrees

    private fun toLayout(screenDegrees: Float) = toScreen(screenDegrees) // Mirroring is its own inverse.

    companion object {
        const val EXTRA_CENTER = 180f
        const val DEFAULT_DEAD_ZONE = 5f

        /** Angle of a screen-space vector in degrees, with y flipped so that "up" is 90°. */
        fun angleOf(vector: Point): Float {
            val degrees = Math.toDegrees(atan2(-vector.y.toDouble(), vector.x.toDouble())).toFloat()
            return normalize(degrees)
        }

        fun normalize(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    }
}
