package blbl.cat3399.core.image

import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ImageLoader {
    private const val FADE_IN_DURATION_MS = 120
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val requestOptions =
        RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .dontTransform()

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)
        view.setTag(R.id.tag_image_loader_url, normalized)
        if (normalized == null) {
            clear(view)
            return
        }

        Glide.with(view)
            .load(glideUrl(normalized))
            .apply(requestOptions)
            .placeholder(placeholder)
            .error(placeholder)
            .transition(DrawableTransitionOptions.withCrossFade(FADE_IN_DURATION_MS))
            .into(view)
    }

    fun clear(view: ImageView) {
        view.setTag(R.id.tag_image_loader_url, null)
        view.animate().cancel()
        view.alpha = 1f
        Glide.with(view).clear(view)
        if (view.drawable == null) view.setImageDrawable(placeholder)
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
