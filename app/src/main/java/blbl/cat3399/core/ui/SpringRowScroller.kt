package blbl.cat3399.core.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Row scrolling driven by a damped spring instead of a fixed-duration interpolator.
 *
 * The difference from the interpolator path is not the curve — a fixed-duration interpolator
 * always spends the full duration, whether or not a frame was dropped, and it cannot carry
 * velocity between presses. Here every frame integrates the spring against real elapsed time,
 * so a dropped frame is skipped (the position simply jumps to where physics says it should be)
 * rather than being stretched, and a repeated press starts from the current position *and*
 * velocity, the way a real spring would.
 *
 * x(t) = target * (1 - e^(-ζωt)(cos(ω_d t) + ζω/ω_d * sin(ω_d t))
 * v(t) = target * (ω²/ω_d) * e^(-ζωt) * sin(ω_d t)
 *
 * RecyclerView's own scroller is not used, so SCROLL_STATE_IDLE never fires for this motion;
 * the caller gets the settle callback explicitly instead, which is what the grid's focus
 * hand-off is built around.
 */
class SpringRowScroller(private val recyclerView: RecyclerView) {

    private var animator: ValueAnimator? = null

    fun isRunning(): Boolean = animator?.isRunning == true

    /** Stops any in-flight spring without firing its settle callback (a new press owns the motion). */
    fun cancel() {
        val running = animator ?: return
        animator = null
        running.cancel()
    }

    fun scrollBy(dy: Int, dampingRatio: Float, frequencyHz: Float, onSettled: () -> Unit) {
        if (dy == 0) {
            onSettled()
            return
        }
        cancel()
        recyclerView.stopScroll()

        val target = dy.toFloat()
        val omega = (2.0 * Math.PI * frequencyHz).toFloat()
        val zeta = dampingRatio.coerceIn(0.3f, 0.99f)
        val omegaD = omega * sqrt(1f - zeta * zeta)
        val decay = zeta * omega
        if (omegaD <= 0f) {
            recyclerView.scrollBy(0, dy)
            onSettled()
            return
        }

        // Five time constants is where the spring is within ~0.7% of target; the last fraction
        // of a pixel is snapped in onAnimationEnd.
        val settleSeconds = 5f / decay
        var scrolled = 0

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (settleSeconds * 1000f).toLong().coerceIn(120L, 900L)
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.currentPlayTime / 1000f
                val x = target * (1f - exp(-decay * t) * (cos(omegaD * t) + decay / omegaD * sin(omegaD * t)))
                val want = x.roundToInt()
                val delta = want - scrolled
                if (delta != 0) {
                    recyclerView.scrollBy(0, delta)
                    scrolled = want
                }
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator !== animation) return
                        animator = null
                        val remaining = target.roundToInt() - scrolled
                        if (remaining != 0 && abs(remaining) < 8) {
                            recyclerView.scrollBy(0, remaining)
                        }
                        onSettled()
                    }
                },
            )
        }
        animator = anim
        anim.start()
    }
}
