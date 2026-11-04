package com.zaka.shijuan.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 统一网络层：处理编码、UA、Referer、重试。
 *
 * 性能取舍：
 *  - 连接池放大 + 长保活，同一站点连续抓目录/正文时不再重新握手
 *  - 单请求超时收短（5s 连接 / 12s 读取），慢源尽快失败，不拖整个并发搜索
 *  - 每站点 8 并发，整机 48 并发，够 15+ 源同时打
 *  - 页面级小缓存，返回上一页、重进详情不重复下载
 */
object Net {

    private val dispatcher = Dispatcher().apply {
        maxRequests = 48
        maxRequestsPerHost = 8
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(28, 6, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    data class Resp(val ok: Boolean, val code: Int, val body: String, val error: String = "", val via: String = "")

    // ── 页面小缓存：只存最近的几十页，避免来回跳转重复下载 ──
    private val pageCache = object : LinkedHashMap<String, Resp>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resp>?): Boolean = size > 48
    }

    /** 记录每个源实际连通的 base（避开挂掉的镜像，后续请求直接走通的） */
    private val bestBase = ConcurrentHashMap<String, String>()

    fun rememberBase(sourceId: String, base: String) {
        bestBase[sourceId] = base
    }

    fun baseOf(sourceId: String): String? = bestBase[sourceId]

    /** 按站点编码解码（GBK 站点很常见，必须处理） */
    private fun decode(bytes: ByteArray, charset: String, contentType: String?): String {
        var cs = charset
        if (cs == "auto" || cs.isBlank()) cs = "utf-8"
        // 先从 meta 里嗅探
        if (cs == "utf-8") {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val m = Regex("charset=[\"']?([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(
                head + (contentType ?: "")
            )
            if (m != null) {
                val sniff = m.groupValues[1].lowercase()
                if (sniff.contains("gb")) cs = "gbk"
                else if (sniff.contains("big5")) cs = "big5"
            }
        }
        val javaCharset = when {
            cs.contains("gb") -> Charset_gbk
            cs.contains("big5") -> charset("big5")
            else -> charset("utf-8")
        }
        return runCatching { String(bytes, javaCharset) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private val Charset_gbk = charset("gbk")

    fun get(
        url: String,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
        useCache: Boolean = false,
    ): Resp {
        val key = "G|$url|$charset"
        if (useCache) pageCache[key]?.let { return it }
        val builder = Request.Builder().url(safeUrl(url)).header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Connection", "keep-alive")
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = exec(builder.build(), charset)
        if (useCache && resp.ok) pageCache[key] = resp
        return resp
    }

    fun post(
        url: String,
        body: Map<String, String>,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        val fb = FormBody.Builder()
        body.forEach { (k, v) -> fb.add(k, v) }
        val builder = Request.Builder().url(safeUrl(url))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(fb.build())
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return exec(builder.build(), charset)
    }

    fun getBytes(url: String, referer: String = ""): ByteArray? = runCatching {
        val b = Request.Builder().url(safeUrl(url)).header("User-Agent", UA)
        if (referer.isNotBlank()) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()

    /**
     * 只取 1 字节探资源在不在：返回（状态码，总大小）。
     * 有些图床对不存在的页回 200 + 一张几十字节的占位图，所以必须拿到大小。
     */
    fun probe(url: String, headers: Map<String, String> = emptyMap(), referer: String = ""): Pair<Int, Long> =
        runCatching {
            val b = Request.Builder().url(safeUrl(url)).get()
                .header("User-Agent", UA)
                .header("Range", "bytes=0-0")
            if (referer.isNotBlank()) b.header("Referer", referer)
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r ->
                val len = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                r.code to len
            }
        }.getOrDefault(0 to -1L)

    /** 下载二进制到本地文件，返回字节数（-1 表示失败） */
    fun downloadTo(url: String, dest: java.io.File, headers: Map<String, String> = emptyMap(), referer: String = ""): Long =
        runCatching {
            dest.parentFile?.mkdirs()
            val b = Request.Builder().url(safeUrl(url)).get().header("User-Agent", UA)
            if (referer.isNotBlank()) b.header("Referer", referer)
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return -1L
                val body = r.body ?: return -1L
                val len = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                if (len > 0 && dest.length() != len) return -1L
                if (dest.length() < 512) return -1L
                dest.length()
            }
        }.getOrDefault(-1L)

    /** 预连接：点开书籍前先把到该站的 TCP/TLS 握手做掉，省掉首包那一两百毫秒 */
    fun warmUp(url: String) {
        runCatching {
            val b = Request.Builder().url(safeUrl(url)).head()
                .header("User-Agent", UA)
                .build()
            client.newCall(b).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        }
    }

    private fun exec(request: Request, charset: String, depth: Int = 0): Resp = try {
        client.newCall(request).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val text = decode(bytes, charset, r.header("Content-Type"))
            val finalUrl = r.request.url.toString()
            if (!r.isSuccessful) return Resp(false, r.code, text, "HTTP ${r.code}", finalUrl)

            // 有些站用 meta / JS 做跳转，普通 HTTP 客户端不会跟，这里手动跟一层
            if (depth < 2 && text.length < 3000) {
                val jump = findMetaRedirect(text)
                if (jump != null && jump != finalUrl) {
                    val abs = Rules.absUrl(finalUrl, jump)
                    if (abs.startsWith("http")) {
                        val again = Request.Builder().url(abs)
                            .header("User-Agent", UA)
                            .header("Referer", finalUrl)
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .build()
                        return exec(again, charset, depth + 1)
                    }
                }
            }
            Resp(true, r.code, text, via = finalUrl)
        }
    } catch (e: Exception) {
        Resp(false, 0, "", friendlyError(e))
    }

    /**
     * 识别「文档已移动」这类真跳转。
     * 只认明确声明跳转的三处：meta refresh、location.href / location.replace、window.location。
     * 以前还顺手匹配页面里第一个 href / url=，结果章节页里只要有条导航链接
     * 就会被当成跳转目标跟过去，正文自然就抓不到了——这个坑要堵死。
     */
    private fun findMetaRedirect(html: String): String? {
        Regex(
            "(?i)http-equiv=[\"']?refresh[\"']?[^>]{0,200}?content=[\"'][^;\"'>]*;\\s*url=([^\"'>]+)",
        ).find(html)?.let { return it.groupValues[1].trim() }

        Regex(
            "(?i)(?:location\\.href\\s*=|location\\.replace\\s*\\(\\s*|window\\.location(?:\\.href)?\\s*=\\s*)[\"']([^\"']+)[\"']",
        ).find(html)?.let {
            val v = it.groupValues[1].trim()
            if (v.startsWith("http") || v.startsWith("/")) return v
        }
        return null
    }

    fun friendlyError(e: Throwable): String {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("timeout") || msg.contains("timed out") -> "连接超时"
            msg.contains("unable to resolve") || msg.contains("nodename") -> "域名解析失败"
            msg.contains("ssl") || msg.contains("certificate") -> "证书错误"
            msg.contains("refused") -> "连接被拒绝"
            msg.contains("reset") -> "连接被重置"
            else -> e.javaClass.simpleName
        }
    }

    private fun safeUrl(u: String): String {
        var s = u.trim()
        if (!s.startsWith("http")) s = "https://$s"
        return s.replace(" ", "%20")
    }

    /** 简单并发下载（给体积不大的页面用） */
    fun parallel(urls: List<String>, charset: String = "utf-8"): List<Resp> {
        val out = arrayOfNulls<Resp>(urls.size)
        val threads = urls.mapIndexed { i, u ->
            Thread { out[i] = get(u, charset) }.apply { isDaemon = true; start() }
        }
        threads.forEach { runCatching { it.join(16000) } }
        return out.map { it ?: Resp(false, 0, "", "未返回") }
    }

    fun readAll(input: java.io.InputStream): ByteArray {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            bo.write(buf, 0, n)
        }
        return bo.toByteArray()
    }
}
