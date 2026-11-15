package blbl.cat3399.core.image

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.MotionTokens
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import java.util.LinkedHashSet
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ImageLoader {
    /**
     * A cover requested at this fraction of the target size first. The tiny decode lands within
     * a frame, so the card shows a soft block right away and then sharpens in place - the
     * progressive behaviour Apple uses, instead of a cross-dissolve between two bitmaps.
     */
    private const val THUMBNAIL_SIZE_MULTIPLIER = 0.08f

    private const val REQUEST_START_INTERVAL_MS = 48L

    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLoads = ArrayDeque<LoadRequest>()
    private var drainingLoads = false

    /** URLs that already played their appear animation; re-entering them stays instant. */
    private val appearedUrls = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (size > 300) remove(iterator().next())
            return added
        }
    }

    /** Last cover slot size actually laid out; used as the override for not-yet-measured views. */
    private var knownCoverSize: Pair<Int, Int>? = null

    // Glide owns request reuse, cancellation, memory cache, and disk cache for cover thumbnails.
    //
    // NOTE: this used to carry `.dontTransform()`, which in Glide also turns downsampling off
    // (DownsampleStrategy.NONE). Every cover was decoded at its source size (~1280x720) and
    // uploaded as a full-size texture while being drawn into a ~450x250 slot: that is ~8x the
    // GPU memory and bandwidth the card actually needs, and it was the whole reason the grid
    // GPU-bound on a box that plays games fine. Sizing is now the ImageView's measured size,
    // which is what Glide does by default - identical pixels on screen, far smaller textures.
    private val requestOptions =
        RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)

    /**
     * Cover slot size in pixels: the view's own size once it is laid out, otherwise the last
     * size seen (a freshly inflated holder is bound before it is measured).
     */
    private fun coverTargetSize(view: ImageView): Pair<Int, Int>? {
        if (view.width > 0 && view.height > 0) {
            knownCoverSize = view.width to view.height
        }
        return knownCoverSize
    }

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)
        val previous = view.getTag(R.id.tag_image_loader_url) as? String
        if (previous == normalized && normalized != null) return
        view.setTag(R.id.tag_image_loader_url, normalized)
        pendingLoads.removeAll { it.view == view }
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        if (normalized == null) {
            Glide.with(view).clear(view)
            view.setImageDrawable(placeholder)
            return
        }

        Glide.with(view).clear(view)
        view.setImageDrawable(placeholder)
        enqueueLoad(view, normalized)
    }

    private data class LoadRequest(
        val view: ImageView,
        val url: String,
    )

    private fun enqueueLoad(view: ImageView, normalized: String) {
        pendingLoads.removeAll { it.view == view }
        pendingLoads.addLast(LoadRequest(view, normalized))
        drainLoadQueue()
    }

    private fun drainLoadQueue() {
        if (drainingLoads) return
        drainingLoads = true
        mainHandler.post(object : Runnable {
            override fun run() {
                val request = pendingLoads.removeFirstOrNull()
                if (request == null) {
                    drainingLoads = false
                    return
                }
                if ((request.view.getTag(R.id.tag_image_loader_url) as? String) == request.url) {
                    startLoad(request.view, request.url)
                }
                mainHandler.postDelayed(this, REQUEST_START_INTERVAL_MS)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun startLoad(view: ImageView, normalized: String) {
        val targetSize = coverTargetSize(view)
        val builder =
            Glide.with(view)
                .load(glideUrl(normalized))
                .apply(requestOptions)
                .signature(ObjectKey(normalized))
                .placeholder(placeholder)
                .error(placeholder)
                .thumbnail(THUMBNAIL_SIZE_MULTIPLIER)
                .dontAnimate()
        if (targetSize != null) builder.override(targetSize.first, targetSize.second)

        if (appearedUrls.add(normalized)) {
            builder.listener(
                object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean = false

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        if (isFirstResource) playAppear(view)
                        return false
                    }
                },
            )
        }
        builder.into(view)
    }

    /**
     * Single-layer appearance: alpha plus a small rise, both plain RenderNode properties, so the
     * card is never re-rasterised while the cover shows up.
     */
    private fun playAppear(view: ImageView) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 4f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.CONTENT_APPEAR_MS)
            .setInterpolator(MotionTokens.standard)
            .start()
    }

    fun clear(view: ImageView) {
        view.setTag(R.id.tag_image_loader_url, null)
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
        pendingLoads.removeAll { it.view == view }
        Glide.with(view).clear(view)
        view.setImageDrawable(placeholder)
    }

    /**
     * Warms the cache at the size covers are actually drawn. Passing the real size is what makes
     * the preload useful: [preload][com.bumptech.glide.RequestBuilder.preload] without a size
     * decodes at the source size, which does not match the per-view cache key and is thrown away
     * when the card is bound.
     */
    fun preloadCovers(
        context: android.content.Context,
        urls: List<String?>,
        targetWidthPx: Int = 0,
        targetHeightPx: Int = 0,
    ) {
        val hasKnownSize = targetWidthPx > 0 && targetHeightPx > 0
        urls.asSequence()
            .mapNotNull(::normalizeImageUrl)
            .distinct()
            .forEach { normalized ->
                val builder =
                    Glide.with(context.applicationContext)
                        .load(glideUrl(normalized))
                        .apply(requestOptions)
                        .signature(ObjectKey(normalized))
                runCatching {
                    if (hasKnownSize) {
                        knownCoverSize = targetWidthPx to targetHeightPx
                        builder.preload(targetWidthPx, targetHeightPx)
                    } else {
                        builder.preload()
                    }
                }
            }
    }

    private fun glideUrl(url: String): GlideUrl {
        val userAgent = runCatching { BiliClient.prefs.userAgent }.getOrDefault(BiliClient.APP_CDN_USER_AGENT)
        val headers =
            LazyHeaders.Builder()
                .addHeader("User-Agent", userAgent)
                .addHeader("Referer", "https://www.bilibili.com/")
                .build()
        return GlideUrl(url, headers)
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }
}
