package blbl.cat3399.feature.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import android.view.TextureView
import android.view.View
import blbl.cat3399.R
import android.widget.ImageView

/**
 * Real-time backdrop blur for the player chrome. Frames are read from the video TextureView
 * (not from MediaCodec's surface), downscaled, blurred with RenderScript and drawn behind the
 * bottom control bar and the top-left UP card.
 */
internal class PlayerBlurBackdrop(private val activity: PlayerActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var bottomVisible = false
    private var topVisible = false
    private var rs: RenderScript? = null
    private var blurScript: ScriptIntrinsicBlur? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            capture()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun update(visible: Boolean) {
        bottomVisible = visible
        activity.binding.videoBlurBackdrop.visibility = if (visible) View.VISIBLE else View.GONE
        syncRunning()
    }

    fun updateTop(visible: Boolean) {
        topVisible = visible
        activity.binding.videoBlurUpCard.visibility = if (visible) View.VISIBLE else View.GONE
        syncRunning()
    }

    private fun syncRunning() {
        if (bottomVisible || topVisible) {
            if (!running) {
                running = true
                handler.post(tick)
            }
        } else {
            stop()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        activity.binding.videoBlurBackdrop.setImageDrawable(null)
        activity.binding.videoBlurUpCard.setImageDrawable(null)
        blurScript?.destroy()
        blurScript = null
        rs?.destroy()
        rs = null
    }

    private fun capture() {
        if (!bottomVisible && !topVisible) return
        val player = activity.binding.playerView
        if (player.width <= 0 || player.height <= 0) return
        val textureView = activity.binding.videoTextureView
        if (!textureView.isAvailable) return

        val full = runCatching { textureView.getBitmap(CAPTURE_WIDTH, CAPTURE_HEIGHT) }.getOrNull()
            ?: return
        if (bottomVisible) {
            blurRegion(full, player, activity.binding.videoBlurBackdrop)
        }
        if (topVisible) {
            blurRegion(full, player, activity.binding.videoBlurUpCard)
        }
        full.recycle()
    }

    private fun blurRegion(full: Bitmap, player: View, target: ImageView) {
        if (target.width <= 0 || target.height <= 0) return
        val playerLoc = IntArray(2)
        val targetLoc = IntArray(2)
        player.getLocationOnScreen(playerLoc)
        target.getLocationOnScreen(targetLoc)
        val left = (targetLoc[0] - playerLoc[0]).coerceAtLeast(0)
        val top = (targetLoc[1] - playerLoc[1]).coerceAtLeast(0)
        val right = (left + target.width).coerceAtMost(player.width)
        val bottom = (top + target.height).coerceAtMost(player.height)
        if (right <= left || bottom <= top) return

        val bx = (left.toFloat() * full.width / player.width).toInt().coerceIn(0, full.width - 1)
        val by = (top.toFloat() * full.height / player.height).toInt().coerceIn(0, full.height - 1)
        val bw = ((right - left).toFloat() * full.width / player.width).toInt().coerceAtLeast(1)
            .coerceAtMost(full.width - bx)
        val bh = ((bottom - top).toFloat() * full.height / player.height).toInt().coerceAtLeast(1)
            .coerceAtMost(full.height - by)
        val crop = Bitmap.createBitmap(full, bx, by, bw, bh)
        runCatching { showBlurred(crop, target) }
            .onFailure { Log.w(TAG, "texture blur failed", it) }
        crop.recycle()
    }

    private fun showBlurred(source: Bitmap, target: ImageView) {
        val smallW = SMALL_WIDTH
        val smallH = (source.height.toFloat() * smallW / source.width.toFloat())
            .toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)

        val script = ensureScript()
        var current = small
        repeat(BLUR_PASSES) { pass ->
            val input = Allocation.createFromBitmap(script.rs, current)
            val output = Allocation.createTyped(script.rs, input.type)
            script.blur.setRadius(BLUR_RADIUS)
            script.blur.setInput(input)
            script.blur.forEach(output)
            val blurred = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            output.copyTo(blurred)
            input.destroy()
            output.destroy()
            if (pass > 0) current.recycle()
            current = blurred
        }
        small.recycle()

        if (target.id == R.id.video_blur_up_card) {
            val radius = activity.resources.getDimensionPixelSize(R.dimen.player_up_quick_card_radius).toFloat()
            val rounded = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(rounded)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawRoundRect(RectF(0f, 0f, current.width.toFloat(), current.height.toFloat()), radius, radius, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(current, 0f, 0f, paint)
            current.recycle()
            target.setImageBitmap(rounded)
        } else {
            target.setImageBitmap(current)
        }
    }

    private class BlurScript(val rs: RenderScript, val blur: ScriptIntrinsicBlur)

    private fun ensureScript(): BlurScript {
        val existingRs = rs
        val existingBlur = blurScript
        if (existingRs != null && existingBlur != null) return BlurScript(existingRs, existingBlur)
        val newRs = RenderScript.create(activity)
        val newBlur = ScriptIntrinsicBlur.create(newRs, Element.U8_4(newRs))
        rs = newRs
        blurScript = newBlur
        return BlurScript(newRs, newBlur)
    }

    private companion object {
        const val TAG = "PlayerBlurBackdrop"
        const val FRAME_INTERVAL_MS = 50L
        const val CAPTURE_WIDTH = 480
        const val CAPTURE_HEIGHT = 270
        const val SMALL_WIDTH = 240
        const val BLUR_RADIUS = 8f
        const val BLUR_PASSES = 1
    }
}
