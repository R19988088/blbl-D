package blbl.cat3399.core.image

import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import java.util.LinkedHashSet
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ImageLoader {
    private const val FADE_IN_DURATION_MS = 120
    private const val REQUEST_START_INTERVAL_MS = 83L
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLoads = ArrayDeque<LoadRequest>()
    private var drainingLoads = false
    private val animatedUrls = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            val added = super.add(element)
            if (size > 300) remove(iterator().next())
            return added
        }
    }
    // Glide owns request reuse, cancellation, memory cache, and disk cache for cover thumbnails.
    private val requestOptions =
        RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .dontTransform()

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)
        val previous = view.getTag(R.id.tag_image_loader_url) as? String
        if (previous == normalized && normalized != null) return
        view.setTag(R.id.tag_image_loader_url, normalized)
        pendingLoads.removeAll { it.view == view }
        view.animate().cancel()
        view.alpha = 1f
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

    private fun startLoad(view: ImageView, normalized: String) {
        val request =
            Glide.with(view)
                .load(glideUrl(normalized))
                .apply(requestOptions)
                .signature(ObjectKey(normalized))
                .placeholder(placeholder)
                .error(placeholder)
        if (animatedUrls.add(normalized)) {
            request.transition(DrawableTransitionOptions.withCrossFade(FADE_IN_DURATION_MS))
        } else {
            request.dontAnimate()
        }.into(view)
    }

    fun clear(view: ImageView) {
        view.setTag(R.id.tag_image_loader_url, null)
        view.animate().cancel()
        view.alpha = 1f
        pendingLoads.removeAll { it.view == view }
        Glide.with(view).clear(view)
        view.setImageDrawable(placeholder)
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
