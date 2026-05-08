package blbl.cat3399.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.collection.LruCache
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

object ImageLoader {
    private const val TAG = "ImageLoader"
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val inFlight = WeakHashMap<ImageView, Job>()
    private val pendingDisplay = WeakHashMap<ImageView, Bitmap>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decodeDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private var displayDeferred: Boolean = false

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun loadInto(view: ImageView, url: String?) {
        val normalized = normalizeImageUrl(url)

        if (normalized == null) {
            view.setTag(R.id.tag_image_loader_url, null)
            inFlight.remove(view)?.cancel()
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            return
        }

        val lastUrl = view.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == normalized) {
            // If we already have a non-placeholder image for the same URL, keep it to prevent
            // flicker on rebind (e.g. switching tabs triggers notifyItemRangeChanged).
            val drawable = view.drawable
            if (drawable != null && drawable !== placeholder) {
                inFlight.remove(view)?.cancel()
                return
            }
            // If the same URL is already loading, keep the current placeholder.
            val inFlightJob = inFlight[view]
            if (inFlightJob != null && inFlightJob.isActive) return
        } else {
            view.setTag(R.id.tag_image_loader_url, normalized)
            inFlight.remove(view)?.cancel()
        }

        val cached = cache.get(normalized)
        if (cached != null) {
            displayOrDefer(view, normalized, cached)
            return
        }

        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
        val job = scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { BiliClient.getBytes(normalized) }
                val targetSize = viewTargetSize(view)
                val bmp = withContext(decodeDispatcher) { decodeBitmap(bytes, targetSize) }
                if (bmp != null) {
                    cache.put(normalized, bmp)
                    if ((view.getTag(R.id.tag_image_loader_url) as? String) == normalized) {
                        displayOrDefer(view, normalized, bmp)
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "load failed url=$normalized", t)
            }
        }
        inFlight[view] = job
    }

    fun clear(view: ImageView) {
        view.setTag(R.id.tag_image_loader_url, null)
        inFlight.remove(view)?.cancel()
        pendingDisplay.remove(view)
        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
    }

    fun setDisplayDeferred(deferred: Boolean) {
        if (displayDeferred == deferred) return
        displayDeferred = deferred
        if (!deferred) flushPendingDisplay()
    }

    private fun displayOrDefer(
        view: ImageView,
        normalized: String,
        bmp: Bitmap,
    ) {
        if ((view.getTag(R.id.tag_image_loader_url) as? String) != normalized) return
        if (displayDeferred) {
            pendingDisplay[view] = bmp
        } else {
            pendingDisplay.remove(view)
            view.setImageBitmap(bmp)
        }
    }

    private fun flushPendingDisplay() {
        if (pendingDisplay.isEmpty()) return
        val pending = pendingDisplay.entries.toList()
        pendingDisplay.clear()
        pending.forEach { (view, bmp) ->
            val normalized = view.getTag(R.id.tag_image_loader_url) as? String ?: return@forEach
            if (cache.get(normalized) === bmp) view.setImageBitmap(bmp)
        }
    }

    private data class TargetSize(
        val width: Int,
        val height: Int,
    )

    private fun viewTargetSize(view: ImageView): TargetSize? {
        val width = view.width.takeIf { it > 0 } ?: view.measuredWidth.takeIf { it > 0 }
        val height = view.height.takeIf { it > 0 } ?: view.measuredHeight.takeIf { it > 0 }
        if (width == null || height == null) return null
        val density = view.resources.displayMetrics.density
        val guardScale = if (density >= 2f) 1.25f else 1.5f
        return TargetSize(
            width = (width * guardScale).toInt().coerceAtLeast(width),
            height = (height * guardScale).toInt().coerceAtLeast(height),
        )
    }

    private fun decodeBitmap(bytes: ByteArray, targetSize: TargetSize?): Bitmap? {
        if (targetSize == null) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val bounds =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetSize)
            }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetSize: TargetSize,
    ): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetSize.width && sourceHeight / (sample * 2) >= targetSize.height) {
            sample *= 2
        }
        return sample
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

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        return maxMemory / 16
    }
}
