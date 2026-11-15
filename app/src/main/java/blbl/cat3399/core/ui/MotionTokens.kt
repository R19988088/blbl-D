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
     * cubic-bezier(0.40, 0.00, 0.20, 1.00) — slow off the mark, fast through the middle, soft stop,
     * which is what Apple's list scrolling feels like at TV viewing distance.
     */
    val gridScroll: PathInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** One row of travel reads as a real scroll rather than a jump: ~0.3s, Apple's list timing. */
    const val GRID_SCROLL_MS = 280L

    /** Cover appearance: a single layer fades in and rises, no cross-dissolve. */
    const val CONTENT_APPEAR_MS = 320L

    const val FOCUS_LIFT_IN_MS = 260L
    const val FOCUS_LIFT_OUT_MS = 220L
}
