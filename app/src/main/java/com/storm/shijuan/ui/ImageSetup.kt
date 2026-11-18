package com.storm.shijuan.ui

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
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 4
        }
        val pool = okhttp3.ConnectionPool(32, 5, TimeUnit.MINUTES)
        val clientBuilder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val orig = chain.request()
                val b = orig.newBuilder()
                if (orig.header("User-Agent").isNullOrBlank()) {
                    b.header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
                    )
                }
                // 自动补齐特定图床的防盗链 Referer，避免轻小说插图、封面和漫画图片 404/403
                if (orig.header("Referer").isNullOrBlank()) {
                    val host = orig.url.host.lowercase()
                    when {
                        host.contains("cdndm5.com") || host.contains("dm5.com") -> b.header("Referer", "https://m.dm5.com/")
                        host.contains("manhuaren.com") -> b.header("Referer", "https://www.manhuaren.com/")
                        host.contains("1kkk.com") -> b.header("Referer", "https://m.1kkk.com/")
                        host.contains("baozicdn.com") || host.contains("baozimh.com") -> b.header("Referer", "https://www.baozimh.com/")
                        host.contains("777743") || host.contains("wenku8") -> b.header("Referer", "https://www.wenku8.cc/")
                        host.contains("sfacg") -> b.header("Referer", "https://m.sfacg.com/")
                        host.contains("esjzone") -> b.header("Referer", "https://www.esjzone.cc/")
                        host.contains("faloo") -> b.header("Referer", "https://wap.faloo.com/")
                        else -> b.header("Referer", "${orig.url.scheme}://${orig.url.host}/")
                    }
                }
                chain.proceed(b.build())
            }
        // 宽容处理部分老站或 CDN 图床的自签名/过期 SSL 证书与握手错误
        runCatching {
            val trustAll = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
            clientBuilder.sslSocketFactory(sslContext.socketFactory, trustAll)
            clientBuilder.hostnameVerifier { _, _ -> true }
        }
        com.storm.shijuan.data.Net.applyProxy(clientBuilder)
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
            val isJm = url.contains("/media/photos/") || headers.containsKey("X-JM")
            val req = ImageRequest.Builder(ctx)
                .data(url)
                .apply {
                    headers.forEach { (k, v) -> setHeader(k, v) }
                    if (isJm) {
                        transformations(com.storm.shijuan.data.JmDescrambleTransformation(url))
                    }
                }
                .size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
                .diskCacheKey(url)
                .build()
            loader(ctx).enqueue(req)
        }
    }
}
