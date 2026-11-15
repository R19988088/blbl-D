package blbl.cat3399.core.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Draws one soft drop shadow behind the currently focused card.
 *
 * Why a decoration instead of elevation: animating `elevation` re-computes the shadow at every
 * frame for every card. Here the blurred shadow is rasterised **once** into a bitmap and then
 * blitted on the focused card only, so the per-frame cost is a single bitmap draw no matter how
 * many cards are on screen. The alpha follows the focus lift, which is what makes the card read
 * as "lifting off" the surface rather than just scaling up.
 */
class FocusShadowDecoration(
    private val cornerRadiusPx: Float,
    private val spreadPx: Float,
    private val maxAlpha: Float = 0.30f,
    private val dropOffsetPx: Float = spreadPx * 0.35f,
) : RecyclerView.ItemDecoration() {
    private var shadow: Bitmap? = null
    private var shadowWidth = 0
    private var shadowHeight = 0

    private var focusedChild: View? = null
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val dest = RectF()

    override fun onDraw(
        canvas: Canvas,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val target = resolveFocusedChild(parent)
        if (target !== focusedChild) {
            focusedChild = target
            animateTo(parent, if (target != null) 1f else 0f)
        }
        if (progress <= 0.01f) return
        val child = target ?: return
        if (child.width <= 0 || child.height <= 0) return

        val bitmap = prepareShadow(child) ?: return
        // Follow the lift: the shadow grows with the card instead of staying fixed.
        val scale = child.scaleX.takeIf { it > 0f } ?: 1f
        val centerX = (child.left + child.right) / 2f
        val centerY = (child.top + child.bottom) / 2f
        val halfW = bitmap.width * scale / 2f
        val halfH = bitmap.height * scale / 2f
        dest.set(centerX - halfW, centerY - halfH + dropOffsetPx, centerX + halfW, centerY + halfH + dropOffsetPx)
        paint.alpha = (progress * maxAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, dest, paint)
    }

    private fun resolveFocusedChild(parent: RecyclerView): View? {
        val focused = parent.findFocus() ?: return null
        return parent.findContainingItemView(focused)
    }

    private fun animateTo(
        parent: RecyclerView,
        target: Float,
    ) {
        animator?.cancel()
        val from = progress
        if (from == target) return
        val duration = if (target > from) MotionTokens.FOCUS_LIFT_IN_MS else MotionTokens.FOCUS_LIFT_OUT_MS
        animator =
            ValueAnimator.ofFloat(from, target).apply {
                this.duration = duration
                interpolator = if (target > from) MotionTokens.springOut else MotionTokens.easeIn
                addUpdateListener { animation ->
                    progress = animation.animatedValue as Float
                    parent.invalidate()
                }
                start()
            }
    }

    private fun prepareShadow(child: View): Bitmap? {
        val width = child.width + (spreadPx * 2f).toInt()
        val height = child.height + (spreadPx * 2f).toInt()
        shadow?.let { cached ->
            if (shadowWidth == width && shadowHeight == height) return cached
        }

        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val shapePaint =
                Paint().apply {
                    color = Color.BLACK
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(spreadPx / 1.6f, BlurMaskFilter.Blur.NORMAL)
                }
            val inset = spreadPx
            canvas.drawRoundRect(
                RectF(inset, inset, width - inset, height - inset),
                cornerRadiusPx,
                cornerRadiusPx,
                shapePaint,
            )
            bitmap
        }.getOrNull().also {
            shadow = it
            shadowWidth = width
            shadowHeight = height
        }
    }

    fun release() {
        animator?.cancel()
        animator = null
        progress = 0f
        focusedChild = null
        shadow?.recycle()
        shadow = null
        shadowWidth = 0
        shadowHeight = 0
    }
}
