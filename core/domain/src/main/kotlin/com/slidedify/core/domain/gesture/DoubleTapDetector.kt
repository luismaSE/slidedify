package com.slidedify.core.domain.gesture

enum class TapKind {
    /** May still become a double tap: wait [DoubleTapDetector.timeoutMs] before treating it as single. */
    FirstTap,
    DoubleTap,
}

/** Tells a double tap (like) from a single tap (play/pause). */
class DoubleTapDetector(val timeoutMs: Long = 300, private val maxDistance: Float) {
    private var last: GestureOutcome.Tap? = null

    fun onTap(tap: GestureOutcome.Tap): TapKind {
        val previous = last
        return if (
            previous != null &&
            tap.timeMs - previous.timeMs <= timeoutMs &&
            (tap.position - previous.position).length <= maxDistance
        ) {
            last = null
            TapKind.DoubleTap
        } else {
            last = tap
            TapKind.FirstTap
        }
    }
}
