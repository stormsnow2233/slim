package com.storm.shijuan.data

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    private val cookieStore = ConcurrentHashMap<String, MutableList<okhttp3.Cookie>>()

    /** 全局 CookieJar：支持需会话验证与动态 Cookie 保持的书源站点 */
    val cookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isEmpty()) return
            val list = cookieStore.getOrPut(url.host) { java.util.concurrent.CopyOnWriteArrayList() }
            for (cookie in cookies) {
                list.removeAll { it.name == cookie.name }
                list.add(cookie)
            }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val list = cookieStore[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            list.removeAll { it.expiresAt < now }
            return list
        }
    }

    // ── 统一令牌桶域名限频与防刷保护 ──
    val rateLimiter = DomainRateLimiter()

    /** 域名级请求限频：在 OkHttp 实际发包前强制等待保护间隔，防止并发/快速重试触发源站 Cloudflare 拦截 */
    fun enforceHostInterval(host: String, maxWaitMs: Long = 10_000L): Boolean {
        return rateLimiter.acquire(host, maxWaitMs)
    }

    // ── 请求 Call 级取消能力（支持按 Tag/SearchSeq 瞬时终止底层网络发包） ──
    private val activeCalls = ConcurrentHashMap<Any, MutableSet<okhttp3.Call>>()

    fun registerCall(tag: Any, call: okhttp3.Call) {
        activeCalls.getOrPut(tag) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }.add(call)
    }

    fun unregisterCall(tag: Any, call: okhttp3.Call) {
        val set = activeCalls[tag] ?: return
        set.remove(call)
        if (set.isEmpty()) activeCalls.remove(tag)
    }

    /** 强制取消并中断绑在该 tag 下的所有活跃 OkHttp 任务 */
    fun cancelCalls(tag: Any) {
        val set = activeCalls.remove(tag) ?: return
        for (call in set) {
            runCatching { call.cancel() }
        }
    }

    /** 将外部（如 WebView 拦截或登录）提取的 Cookie 串注入全局 CookieJar 并持久化 */
    fun syncCookies(host: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val cleanHost = host.removePrefix("http://").removePrefix("https://").substringBefore('/')
        val targets = if (cleanHost.startsWith("www.")) {
            listOf(cleanHost, cleanHost.removePrefix("www."))
        } else {
            listOf(cleanHost, "www.$cleanHost")
        }
        for (targetHost in targets) {
            val list = cookieStore.getOrPut(targetHost) { java.util.concurrent.CopyOnWriteArrayList() }
            val now = System.currentTimeMillis()
            val pairs = cookieHeader.split(";").map { it.trim() }.filter { it.isNotBlank() }
            for (pair in pairs) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                val cookie = runCatching {
                    okhttp3.Cookie.Builder()
                        .domain(targetHost)
                        .name(name)
                        .value(value)
                        .path("/")
                        .expiresAt(now + 30L * 24 * 3600 * 1000L)
                        .build()
                }.getOrNull() ?: continue
                list.removeAll { it.name == name }
                list.add(cookie)
            }
        }
    }

    fun getCookiesHeader(host: String): String {
        val cleanHost = host.removePrefix("http://").removePrefix("https://").substringBefore('/')
        val list = cookieStore[cleanHost] ?: return ""
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 判断响应是否触发了 Cloudflare 浏览器人机验证/质询 */
    fun isCloudflareChallenge(code: Int, body: String): Boolean {
        if (code == 403 || code == 503) {
            val lower = body.lowercase()
            return lower.contains("just a moment") ||
                lower.contains("cloudflare") ||
                lower.contains("cf-browser-verification") ||
                lower.contains("challenge-platform") ||
                lower.contains("attention required") ||
                lower.contains("cf-mitigated") ||
                lower.contains("ray id:") ||
                lower.contains("error 1020") ||
                lower.contains("access denied")
        }
        return false
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
        com.storm.shijuan.ui.ComicImages.invalidate()
    }

    fun proxy(): ProxyCfg = proxyCfg

    private fun buildClient(cfg: ProxyCfg): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cookieJar(cookieJar)
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

    /** 专用于热门榜单与多源搜索的高响应客户端 */
    private fun buildFastClient(cfg: ProxyCfg): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .cookieJar(cookieJar)
            .connectionPool(ConnectionPool(16, 3, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        applyProxy(b, cfg)
        return b.build()
    }

    /** 专用于慢速/易流控源（如 SF 轻小说）的慢速通道（长超时 + 限 2 并发） */
    private fun buildSlowClient(cfg: ProxyCfg): OkHttpClient {
        val b = OkHttpClient.Builder()
            .dispatcher(slowDispatcher)
            .cookieJar(cookieJar)
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

    @Volatile private var isLoggingInWenku8 = false

    /**
     * 为轻小说文库建立登录凭证（仅在用户显式提供个人账号时调用；严禁内置共享凭证）。
     * 若未提供账号密码，则返回 false，交由 UI 引导用户通过 WebView 登录。
     */
    fun ensureWenku8Login(username: String = "", password: String = ""): Boolean {
        if (username.isBlank() || password.isBlank()) return false
        if (isLoggingInWenku8) return false
        isLoggingInWenku8 = true
        return try {
            val fb = FormBody.Builder()
                .add("username", username.trim())
                .add("password", password.trim())
                .add("usecookie", "315360000")
                .add("action", "login")
                .build()
            val req = Request.Builder()
                .url("https://www.wenku8.cc/login.php?do=submit")
                .post(fb)
                .header("User-Agent", UA)
                .header("Referer", "https://www.wenku8.cc/")
                .build()
            client.newCall(req).execute().use { resp ->
                val setCookies = resp.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    val combined = setCookies.joinToString("; ") { it.substringBefore(';') }
                    syncCookies("wenku8.cc", combined)
                    syncCookies("www.wenku8.cc", combined)
                    syncCookies("wenku8.net", combined)
                    syncCookies("www.wenku8.net", combined)
                    true
                } else false
            }
        } catch (_: Exception) {
            false
        } finally {
            isLoggingInWenku8 = false
        }
    }

    fun get(
        url: String,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
        useCache: Boolean = false,
        fast: Boolean = false,
        slow: Boolean = false,
        tag: Any? = null,
        timeoutMs: Long = 0L,
        profile: SourceRequestProfile? = null,
    ): Resp {
        val isSlow = slow || url.contains("sfacg.com")
        val key = "G|$url|$charset"
        if (useCache) synchronized(pageCache) { pageCache[key] }?.let { return it }
        val builder = Request.Builder().url(safeUrl(url))
        baseHeaders.forEach { (k, v) -> builder.header(k, v) }

        val reqUa = profile?.userAgent?.ifBlank { headers["User-Agent"] } ?: headers["User-Agent"] ?: UA
        val isMobile = when (profile?.deviceType?.uppercase()) {
            "MOBILE" -> true
            "DESKTOP" -> false
            else -> reqUa.contains("Mobile") || reqUa.contains("Android")
        }
        builder.header("User-Agent", reqUa)
        builder.header("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
        builder.header("sec-ch-ua-platform", if (isMobile) "\"Android\"" else "\"Windows\"")
        builder.header("Connection", "keep-alive")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", if (referer.isBlank()) "none" else "same-origin")
        builder.header("Sec-Fetch-User", "?1")
        if (profile != null && profile.accept.isNotBlank()) builder.header("Accept", profile.accept)
        val finalReferer = profile?.referer?.ifBlank { referer } ?: referer
        if (finalReferer.isNotBlank()) builder.header("Referer", finalReferer)
        if (profile != null && profile.cookie.isNotBlank() && !headers.containsKey("Cookie")) {
            builder.header("Cookie", profile.cookie)
        }
        profile?.extraHeaders?.forEach { (k, v) -> builder.header(k, v) }
        headers.forEach { (k, v) -> builder.header(k, v) }

        val resp = exec(builder.build(), charset, fast = fast, slow = isSlow, tag = tag, timeoutMs = timeoutMs)
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
        tag: Any? = null,
        timeoutMs: Long = 0L,
        profile: SourceRequestProfile? = null,
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

        val reqUa = profile?.userAgent?.ifBlank { headers["User-Agent"] } ?: headers["User-Agent"] ?: UA
        val isMobile = when (profile?.deviceType?.uppercase()) {
            "MOBILE" -> true
            "DESKTOP" -> false
            else -> reqUa.contains("Mobile") || reqUa.contains("Android")
        }
        builder.header("User-Agent", reqUa)
        builder.header("sec-ch-ua-mobile", if (isMobile) "?1" else "?0")
        builder.header("sec-ch-ua-platform", if (isMobile) "\"Android\"" else "\"Windows\"")
        builder.header("Content-Type", "application/x-www-form-urlencoded")
        builder.header("Sec-Fetch-Dest", "document")
        builder.header("Sec-Fetch-Mode", "navigate")
        builder.header("Sec-Fetch-Site", if (referer.isBlank()) "none" else "same-origin")
        val finalReferer = profile?.referer?.ifBlank { referer } ?: referer
        builder.header("Origin", finalReferer.trimEnd('/'))
        builder.post(fb.build())
        if (finalReferer.isNotBlank()) builder.header("Referer", finalReferer)
        if (profile != null && profile.accept.isNotBlank()) builder.header("Accept", profile.accept)
        if (profile != null && profile.cookie.isNotBlank() && !headers.containsKey("Cookie")) {
            builder.header("Cookie", profile.cookie)
        }
        profile?.extraHeaders?.forEach { (k, v) -> builder.header(k, v) }
        headers.forEach { (k, v) -> builder.header(k, v) }

        return exec(builder.build(), charset, fast = fast, slow = isSlow, tag = tag, timeoutMs = timeoutMs)
    }

    fun getBytes(url: String, referer: String = ""): ByteArray? = runCatching {
        val b = Request.Builder().url(safeUrl(url)).header("User-Agent", UA)
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (url.contains("wenku8")) {
            val host = runCatching { safeUrl(url).toHttpUrlOrNull()?.host }.getOrNull() ?: "wenku8.cc"
            val ck = getCookiesHeader(host)
            if (ck.isNotBlank()) {
                b.header("Cookie", ck)
            }
        }
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
     * 请求外壳：支持超时预算限制与底层 OkHttp Call.cancel 级取消。
     */
    private fun exec(
        request: Request,
        charset: String,
        depth: Int = 0,
        fast: Boolean = false,
        slow: Boolean = false,
        tag: Any? = null,
        timeoutMs: Long = 0L,
    ): Resp {
        return once(request, charset, depth, fast = fast, slow = slow, tag = tag, timeoutMs = timeoutMs)
    }

    private fun once(
        request: Request,
        charset: String,
        depth: Int = 0,
        fast: Boolean = false,
        slow: Boolean = false,
        tag: Any? = null,
        timeoutMs: Long = 0L,
    ): Resp {
        val baseClient = when {
            slow -> slowClient
            fast -> fastClient
            else -> client
        }
        val targetClient = if (timeoutMs > 0L) {
            val timeoutSec = maxOf(1L, timeoutMs / 1000L)
            baseClient.newBuilder()
                .connectTimeout(minOf(5L, timeoutSec), TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .callTimeout(timeoutSec + 2, TimeUnit.SECONDS)
                .build()
        } else baseClient

        val call = targetClient.newCall(request)
        if (tag != null) registerCall(tag, call)

        return try {
            val maxWait = if (timeoutMs > 0L) timeoutMs else 10_000L
            val acquired = enforceHostInterval(request.url.host, maxWaitMs = maxWait)
            if (!acquired && timeoutMs > 0L) {
                return Resp(false, 0, "", "域名限频等待超时", request.url.toString())
            }

            call.execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val text = decode(bytes, charset, r.header("Content-Type"))
                val finalUrl = r.request.url.toString()
                if (!r.isSuccessful) {
                    val isCf = isCloudflareChallenge(r.code, text)
                    val err = if (isCf) "访问受限（触发 Cloudflare 浏览器安全质询，需网页验证）" else friendlyCode(r.code)
                    return Resp(false, r.code, text, err, finalUrl)
                }

                // 识别短脚本/meta 自动跳转
                if (depth < 2 && text.length < 1200) {
                    val jump = findMetaRedirect(text)
                    if (jump != null && jump != finalUrl) {
                        val abs = Rules.absUrl(finalUrl, jump)
                        if (abs.startsWith("http")) {
                            val again = Request.Builder().url(abs)
                                .header("User-Agent", request.header("User-Agent") ?: UA)
                                .header("Referer", finalUrl)
                                .header("Accept-Language", "zh-CN,zh;q=0.9")
                                .build()
                            return exec(again, charset, depth + 1, fast = fast, slow = slow, tag = tag, timeoutMs = timeoutMs)
                        }
                    }
                }
                Resp(true, r.code, text, via = finalUrl)
            }
        } catch (e: Exception) {
            if (call.isCanceled() || (e is java.io.InterruptedIOException && e.message?.contains("cancel", true) == true)) {
                return Resp(false, -1, "", "已取消", request.url.toString())
            }
            Resp(false, 0, "", friendlyError(e), request.url.toString())
        } finally {
            if (tag != null) unregisterCall(tag, call)
        }
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
        if (msg.contains("cancel") || name.contains("CanceledException")) return "已取消"
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
