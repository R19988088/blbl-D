package blbl.cat3399.feature.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import androidx.media3.exoplayer.ExoPlayer
import blbl.cat3399.core.net.BiliClient

/**
 * Video is rendered into a dedicated TextureView instead of PlayerView's SurfaceView. TextureView
 * lets us read the current frame (getBitmap) for the backdrop blur without touching MediaCodec's
 * surface, so the decoder cannot be starved by our blur consumer.
 */
internal fun PlayerActivity.setupGlVideoRenderingIfNeeded() {
    glBlurEnabled = BiliClient.prefs.playerGlBlurBackdropEnabled
    glBlurActive = glBlurEnabled
    if (!glBlurEnabled) {
        binding.videoTextureAspect.visibility = View.GONE
        binding.subtitlePlayerView.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        binding.videoBlurBackdrop.visibility = View.GONE
        applyPlayerKeepScreenOn(glBlurEnabled = false)
        return
    }

    binding.playerView.visibility = View.INVISIBLE
    binding.videoTextureAspect.visibility = View.VISIBLE
    binding.subtitlePlayerView.visibility = View.VISIBLE
    binding.videoBlurBackdrop.visibility = View.GONE
    applyPlayerKeepScreenOn(glBlurEnabled = true)

    binding.videoTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            val surface = Surface(st)
            glVideoSurface = surface
            player?.setVideoSurface(surface)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            glVideoSurface?.release()
            glVideoSurface = null
            player?.setVideoSurface(null)
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }
    if (binding.videoTextureView.isAvailable) {
        val st = binding.videoTextureView.surfaceTexture
        if (st != null) {
            val surface = Surface(st)
            glVideoSurface = surface
            player?.setVideoSurface(surface)
        }
    }

    binding.collectionControlsBackdrop.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateGlBlurRegion()
    }
}

internal fun PlayerActivity.setGlBlurActive(active: Boolean) {
    // Re-evaluate against the real view state instead of forcing the overlay off.
    // Seeking can temporarily hide the controls while the cover list is still open;
    // an unconditional hide here made the blur vanish until the list was reopened.
    scheduleGlBlurRegionUpdate()
}

internal fun PlayerActivity.updateGlBlurRegion() {
    if (!glBlurEnabled) {
        blurBackdrop.update(false)
        blurBackdrop.updateTop(false)
        return
    }

    val topCardVisible = binding.cardUpQuick.visibility == View.VISIBLE && binding.cardUpQuick.width > 0
    blurBackdrop.updateTop(topCardVisible)

    val height = binding.playerView.height
    if (height <= 0) {
        blurBackdrop.update(false)
        return
    }

    val listVisible = binding.collectionControlsBackdrop.visibility == View.VISIBLE &&
        binding.recyclerCollectionThumbs.visibility == View.VISIBLE &&
        binding.recyclerCollectionThumbs.height in 1..(height * 3 / 5)
    val barVisible = binding.bottomBar.visibility == View.VISIBLE
    val top = when {
        listVisible -> binding.recyclerCollectionThumbs.top
        barVisible -> binding.bottomBar.top
        else -> height
    }
    if (top <= 0 || top >= height) {
        blurBackdrop.update(false)
        return
    }

    val image = binding.videoBlurBackdrop
    val lp = image.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
    if (lp.topMargin != top) {
        lp.topMargin = top
        image.layoutParams = lp
    }
    blurBackdrop.update(true)
}

internal fun PlayerActivity.scheduleGlBlurRegionUpdate() {
    if (!glBlurEnabled) return
    binding.collectionControlsBackdrop.post { updateGlBlurRegion() }
}

internal fun PlayerActivity.attachExoPlayerToActiveView(exo: ExoPlayer) {
    if (glBlurEnabled) {
        binding.playerView.player = null
        binding.subtitlePlayerView.player = exo
    } else {
        binding.subtitlePlayerView.player = null
        binding.playerView.player = exo
    }
}

internal fun PlayerActivity.detachPlayerFromViews() {
    binding.playerView.player = null
    binding.subtitlePlayerView.player = null
}

internal fun PlayerActivity.hasAttachedPlayerView(): Boolean =
    binding.playerView.player != null || binding.subtitlePlayerView.player != null

internal fun PlayerActivity.subtitleHostView(): View =
    if (glBlurEnabled) binding.subtitlePlayerView else binding.playerView

/**
 * The GL/TextureView path makes the PlayerView that carries `android:keepScreenOn` invisible, and a
 * keep-screen-on flag on an invisible view never reaches the window. MiTV's vendor power manager
 * (MitvPowerManagerService) then decides nothing is playing ("not PlayingWithSurface" +
 * "mKeepScreenOnEnable false") and starts its screensaver after its idle timeout; closing that
 * screensaver lands on the launcher instead of the player. Hold the flag on the window itself so
 * the screen stays on no matter which view renders the video.
 */
internal fun PlayerActivity.applyPlayerKeepScreenOn(glBlurEnabled: Boolean) {
    if (glBlurEnabled) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
