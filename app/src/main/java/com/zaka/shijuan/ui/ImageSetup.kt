package com.zaka.shijuan.ui

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 漫画 / 封面图统一走这里。
 *
 * 提速要点：
 *  - 磁盘缓存开到 512MB：漫画图一次翻回去不用重下
 *  - 关掉 respectCacheHeaders：国内图床大多发 no-store / no-cache，
 *    按响应头走的话每次都当没缓存，翻回去还得再下一次
 *  - 连接池加大 + 长保活：同一图床连续取图不再重新握手
 *  - 跨话预取：进阅读器就把前几张和下一页附近的图先拉到本地
 */
class ShijuanApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ComicImages.loader(this)
}

object ComicImages {

    @Volatile
    private var cached: ImageLoader? = null

    fun loader(ctx: Context): ImageLoader = cached ?: synchronized(this) {
        cached ?: build(ctx.applicationContext).also { cached = it }
    }

    fun invalidate() {
        synchronized(this) {
            cached = null
        }
    }

    private fun build(ctx: Context): ImageLoader {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        com.zaka.shijuan.data.Net.applyProxy(clientBuilder)
        val client = clientBuilder.build()
        return ImageLoader.Builder(ctx)
            .okHttpClient { client }
            .memoryCache {
                MemoryCache.Builder(ctx)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(ctx.cacheDir.resolve("comic-images"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            // 图床的缓存头基本不可信，关掉才能真正复用本地
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
    }

    /** 预热一张图：只为了塞进缓存，不显示 */
    fun prefetch(ctx: Context, url: String, headers: Map<String, String>, widthPx: Int, heightPx: Int) {
        if (url.isBlank() || url.startsWith("file://")) return
        runCatching {
            val req = ImageRequest.Builder(ctx)
                .data(url)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
                .memoryCacheKey(url)
                .diskCacheKey(url)
                .build()
            loader(ctx).enqueue(req)
        }
    }
}
