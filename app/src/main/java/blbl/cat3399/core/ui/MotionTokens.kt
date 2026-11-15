package blbl.cat3399.core.ui

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
     * Grid navigation: focus moves one row and the content follows.
     *
     * cubic-bezier(0.45, 0.00, 0.50, 1.30) — gentle start, fast middle, and the end point overshoots
     * by ~4.5% before settling back to 1.0. That last sliver is the "elastic" part: the content
     * slightly overtakes the row it settles on and springs back, which is how tvOS stops a scroll.
     * The y control point above 1.0 is deliberate; PathInterpolator only constrains x to [0, 1].
     */
    val gridScroll: PathInterpolator = PathInterpolator(0.45f, 0f, 0.50f, 1.30f)

    /** ~0.3s for one row of travel; the settle needs the extra 20ms over a plain ease-in-out. */
    const val GRID_SCROLL_MS = 300L

    /** Cover appearance: a single layer fades in and rises, no cross-dissolve. */
    const val CONTENT_APPEAR_MS = 320L

    const val FOCUS_LIFT_IN_MS = 260L
    const val FOCUS_LIFT_OUT_MS = 220L
}
