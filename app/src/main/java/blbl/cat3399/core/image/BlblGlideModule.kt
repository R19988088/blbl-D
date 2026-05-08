package blbl.cat3399.core.image

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule

@GlideModule
class BlblGlideModule : AppGlideModule() {
    override fun applyOptions(
        context: Context,
        builder: GlideBuilder,
    ) {
        builder.setMemoryCache(LruResourceCache(MEMORY_CACHE_BYTES))
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, DISK_CACHE_BYTES))
    }

    override fun isManifestParsingEnabled(): Boolean = false

    private companion object {
        private const val MEMORY_CACHE_BYTES = 96L * 1024L * 1024L
        private const val DISK_CACHE_BYTES = 512L * 1024L * 1024L
    }
}
