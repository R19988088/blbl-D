package blbl.cat3399.core.ui

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Shared motion tokens modelled on Apple's standard easing set.
 *
 * The point is a single rhythm across the list: content appearance, focus lift and retreat all
 * use the same curves and durations, instead of each view inventing its own interpolator.
 *
 * Curves (control points stay inside [0, 1], which is what the platform accepts):
 * - [standard]  cubic-bezier(0.32, 0.72, 0.00, 1.00)  iOS default ease for content + navigation
 * - [springOut] cubic-bezier(0.16, 1.00, 0.30, 1.00)  Apple's spring-like "arrive" curve
 * - [easeIn]    cubic-bezier(0.32, 0.00, 0.67, 0.00)  Apple's "retreat" curve
 *
 * PathInterpolator only implements computeFunction, so a single instance can be shared.
 */
object MotionTokens {
    val standard: PathInterpolator = PathInterpolator(0.32f, 0.72f, 0f, 1f)
    val springOut: PathInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val easeIn: PathInterpolator = PathInterpolator(0.32f, 0f, 0.67f, 0f)

    /**
     * Grid navigation: focus moves one row and the content follows, then floats into place.
     *
     * Two stages instead of one cubic-bezier. A bezier overshoot is pinned to 1.0 at the end, so
     * its whole bump gets squeezed into the last ~10% of the timeline (4.5% of the travel in
     * 30ms) — that reads as a snap, not a spring. Here the content eases out of the row and
     * overshoots at [ElasticSettleInterpolator.peakAt] (60%), then spends the remaining 40%
     * drifting back to rest, which is how a tvOS scroll stops.
     */
    val gridScroll: Interpolator = ElasticSettleInterpolator()

    /** ~0.32s for one row: 190ms of travel plus 130ms of settle. */
    const val GRID_SCROLL_MS = 320L

    /** Cover appearance: a single layer fades in and rises, no cross-dissolve. */
    const val CONTENT_APPEAR_MS = 320L

    const val FOCUS_LIFT_IN_MS = 260L
    const val FOCUS_LIFT_OUT_MS = 220L
}

/**
 * Eases out of the target row, overshoots by [overshoot] at [peakAt], then settles back.
 *
 * The peak is a smooth turning point (velocity reaches zero there, like anything thrown), and the
 * return is a smoothstep over the remaining [1 - peakAt], so the whole stop is one continuous
 * deceleration: no snap at the peak, no dead stop at the end.
 */
class ElasticSettleInterpolator(
    private val overshoot: Float = 0.028f,
    private val peakAt: Float = 0.60f,
) : Interpolator {
    private val ease: Interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    override fun getInterpolation(input: Float): Float {
        val t = input.coerceIn(0f, 1f)
        if (t <= peakAt) return (1f + overshoot) * ease.getInterpolation(t / peakAt)
        val r = (t - peakAt) / (1f - peakAt)
        val s = r * r * (3f - 2f * r)
        return (1f + overshoot) - overshoot * s
    }
}
