package com.zaka.shijuan.data

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
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

    /** 专供慢速/易被流控站点（如 SF轻小说）的独立通道：连接 12s / 读取 35s / 总 48s，严格限制单主机并发 2 */
    private val slowDispatcher = Dispatcher().apply {
        maxRequests = 8
        maxRequestsPerHost = 2
    }

    /** 代理配置：填了就走它，没填就直连 */
    data class ProxyCfg(
        val enabled: Boolean = false,
        val socks: Boolean = false,
        val host: String = "",
        val port: Int = 0,
        val user: String = "",
        val pass: String = "",
    ) {
        val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
    }

    @Volatile private var proxyCfg = ProxyCfg()

    @Volatile private var client: OkHttpClient = buildClient(ProxyCfg())
    @Volatile private var fastClient: OkHttpClient = buildFastClient(ProxyCfg())
    @Volatile private var slowClient: OkHttpClient = buildSlowClient(ProxyCfg())

    /** 改了代理立刻换新连接，不用重启 App */
    fun setProxy(cfg: ProxyCfg) {
        if (cfg == proxyCfg) return
        proxyCfg = cfg
        client = buildClient(cfg)
        fastClient = buildFastClient(cfg)
        slowClient = buildSlowClient(cfg)
        pageCache.clear()
        bestBase.clear()
        com.zaka.shijuan.ui.ComicImages.invalidate()
    }

    fun proxy(): ProxyCfg = proxyCfg

    private fun buildClient(cfg: ProxyCfg): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(28, 6, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        applyProxy(b, cfg)
        return b.build()
    }

    /** 专用于热门榜单与多源搜索的极速客户端（硬超时控制，遇挂站快速跳过） */
    private fun buildFastClient(cfg: ProxyCfg): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 3, TimeUnit.MINUTES))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
        applyProxy(b, cfg)
        return b.build()
    }

    /** 专用于慢速/易流控源（如 SF 轻小说）的慢速通道（长超时 + 限 2 并发） */
    private fun buildSlowClient(cfg: ProxyCfg): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(slowDispatcher)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .callTimeout(48, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        applyProxy(b, cfg)
        return b.build()
    }

    /** 统一为外部 OkHttpClient.Builder 配置代理（供 Coil 与更新下载器复用） */
    fun applyProxy(b: OkHttpClient.Builder, cfg: ProxyCfg = proxyCfg) {
        if (cfg.usable) {
            val type = if (cfg.socks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            b.proxy(Proxy(type, InetSocketAddress.createUnresolved(cfg.host, cfg.port)))
            if (cfg.user.isNotBlank() && !cfg.socks) {
                val cred = Credentials.basic(cfg.user, cfg.pass)
                b.proxyAuthenticator(object : Authenticator {
                    override fun authenticate(route: Route?, response: okhttp3.Response): Request? =
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder()
                            .header("Proxy-Authorization", cred).build()
                })
            }
        }
    }

    /** 桌面 UA：Cloudflare 对移动 UA 打分更低，很多站会直接 403 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    private val baseHeaders = linkedMapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "sec-ch-ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "Upgrade-Insecure-Requests" to "1",
    )

    data class Resp(val ok: Boolean, val code: Int, val body: String, val error: String = "", val via: String = "")

    // ── 页面小缓存：只存最近的几十页，避免来回跳转重复下载（加锁保证并发安全） ──
    private val pageCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Resp>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Resp>?): Boolean = size > 48
        }
    )

    /** 记录每个源实际连通的 base（避开挂掉的镜像，后续请求直接走通的） */
    private val bestBase = ConcurrentHashMap<String, String>()

    fun rememberBase(sourceId: String, base: String) {
        bestBase[sourceId] = base
    }

    fun baseOf(sourceId: String): String? = bestBase[sourceId]

    /** 按站点编码解码（GB18030 兼容 GBK/GB2312 超集，避免生僻字与扩展字乱码） */
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
                if (sniff.contains("gb")) cs = "gb18030"
                else if (sniff.contains("big5")) cs = "big5"
            }
        }
        val javaCharset = when {
            cs.contains("gb") -> Charset_gb18030
            cs.contains("big5") -> charset("big5")
            else -> charset("utf-8")
        }
        return runCatching { String(bytes, javaCharset) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private val Charset_gb18030 = charset("GB18030")

    fun get(
        url: String,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
        useCache: Boolean = false,
        fast: Boolean = false,
        slow: Boolean = false,
    ): Resp {
        val isSlow = slow || url.contains("sfacg.com")
        val key = "G|$url|$charset"
        if (useCache) synchronized(pageCache) { pageCache[key] }?.let { return it }
        val builder = Request.Builder().url(safeUrl(url))
        baseHeaders.forEach { (k, v) -> builder.header(k, v) }
        val reqUa = headers["User-Agent"] ?: UA
        val isMobile = reqUa.contains("Mobile") || reqUa.contains("Android")
        builder.header("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
        builder.header("sec-ch-ua-platform", if (isMobile) "\"Android\"" else "\"Windows\"")
        builder.header("Connection", "keep-alive")
        // 这几个头是 Cloudflare 判「真人浏览器」的重要依据，缺了容易被 403
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", if (referer.isBlank()) "none" else "same-origin")
        builder.header("Sec-Fetch-User", "?1")
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = exec(builder.build(), charset, fast = fast, slow = isSlow)
        if (useCache && resp.ok) synchronized(pageCache) { pageCache[key] = resp }
        return resp
    }

    fun post(
        url: String,
        body: Map<String, String>,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
        encoded: Boolean = false,
        fast: Boolean = false,
        slow: Boolean = false,
    ): Resp {
        val isSlow = slow || url.contains("sfacg.com")
        val fb = FormBody.Builder()
        if (encoded) {
            body.forEach { (k, v) -> fb.addEncoded(k, v) }
        } else {
            body.forEach { (k, v) -> fb.add(k, v) }
        }
        val builder = Request.Builder().url(safeUrl(url))
        baseHeaders.forEach { (k, v) -> builder.header(k, v) }
        val reqUa = headers["User-Agent"] ?: UA
        val isMobile = reqUa.contains("Mobile") || reqUa.contains("Android")
        builder.header("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
        builder.header("sec-ch-ua-platform", if (isMobile) "\"Android\"" else "\"Windows\"")
        builder.header("Content-Type", "application/x-www-form-urlencoded")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", if (referer.isBlank()) "none" else "same-origin")
        builder.header("Origin", referer.trimEnd('/'))
        builder.post(fb.build())
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return exec(builder.build(), charset, fast = fast, slow = isSlow)
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
                .header("User-Agent", headers["User-Agent"] ?: UA)
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

    /** 下载二进制到本地文件，返回字节数（-1 表示失败，并在失败时自动清理残损文件） */
    fun downloadTo(url: String, dest: java.io.File, headers: Map<String, String> = emptyMap(), referer: String = ""): Long =
        runCatching {
            dest.parentFile?.mkdirs()
            val b = Request.Builder().url(safeUrl(url)).get().header("User-Agent", headers["User-Agent"] ?: UA)
            if (referer.isNotBlank()) b.header("Referer", referer)
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    dest.delete()
                    return -1L
                }
                val body = r.body ?: run { dest.delete(); return -1L }
                val len = body.contentLength()
                body.byteStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
                }
                if (len > 0 && dest.length() != len) {
                    dest.delete()
                    return -1L
                }
                if (dest.length() < 512) {
                    dest.delete()
                    return -1L
                }
                dest.length()
            }
        }.getOrElse {
            runCatching { dest.delete() }
            -1L
        }

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

    /**
     * 请求外壳：https 被 403 时尝试降级 http。
     * 移除对网络故障/超时的隐式休眠重试，使多域名轮询与镜像降级能够尽快切换。
     */
    private fun exec(request: Request, charset: String, depth: Int = 0, fast: Boolean = false, slow: Boolean = false): Resp {
        var r = once(request, charset, depth, fast = fast, slow = slow)
        if (r.ok) return r
        if (depth == 0 && r.code == 403) {
            val httpReq = toHttp(request)
            if (httpReq != null) {
                val r2 = once(httpReq, charset, depth, fast = fast, slow = slow)
                if (r2.ok) return r2
                r = r2
            }
        }
        return r
    }

    /** https://x → http://x，已经是 http 的返回 null（别来回打） */
    private fun toHttp(request: Request): Request? {
        val u = request.url
        if (!u.isHttps) return null
        val httpUrl = u.newBuilder().scheme("http").port(80).build()
        return request.newBuilder().url(httpUrl).build()
    }

    private fun once(request: Request, charset: String, depth: Int = 0, fast: Boolean = false, slow: Boolean = false): Resp = try {
        val targetClient = when {
            slow -> slowClient
            fast -> fastClient
            else -> client
        }
        targetClient.newCall(request).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val text = decode(bytes, charset, r.header("Content-Type"))
            val finalUrl = r.request.url.toString()
            if (!r.isSuccessful) return Resp(false, r.code, text, friendlyCode(r.code), finalUrl)

            // 有些站用 meta / JS 做跳转，普通 HTTP 客户端不会跟，这里手动跟一层。
            // 只对「几乎没内容的空壳页」生效：正常章节页里常有一段
            // `location.href = "下载APP"` 之类的脚本，跟着它跳就再也抓不到正文了。
            if (depth < 2 && text.length < 1200) {
                val jump = findMetaRedirect(text)
                if (jump != null && jump != finalUrl) {
                    val abs = Rules.absUrl(finalUrl, jump)
                    if (abs.startsWith("http")) {
                        val again = Request.Builder().url(abs)
                            .header("User-Agent", UA)
                            .header("Referer", finalUrl)
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .build()
                        return exec(again, charset, depth + 1, fast = fast, slow = slow)
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

    /** HTTP 状态码翻译成用户看得懂的短句 */
    private fun friendlyCode(code: Int): String = when {
        code == 403 -> "被站点拦截 403"
        code == 401 -> "要登录 401"
        code == 404 -> "页面不存在 404"
        code == 429 -> "请求太频繁 429"
        code == 451 -> "站点拒绝访问 451"
        code in 500..599 -> "站点自己出错 $code"
        else -> "HTTP $code"
    }

    fun friendlyError(e: Throwable): String {
        val msg = (e.message ?: "").lowercase()
        val name = e.javaClass.simpleName
        return when {
            msg.contains("timeout") || msg.contains("timed out") || name.contains("Timeout") -> "连接超时"
            msg.contains("unable to resolve") || msg.contains("nodename") ||
                name.contains("UnknownHost") -> "域名解析失败"
            msg.contains("ssl") || msg.contains("certificate") || name.contains("SSL") -> "TLS 握手被拦"
            msg.contains("refused") -> "连接被拒绝"
            msg.contains("reset") -> "连接被重置"
            msg.contains("econnaborted") || msg.contains("closed") -> "连接被中断"
            proxyCfg.usable -> "连代理失败（检查代理地址和端口）"
            else -> name
        }
    }

    private fun safeUrl(u: String): String {
        var s = u.trim()
        s = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            else -> "https://$s"
        }
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
