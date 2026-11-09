package com.storm.shijuan.data

import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.*

/** 书源运行状态分类 */
enum class SourceStatus(val label: String) {
    NORMAL("正常"),
    ACCESS_RESTRICTED("访问受限"),    // 403, Cloudflare 质询, WAF
    NEEDS_LOGIN("需要登录"),          // VIP, 401, 会话失效
    SITE_ERROR("站点异常"),           // 404, 500, 502, 504, SSL 证书失效
    RULE_INVALID("规则失效"),         // 页面拉取成功但规则提取 0 本
    UNVERIFIED("未验证"),
}

/** 单次搜索某书源的结果 */
data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val ok: Boolean,
    val books: List<Book> = emptyList(),
    val error: String = "",
    val ms: Long = 0,
    val status: SourceStatus = if (ok) SourceStatus.NORMAL else SourceStatus.UNVERIFIED,
    val httpCode: Int = 0,
) {
    val count: Int get() = books.size
}

data class SourceHealthMetrics(
    val sourceId: String,
    val totalRequests: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastSuccessMs: Long = 0L,
    val lastFailMs: Long = 0L,
    val lastFailStatus: SourceStatus = SourceStatus.UNVERIFIED,
    val lastFailHttpCode: Int = 0,
    val lastFailMsg: String = "",
    val searchTotal: Int = 0,
    val searchSuccess: Int = 0,
    val tocTotal: Int = 0,
    val tocSuccess: Int = 0,
    val contentTotal: Int = 0,
    val contentSuccess: Int = 0,
    val lastResult: SourceResult? = null,
) {
    val overallSuccessRate: Float get() = if (totalRequests > 0) successCount.toFloat() / totalRequests else 0f
    val searchSuccessRate: Float get() = if (searchTotal > 0) searchSuccess.toFloat() / searchTotal else 0f
    val tocSuccessRate: Float get() = if (tocTotal > 0) tocSuccess.toFloat() / tocTotal else 0f
    val contentSuccessRate: Float get() = if (contentTotal > 0) contentSuccess.toFloat() / contentTotal else 0f
}

data class DeepHealthResult(
    val ok: Boolean,
    val detail: String,
    val status: SourceStatus = if (ok) SourceStatus.NORMAL else SourceStatus.UNVERIFIED,
    val httpCode: Int = 0,
)

object BookRepo {

    private fun threadFactory(name: String): ThreadFactory {
        val count = java.util.concurrent.atomic.AtomicInteger(1)
        return ThreadFactory { r ->
            Thread(r, "$name-${count.getAndIncrement()}").apply { isDaemon = true }
        }
    }

    // 搜索有界独立线程池：8~16 线程，有界队列 128
    val searchPool = ThreadPoolExecutor(
        8, 16, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(128),
        threadFactory("novel-search"),
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    val pool: ThreadPoolExecutor get() = searchPool

    // 正文独立线程池：4~8 线程，有界队列 64
    val contentPool = ThreadPoolExecutor(
        4, 8, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(64),
        threadFactory("novel-content"),
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    // 预取独立线程池：2~3 线程，有界队列 32，超载自动丢弃最旧任务
    private val prefetchPool = ThreadPoolExecutor(
        2, 3, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(32),
        threadFactory("novel-prefetch"),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private var storeRef: Store? = null

    /** 初始化：关联 Store 并恢复 ActiveBases 与历史健康指标 */
    fun init(store: Store) {
        storeRef = store
        val bases = store.loadActiveBases()
        bases.forEach { (sid, p) ->
            ActiveBases[sid] = p.first
            activeBaseTimes[sid] = p.second
        }
        loadPersistedMetrics(store)
    }

    private fun enc(s: String, charset: String = "UTF-8"): String =
        runCatching { URLEncoder.encode(s, charset) }.getOrDefault(URLEncoder.encode(s, "UTF-8"))

    private fun fill(tpl: String, key: String, charset: String = "utf-8"): String =
        tpl.replace("{key}", enc(key, if (charset.contains("gb")) "GBK" else "UTF-8"))

    val sourceMetrics = java.util.concurrent.ConcurrentHashMap<String, SourceHealthMetrics>()

    private fun persistMetrics() {
        val store = storeRef ?: return
        runCatching {
            val json = JSONObject()
            sourceMetrics.forEach { (id, m) ->
                json.put(id, JSONObject().apply {
                    put("sourceId", m.sourceId)
                    put("totalRequests", m.totalRequests)
                    put("successCount", m.successCount)
                    put("failureCount", m.failureCount)
                    put("consecutiveFailures", m.consecutiveFailures)
                    put("lastSuccessMs", m.lastSuccessMs)
                    put("lastFailMs", m.lastFailMs)
                    put("lastFailStatus", m.lastFailStatus.name)
                    put("lastFailHttpCode", m.lastFailHttpCode)
                    put("lastFailMsg", m.lastFailMsg)
                    put("searchTotal", m.searchTotal)
                    put("searchSuccess", m.searchSuccess)
                    put("tocTotal", m.tocTotal)
                    put("tocSuccess", m.tocSuccess)
                    put("contentTotal", m.contentTotal)
                    put("contentSuccess", m.contentSuccess)
                })
            }
            store.saveSourceMetrics(json.toString())
        }
    }

    private fun loadPersistedMetrics(store: Store) {
        val raw = store.loadSourceMetrics() ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { id ->
                val o = json.optJSONObject(id) ?: return@forEach
                val m = SourceHealthMetrics(
                    sourceId = id,
                    totalRequests = o.optInt("totalRequests", 0),
                    successCount = o.optInt("successCount", 0),
                    failureCount = o.optInt("failureCount", 0),
                    consecutiveFailures = o.optInt("consecutiveFailures", 0),
                    lastSuccessMs = o.optLong("lastSuccessMs", 0L),
                    lastFailMs = o.optLong("lastFailMs", 0L),
                    lastFailStatus = runCatching { SourceStatus.valueOf(o.optString("lastFailStatus")) }.getOrDefault(SourceStatus.UNVERIFIED),
                    lastFailHttpCode = o.optInt("lastFailHttpCode", 0),
                    lastFailMsg = o.optString("lastFailMsg", ""),
                    searchTotal = o.optInt("searchTotal", 0),
                    searchSuccess = o.optInt("searchSuccess", 0),
                    tocTotal = o.optInt("tocTotal", 0),
                    tocSuccess = o.optInt("tocSuccess", 0),
                    contentTotal = o.optInt("contentTotal", 0),
                    contentSuccess = o.optInt("contentSuccess", 0),
                )
                sourceMetrics[id] = m
            }
        }
    }

    fun classifyStatus(ok: Boolean, httpCode: Int, error: String, booksCount: Int): SourceStatus {
        if (ok && booksCount > 0) return SourceStatus.NORMAL
        if (httpCode == 403 || httpCode == 429 || error.contains("Cloudflare") || error.contains("403") ||
            error.contains("429") || error.contains("安全质询") || error.contains("拦截") ||
            error.contains("限频") || error.contains("间隔") || error.contains("限制")
        ) {
            return SourceStatus.ACCESS_RESTRICTED
        }
        if (httpCode == 401 || error.contains("登录") || error.contains("VIP") || error.contains("购买")) {
            return SourceStatus.NEEDS_LOGIN
        }
        if (httpCode in 500..599 || httpCode == 404 || error.contains("502") || error.contains("504") ||
            error.contains("连接被拒绝") || error.contains("证书") || error.contains("TLS") ||
            error.contains("DNS") || error.contains("域名解析") || error.contains("无法访问")
        ) {
            return SourceStatus.SITE_ERROR
        }
        if (error.contains("改版") || error.contains("规则") || error.contains("列表") ||
            error.contains("取不到") || error.contains("未解析") || error.contains("没有搜索结果")
        ) {
            return SourceStatus.RULE_INVALID
        }
        if (ok) return SourceStatus.NORMAL
        return SourceStatus.UNVERIFIED
    }

    /** 指数退避阶梯：1m -> 5m -> 30m -> 2h -> 24h 封顶 */
    fun calculateBackoffMs(consecutiveFailures: Int): Long = when (consecutiveFailures) {
        0 -> 0L
        1 -> 60_000L               // 1 分钟
        2 -> 300_000L              // 5 分钟
        3 -> 1_800_000L            // 30 分钟
        4 -> 7_200_000L            // 2 小时
        else -> 86_400_000L        // 24 小时封顶
    }

    fun recordSuccess(sourceId: String, res: SourceResult) {
        sourceMetrics.compute(sourceId) { _, old ->
            val prev = old ?: SourceHealthMetrics(sourceId)
            prev.copy(
                totalRequests = prev.totalRequests + 1,
                successCount = prev.successCount + 1,
                searchTotal = prev.searchTotal + 1,
                searchSuccess = prev.searchSuccess + 1,
                lastSuccessMs = System.currentTimeMillis(),
                consecutiveFailures = 0,
                lastResult = res,
            )
        }
        failedUntil.remove(sourceId)
        persistMetrics()
    }

    fun recordFailure(sourceId: String, status: SourceStatus, httpCode: Int, msg: String) {
        val m = sourceMetrics.compute(sourceId) { _, old ->
            val prev = old ?: SourceHealthMetrics(sourceId)
            val newFailures = prev.consecutiveFailures + 1
            prev.copy(
                totalRequests = prev.totalRequests + 1,
                failureCount = prev.failureCount + 1,
                searchTotal = prev.searchTotal + 1,
                lastFailMs = System.currentTimeMillis(),
                lastFailStatus = status,
                lastFailHttpCode = httpCode,
                lastFailMsg = msg,
                consecutiveFailures = newFailures,
            )
        }
        // 统一熔断与指数退避：502、504、拒绝连接或连续报错触发退避降权
        if (m != null && (httpCode in 500..599 || httpCode == 404 || msg.contains("502") || msg.contains("504") || msg.contains("拒绝") || m.consecutiveFailures >= 2)) {
            val backoff = calculateBackoffMs(m.consecutiveFailures)
            markFailed(sourceId, backoff)
        }
        persistMetrics()
    }

    fun recordTocResult(sourceId: String, ok: Boolean, httpCode: Int = 200, error: String = "") {
        sourceMetrics.compute(sourceId) { _, old ->
            val prev = old ?: SourceHealthMetrics(sourceId)
            if (ok) {
                prev.copy(
                    totalRequests = prev.totalRequests + 1,
                    successCount = prev.successCount + 1,
                    tocTotal = prev.tocTotal + 1,
                    tocSuccess = prev.tocSuccess + 1,
                    lastSuccessMs = System.currentTimeMillis(),
                    consecutiveFailures = 0,
                )
            } else {
                val newFailures = prev.consecutiveFailures + 1
                prev.copy(
                    totalRequests = prev.totalRequests + 1,
                    failureCount = prev.failureCount + 1,
                    tocTotal = prev.tocTotal + 1,
                    lastFailMs = System.currentTimeMillis(),
                    lastFailStatus = classifyStatus(false, httpCode, error, 0),
                    lastFailHttpCode = httpCode,
                    lastFailMsg = error,
                    consecutiveFailures = newFailures,
                )
            }
        }
        persistMetrics()
    }

    fun recordContentResult(sourceId: String, ok: Boolean, httpCode: Int = 200, error: String = "") {
        sourceMetrics.compute(sourceId) { _, old ->
            val prev = old ?: SourceHealthMetrics(sourceId)
            if (ok) {
                prev.copy(
                    totalRequests = prev.totalRequests + 1,
                    successCount = prev.successCount + 1,
                    contentTotal = prev.contentTotal + 1,
                    contentSuccess = prev.contentSuccess + 1,
                    lastSuccessMs = System.currentTimeMillis(),
                    consecutiveFailures = 0,
                )
            } else {
                val newFailures = prev.consecutiveFailures + 1
                prev.copy(
                    totalRequests = prev.totalRequests + 1,
                    failureCount = prev.failureCount + 1,
                    contentTotal = prev.contentTotal + 1,
                    lastFailMs = System.currentTimeMillis(),
                    lastFailStatus = classifyStatus(false, httpCode, error, 0),
                    lastFailHttpCode = httpCode,
                    lastFailMsg = error,
                    consecutiveFailures = newFailures,
                )
            }
        }
        persistMetrics()
    }

    // ── ActiveBases 镜像持久化与 24h TTL ──
    val ActiveBases = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val activeBaseTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordActiveBase(sourceId: String, base: String) {
        val now = System.currentTimeMillis()
        ActiveBases[sourceId] = base
        activeBaseTimes[sourceId] = now
        storeRef?.saveActiveBase(sourceId, base, now)
    }

    fun getActiveBase(src: Source): String? {
        val base = ActiveBases[src.id] ?: return null
        val time = activeBaseTimes[src.id] ?: 0L
        if (System.currentTimeMillis() - time > 24L * 3600 * 1000L) {
            ActiveBases.remove(src.id)
            activeBaseTimes.remove(src.id)
            return null
        }
        return if (src.bases.contains(base)) base else null
    }

    // ── 任务取消管理 ──
    private val activeSearchFutures = ConcurrentHashMap<Any, MutableList<java.util.concurrent.Future<*>>>()

    fun cancelSearch(tag: Any) {
        val futures = activeSearchFutures.remove(tag)
        futures?.forEach { runCatching { it.cancel(true) } }
        Net.cancelCalls(tag)
    }

    private val failedUntil = ConcurrentHashMap<String, Long>()

    fun isBlacklisted(sourceId: String): Boolean {
        val until = failedUntil[sourceId] ?: return false
        if (System.currentTimeMillis() > until) {
            failedUntil.remove(sourceId)
            return false
        }
        return true
    }

    fun markFailed(sourceId: String, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        failedUntil[sourceId] = until
    }

    /** 搜索单个书源 —— 依次尝试镜像与备用路径，共享单源 Deadline 预算 */
    fun searchSource(
        src: Source,
        key: String,
        deadlineMs: Long = 0L,
        tag: Any? = null,
    ): SourceResult {
        val t0 = System.currentTimeMillis()
        val cached = SearchCache.get(src.id, key)
        if (cached != null) {
            return cached
        }

        val budgetMs = if (src.apiKind == "sfacg" || src.id == "sfacg") 20_000L else 12_000L
        val effectiveDeadline = if (deadlineMs > 0L) deadlineMs else (t0 + budgetMs)

        if (src.apiKind == "zlib") {
            val r = Zlib.search(key)
            val ok = r.first.isNotEmpty()
            val st = if (ok) SourceStatus.NORMAL else classifyStatus(false, 0, r.second, 0)
            val res = SourceResult(
                src.id, src.name, ok,
                r.first, r.second, System.currentTimeMillis() - t0,
                st, if (ok) 200 else 0,
            )
            if (ok) recordSuccess(src.id, res) else recordFailure(src.id, st, 0, r.second)
            SearchCache.put(src.id, key, res)
            return res
        }
        if (src.apiKind == "sfacg") {
            val res = searchSfacg(src, key, effectiveDeadline, tag)
            SearchCache.put(src.id, key, res)
            return res
        }
        if (src.id == "wenku8") {
            val localHits = Wenku8LocalIndex.search(key)
            if (localHits.isNotEmpty()) {
                val res = SourceResult(src.id, src.name, true, localHits, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                recordSuccess(src.id, res)
                SearchCache.put(src.id, key, res)
                return res
            }
        }
        val rule = src.search ?: return SourceResult(src.id, src.name, false, error = "该源未配置搜索规则", status = SourceStatus.RULE_INVALID)

        val keyCharset = if (rule.keyCharset.equals("utf-8", true) && src.charset.contains("gb")) "gbk"
        else rule.keyCharset

        var lastErr = "连接不上"
        var lastHttpCode = 0
        val known = getActiveBase(src)
        val ordered = if (known != null && src.bases.contains(known))
            listOf(known) + src.bases.filter { it != known } else src.bases

        for (base in ordered) {
            val now = System.currentTimeMillis()
            if (now >= effectiveDeadline - 800L) {
                lastErr = "搜索超时（已达到单源时间预算）"
                break
            }
            val remainingBudget = effectiveDeadline - now

            val urls = buildList {
                add(fill(rule.url, key, keyCharset))
                rule.extraUrls.forEach { add(fill(it, key, keyCharset)) }
            }
            for (path in urls) {
                val loopNow = System.currentTimeMillis()
                if (loopNow >= effectiveDeadline - 500L) {
                    lastErr = "搜索超时（已达到单源时间预算）"
                    break
                }
                val iterBudget = effectiveDeadline - loopNow
                val full = if (path.startsWith("http")) path else base + path
                val isPost = if (path.contains("so.php")) true else rule.method.equals("POST", true)
                val resp = if (isPost) {
                    val body = HashMap<String, String>()
                    val rawBody = if (rule.body.isNotBlank()) rule.body else "searchtype=articlename&searchkey={key}&charset=gbk"
                    fill(rawBody, key, keyCharset).split("&").forEach {
                        val kv = it.split("=", limit = 2)
                        if (kv.size == 2) body[kv[0]] = kv[1]
                    }
                    if (body.isEmpty()) body["searchkey"] = enc(key, keyCharset)
                    Net.post(full, body, src.charset, base + "/", encoded = true, fast = true, tag = tag, timeoutMs = iterBudget, profile = src.requestProfile)
                } else {
                    Net.get(full, src.charset, base + "/", fast = true, tag = tag, timeoutMs = iterBudget, profile = src.requestProfile)
                }

                if (!resp.ok) {
                    lastErr = resp.error
                    lastHttpCode = resp.code
                    if (src.id == "wenku8") {
                        val localHits = Wenku8LocalIndex.search(key)
                        if (localHits.isNotEmpty()) {
                            recordActiveBase(src.id, base)
                            val res = SourceResult(src.id, src.name, true, localHits, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                            recordSuccess(src.id, res)
                            SearchCache.put(src.id, key, res)
                            return res
                        }
                    }
                    if (resp.code == 403 || resp.error.contains("Cloudflare") || resp.error.contains("安全质询")) {
                        val st = SourceStatus.ACCESS_RESTRICTED
                        val res = SourceResult(src.id, src.name, false, emptyList(), resp.error, System.currentTimeMillis() - t0, st, resp.code)
                        recordFailure(src.id, st, resp.code, resp.error)
                        return res
                    }
                    continue
                }

                val lowerBody = resp.body.lowercase()
                val lowerVia = resp.via.lowercase()

                if (lowerBody.contains("<h1>welcome</h1>") || lowerBody.contains("just a moment") || lowerBody.contains("cf-browser-verification")) {
                    val st = SourceStatus.ACCESS_RESTRICTED
                    val err = "触发源站浏览器安全验证（Cloudflare/WAF），需在书源管理中网页验证"
                    val res = SourceResult(src.id, src.name, false, emptyList(), err, System.currentTimeMillis() - t0, st, 403)
                    recordFailure(src.id, st, 403, err)
                    return res
                }

                // 登录跳转或未登录提示拦截（文库 search.php 未登录会 302 到 login.php?jumpurl=...）
                val isLoginRequired = lowerVia.contains("login.php") || (src.id == "wenku8" && (lowerBody.contains("login.php") || lowerBody.contains("您尚未登录") || lowerBody.contains("请先登录")))
                if (isLoginRequired) {
                    if (src.id == "wenku8") {
                        val localHits = Wenku8LocalIndex.search(key)
                        if (localHits.isNotEmpty()) {
                            recordActiveBase(src.id, base)
                            val res = SourceResult(src.id, src.name, true, localHits, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                            recordSuccess(src.id, res)
                            SearchCache.put(src.id, key, res)
                            return res
                        }
                    }
                    val st = SourceStatus.NEEDS_LOGIN
                    val err = if (src.id == "wenku8") "轻小说文库搜索需在网页中登录后使用" else "${src.name}搜索需在网页中登录后使用"
                    val res = SourceResult(src.id, src.name, false, emptyList(), err, System.currentTimeMillis() - t0, st, 401)
                    recordFailure(src.id, st, 401, err)
                    return res
                }

                // 文库等源站搜索频率限制拦截（文库硬限制：两次搜索间隔不得少于 5 秒）
                if (src.id == "wenku8" && (resp.body.contains("两次搜索的间隔时间") || resp.body.contains("搜索间隔时间不得少于") || resp.body.contains("搜索间隔时间"))) {
                    val localHits = Wenku8LocalIndex.search(key)
                    if (localHits.isNotEmpty()) {
                        recordActiveBase(src.id, base)
                        val res = SourceResult(src.id, src.name, true, localHits, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                        recordSuccess(src.id, res)
                        SearchCache.put(src.id, key, res)
                        return res
                    }
                    val st = SourceStatus.ACCESS_RESTRICTED
                    val err = "源站限制两次搜索间隔不得少于 5 秒，请稍后重试"
                    val res = SourceResult(src.id, src.name, false, emptyList(), err, System.currentTimeMillis() - t0, st, 429)
                    recordFailure(src.id, st, 429, err)
                    return res
                }

                if (resp.via.isNotBlank() && resp.via != full) {
                    singleBookFromPage(src, resp, full, key)?.let { one ->
                        recordActiveBase(src.id, base)
                        val res = SourceResult(src.id, src.name, true, listOf(one), "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                        recordSuccess(src.id, res)
                        SearchCache.put(src.id, key, res)
                        return res
                    }
                }
                val books = parseSearchPage(src, resp.body, resp.via.ifBlank { full }, rule, key)
                if (books.isNotEmpty()) {
                    recordActiveBase(src.id, base)
                    val res = SourceResult(src.id, src.name, true, books, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                    recordSuccess(src.id, res)
                    SearchCache.put(src.id, key, res)
                    return res
                }
                val rows = Rules.countList(Rules.parse(resp.body).body(), rule.list)
                val noEcho = key.isNotBlank() && !resp.body.contains(key, ignoreCase = true)
                lastErr = when {
                    noEcho -> "站点搜索不可用（返回的是导航/首页，不是结果页）"
                    rows > 0 -> "站点改版，列表 ${rows} 项但取不到书名"
                    looksLikeNoResult(resp.body) -> "这个站没有这本书"
                    else -> "没搜到这本书"
                }
            }
        }
        if (src.id == "wenku8") {
            val localHits = Wenku8LocalIndex.search(key)
            if (localHits.isNotEmpty()) {
                val res = SourceResult(src.id, src.name, true, localHits, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                recordSuccess(src.id, res)
                SearchCache.put(src.id, key, res)
                return res
            }
        }
        val st = classifyStatus(false, lastHttpCode, lastErr, 0)
        val res = SourceResult(src.id, src.name, false, emptyList(), lastErr, System.currentTimeMillis() - t0, st, lastHttpCode)
        recordFailure(src.id, st, lastHttpCode, lastErr)
        return res
    }

    /**
     * SF 轻小说的搜索是前端 ajax 发出去的，HTML 里根本没有结果；
     * 直接打它的搜索接口拿 JSON。书页统一用手机站 /b/{id}/，目录和正文也都走手机站。
     */
    private fun searchSfacg(
        src: Source,
        key: String,
        deadlineMs: Long = 0L,
        tag: Any? = null,
    ): SourceResult {
        val t0 = System.currentTimeMillis()
        val budgetMs = 20_000L
        val effectiveDeadline = if (deadlineMs > 0L) deadlineMs else (t0 + budgetMs)
        var lastErr = "连接不上"
        var lastCode = 0
        val known = getActiveBase(src)
        val ordered = if (known != null && src.bases.contains(known))
            listOf(known) + src.bases.filter { it != known } else src.bases
        for (base in ordered) {
            val now = System.currentTimeMillis()
            if (now >= effectiveDeadline - 800L) {
                lastErr = "搜索超时（已达到单源时间预算）"
                break
            }
            val iterBudget = effectiveDeadline - now
            val url = base + "/API/HTML5.ashx?op=search&keyword=" + enc(key)
            val resp = Net.get(url, "utf-8", base + "/search.html", fast = true, tag = tag, timeoutMs = iterBudget, profile = src.requestProfile)
            if (!resp.ok) {
                lastErr = resp.error
                lastCode = resp.code
                if (resp.code == 403 || resp.error.contains("Cloudflare") || resp.error.contains("安全质询")) {
                    val st = SourceStatus.ACCESS_RESTRICTED
                    val res = SourceResult(src.id, src.name, false, emptyList(), resp.error, System.currentTimeMillis() - t0, st, resp.code)
                    recordFailure(src.id, st, resp.code, resp.error)
                    return res
                }
                continue
            }
            val books = runCatching {
                val json = JSONObject(resp.body.trimStart('\uFEFF'))
                val arr = json.optJSONArray("Novels") ?: return@runCatching emptyList<Book>()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("NovelID").trim()
                    val name = o.optString("NovelName").trim()
                    if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
                    Book(
                        name = name,
                        author = o.optString("AuthorName").trim(),
                        cover = sfCover(o.optString("NovelCover").trim()),
                        url = base + "/b/" + id + "/",
                        sourceId = src.id,
                        sourceName = src.name,
                        latest = o.optString("LastUpdateTime").trim(),
                        intro = o.optString("Expand").trim(),
                    )
                }
            }.getOrDefault(emptyList())
            if (books.isNotEmpty()) {
                recordActiveBase(src.id, base)
                val res = SourceResult(src.id, src.name, true, books, "", System.currentTimeMillis() - t0, SourceStatus.NORMAL, 200)
                recordSuccess(src.id, res)
                return res
            }
            lastErr = "没搜到这本书"
        }
        val st = classifyStatus(false, lastCode, lastErr, 0)
        val res = SourceResult(src.id, src.name, false, emptyList(), lastErr, System.currentTimeMillis() - t0, st, lastCode)
        recordFailure(src.id, st, lastCode, lastErr)
        return res
    }

    private fun sfCover(path: String): String = when {
        path.isEmpty() || path == "null" -> ""
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:" + path
        else -> "https://rs.sfacg.com/web/novel/images/NovelCover/Big/" + path
    }

    /** 把目录页模板里的 {id}/{idk}/{book}/{page} 换成真值 */
    private fun fillTocPage(tpl: String, bookUrl: String): String = fillTocPage(tpl, bookUrl, 1)

    private fun fillTocPage(tpl: String, bookUrl: String, page: Int): String {
        val id = Regex("/(?:b|Novel|book|info)/(\\d+)").find(bookUrl)?.groupValues?.get(1)
            ?: Regex("(\\d{3,})").find(bookUrl)?.groupValues?.get(1).orEmpty()
        val idk = (id.toLongOrNull()?.let { it / 1000 } ?: 0).toString()
        return tpl.replace("{book}", bookUrl)
            .replace("{id}", id)
            .replace("{idk}", idk)
            .replace("{page}", page.toString())
    }

    /** 页面里明确写了「没有结果」这类话，就说明是站点上没这本书，不是我们解析坏了 */
    private fun looksLikeNoResult(html: String): Boolean {
        if (html.length > 200_000) return false
        val heads = html.substring(0, minOf(html.length, 20_000))
        return listOf(
            "没有找到", "未找到", "无搜索结果", "没有搜索结果", "暂无结果", "抱歉",
            "没有相关", "找不到相关", "no result", "0 条结果", "0条结果",
        ).any { heads.contains(it, ignoreCase = true) }
    }

    /** 过滤误将站点登录/注册/找回密码链接当成书籍的假条目 */
    private fun isFakeBook(name: String, url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("login") || lowerUrl.contains("register") || lowerUrl.contains("getpass")) return true
        val blockedNames = listOf("新用户注册", "用户登录", "注册新用户", "取回密码", "忘记密码", "退出登录")
        return blockedNames.any { name.equals(it, ignoreCase = true) || name.contains(it) }
    }

    internal fun normalizeCover(src: Source, cover: String, bookUrl: String): String {
        var c = Rules.upgradeWenku8Image(cover.trim())
        if (src.id == "wenku8" || c.contains("wenku8") || c.contains("777743")) {
            if (c.isBlank() && bookUrl.isNotBlank()) {
                val aid = Regex("""/book/(\d+)\.htm""").find(bookUrl)?.groupValues?.get(1)
                if (aid != null) {
                    val gid = (aid.toLongOrNull() ?: 0L) / 1000L
                    c = "https://img.wenku8.com/image/$gid/$aid/${aid}s.jpg"
                }
            }
        }
        if (c.isNotBlank() && !c.startsWith("http://") && !c.startsWith("https://") && !c.startsWith("file://") && !c.startsWith("content://")) {
            val root = bookUrl.ifBlank { src.base }
            if (root.isNotBlank()) {
                c = Rules.absUrl(root, c)
            }
        }
        return c
    }

    private fun parseSearchPage(src: Source, html: String, pageUrl: String, rule: SearchRule, key: String = ""): List<Book> {
        val doc = Rules.parse(html)
        var rows: List<Map<String, String>> = emptyList()
        if (rule.list.isNotBlank() && rule.fields.isNotEmpty()) {
            rows = Rules.extractList(doc.body(), rule.list, rule.fields, pageUrl)
        }
        var books = rows.mapNotNull { row ->
            val name = row["name"].orEmpty().trim()
            val url = row["bookUrl"].orEmpty().trim()
            if (name.isEmpty() || url.isEmpty() || isFakeBook(name, url)) null
            else Book(
                name = name,
                author = row["author"].orEmpty().trim(),
                cover = normalizeCover(src, row["cover"].orEmpty().trim(), url),
                intro = row["intro"].orEmpty().trim(),
                latest = row["latest"].orEmpty().trim(),
                url = url,
                sourceId = src.id,
                sourceName = src.name,
                kind = row["kind"].orEmpty(),
                wordCount = row["wordCount"].orEmpty(),
            )
        }
        // Wenku8 专用正则兜底（参考 wenku8_client.py）
        if (books.isEmpty() && src.id == "wenku8") {
            val base = if (pageUrl.startsWith("http")) pageUrl.substringBefore("/modules").substringBefore("/book") else src.base
            val pat = Regex("""/book/(\d+)\.htm"[^>]*>([^<]{1,60})</a>""")
            val hits = pat.findAll(html)
            val seen = mutableSetOf<String>()
            val blockedTexts = listOf("我要阅读", "立即阅读", "点击阅读", "加入书架", "放入书架", "推荐", "投推荐票", "小说目录", "最新章节")
            val fallbackBooks = mutableListOf<Book>()
            for (m in hits) {
                val aid = m.groupValues[1]
                val title = m.groupValues[2].trim()
                if (aid.isEmpty() || title.isEmpty() || blockedTexts.any { title.contains(it) } || isFakeBook(title, "/book/$aid.htm") || !seen.add(aid)) continue
                val aidLong = aid.toLongOrNull() ?: 0L
                val gid = aidLong / 1000L
                val coverUrl = "https://img.wenku8.com/image/$gid/$aid/${aid}s.jpg"
                val bookUrl = if (base.isNotBlank()) "${base.trimEnd('/')}/book/$aid.htm" else "https://www.wenku8.cc/book/$aid.htm"
                fallbackBooks.add(
                    Book(
                        name = title,
                        author = "",
                        cover = coverUrl,
                        intro = "",
                        latest = "",
                        url = bookUrl,
                        sourceId = src.id,
                        sourceName = src.name,
                    )
                )
            }
            if (fallbackBooks.isNotEmpty()) {
                books = fallbackBooks
            }
        }
        // auto 兜底最容易翻车：站点没有搜索结果时，页面上的导航/推荐位也会被当成「书籍列表」，
        // 于是列表里全是书名不对、点进去是伪目录的条目。要求页面里真出现了搜索词才采纳。
        val echoKey = key.isBlank() || html.contains(key, ignoreCase = true)
        if (books.isEmpty() && rule.auto && echoKey) {
            books = Rules.autoList(doc, pageUrl)
                .filterNot { isFakeBook(it.name, it.url) }
                .map {
                    it.copy(sourceId = src.id, sourceName = src.name)
                }
        }
        return books.take(60)
    }

    /** 一次 meta 取值：property 和 name 两种写法都认 */
    private fun metaText(doc: org.jsoup.nodes.Document, key: String): String {
        val el = doc.selectFirst("meta[property=\"$key\"]") ?: doc.selectFirst("meta[name=\"$key\"]")
            ?: return ""
        return el.attr("content").replace(Regex("\\s+"), " ").trim()
    }

    /** 一眼像「书页」：og:type=book，或者标题里写着「最新章节 / 全文阅读」（速读谷的书页标题就是这样） */
    private fun looksLikeBookPage(doc: org.jsoup.nodes.Document): Boolean {
        if (metaText(doc, "og:type").equals("book", true)) return true
        val title = runCatching { doc.title() }.getOrDefault("")
        return title.contains("最新章节") || title.contains("全文阅读") || title.contains("无弹窗")
    }

    /**
     * 有的站拿搜索词一查就直接 302 到书页（速读谷就是这样：搜「凡人修仙传」直接落到 /191/）。
     * 这时把落地的书页本身当成「唯一一条结果」，比丢一句「没搜到这本书」有用得多。
     */
    private fun singleBookFromPage(src: Source, resp: Net.Resp, requestUrl: String, key: String): Book? {
        val landed = resp.via.ifBlank { requestUrl }
        if (landed == requestUrl || landed == src.base || landed == src.base + "/") return null
        val doc = runCatching { Rules.parse(resp.body) }.getOrNull() ?: return null
        if (!looksLikeBookPage(doc)) return null
        val pageText = runCatching { doc.body()?.text().orEmpty() }.getOrDefault("")
        if (key.isNotBlank() && !pageText.contains(key)) return null
        // og:title 常见形态：《书名》作者著-站名；标题常见形态：书名-站名-作者小说-最新章节…
        val raw = metaText(doc, "og:novel:book_name")
            .ifBlank { metaText(doc, "og:title") }
            .ifBlank { metaText(doc, "title") }
            .ifBlank { runCatching { doc.selectFirst("h1")?.text().orEmpty() }.getOrDefault("") }
            .ifBlank { runCatching { doc.title() }.getOrDefault("") }
        val name = (listOf(Regex("^《(.+?)》"), Regex("^(.+?)[-_|｜（(]"))
            .firstNotNullOfOrNull { p -> p.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 } }
            ?: raw).replace(Regex("[《》]"), "").trim()
        if (name.isBlank() || isFakeBook(name, landed)) return null
        return Book(
            name = name,
            author = metaText(doc, "og:novel:author").ifBlank { metaText(doc, "author") },
            cover = normalizeCover(src, metaText(doc, "og:image"), landed),
            intro = metaText(doc, "og:description").ifBlank { metaText(doc, "description") }.take(400),
            url = landed,
            sourceId = src.id,
            sourceName = src.name,
        )
    }

    /** 详情页：补作者/封面/简介（失败不致命） */
    fun getDetail(src: Source, book: Book): Book {
        val base = ActiveBases[src.id] ?: src.base
        val resp = Net.get(book.url, src.charset, base + "/", useCache = true)
        if (!resp.ok) return book
        val doc = Rules.parse(resp.body)
        val d = src.detail
        var name = book.name
        var author = book.author
        var cover = normalizeCover(src, book.cover, book.url)
        var intro = book.intro
        if (d != null) {
            val root = doc
            if (d.name.isNotBlank()) Rules.extract(root, d.name, book.url).ifBlank { null }?.let { name = it }
            if (d.author.isNotBlank()) Rules.extract(root, d.author, book.url).ifBlank { null }?.let { author = it }
            if (d.cover.isNotBlank()) Rules.extract(root, d.cover, book.url).ifBlank { null }?.let { cover = normalizeCover(src, it, book.url) }
            if (d.intro.isNotBlank()) Rules.extract(root, d.intro, book.url).ifBlank { null }?.let { intro = it }
        }
        if (author.isBlank()) {
            doc.selectFirst("meta[name=author]")?.attr("content")?.ifBlank { null }?.let { author = it }
        }
        if (intro.isBlank()) {
            doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }?.let { intro = it }
        }
        // 通用兜底：og 标签
        if (cover.isBlank()) {
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { cover = normalizeCover(src, it, book.url) }
        }
        return book.copy(name = name, author = author, cover = cover, intro = intro)
    }

    /**
     * 详情 + 目录一次拿全。
     * 两者并行解析，若首选域名失败则尝试镜像。
     */
    fun getDetailAndToc(src: Source, book: Book): Pair<Book, Result<Toc>> {
        val detRef = java.util.concurrent.atomic.AtomicReference(book)
        val tocRef = java.util.concurrent.atomic.AtomicReference<Result<Toc>>(
            Result.failure(Exception("未执行"))
        )
        val a = Thread { detRef.set(runCatching { getDetail(src, book) }.getOrDefault(book)) }
        val b = Thread { tocRef.set(runCatching { getToc(src, book) }.getOrElse { Result.failure(it) }) }
        a.isDaemon = true; b.isDaemon = true
        a.start(); b.start()
        runCatching { a.join(35000) }
        runCatching { b.join(35000) }
        return detRef.get() to tocRef.get()
    }

    /** 后台预取指定章节正文（翻页时直接命中缓存） */
    fun prefetchChapter(src: Source, chapter: Chapter, store: Store) {
        if (NovelContentCache.get(src.id, chapter.url) != null || store.loadContent(chapter.url, src.id) != null) return
        runCatching {
            prefetchPool.execute {
                runCatching { getChapter(src, chapter) }
            }
        }
    }

    /** 从一张页面上按规则 / 兜底认出章节列表 */
    internal fun parseTocList(doc: org.jsoup.nodes.Document, pageUrl: String, t: TocRule?): List<Chapter> {
        var list: List<Chapter> = emptyList()
        val skipVol = t?.skipVolume?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }

        /** 这个章节属于哪一卷：往上找几层，向前查找卷标题（SF 是 div.mulu，Wenku8 是 table.css 中的 td.vcss） */
        fun volumeOf(el: org.jsoup.nodes.Element): String {
            val sel = t?.volume.orEmpty()
            if (sel.isBlank()) return ""
            var n: org.jsoup.nodes.Element? = el
            var depth = 0
            while (n != null && depth++ < 6) {
                var prev = n.previousElementSibling()
                while (prev != null) {
                    if (runCatching { prev.`is`(sel) }.getOrDefault(false)) {
                        return prev.text().replace(Regex("\\s+"), " ").trim()
                    }
                    val found = runCatching { prev.selectFirst(sel) }.getOrNull()
                    if (found != null) {
                        return found.text().replace(Regex("\\s+"), " ").trim()
                    }
                    prev = prev.previousElementSibling()
                }
                n = n.parent()
            }
            return ""
        }

        if (t != null && t.list.isNotBlank()) {
            for (branch in t.list.split("||")) {
                val sel = branch.trim()
                if (sel.isEmpty()) continue
                val elements = runCatching { doc.select(sel) }.getOrDefault(emptyList())
                if (elements.isEmpty()) continue
                val parsed = elements.mapIndexedNotNull { idx, el ->
                    val vol = volumeOf(el)
                    // 整卷都是插图/人设的话（SF 每本书几乎都有），这一卷的章节一个都不要
                    if (skipVol != null && skipVol.containsMatchIn(vol)) return@mapIndexedNotNull null
                    val target = if (el.tagName().equals("a", true)) el else el.selectFirst("a") ?: el
                    val name = when {
                        t.name.isBlank() || t.name == "@text" || t.name == "text" ->
                            target.text().replace(Regex("\\s+"), " ").trim()
                        else -> Rules.extract(el, t.name, pageUrl).ifBlank {
                            target.text().replace(Regex("\\s+"), " ").trim()
                        }
                    }
                    val url = when {
                        t.url.isBlank() || t.url == "@href" || t.url == "href" -> {
                            val href = target.attr("href").ifBlank { target.attr("data-href") }.trim()
                            Rules.absUrl(pageUrl, href)
                        }
                        else -> Rules.extract(el, t.url, pageUrl).ifBlank {
                            val href = target.attr("href").ifBlank { target.attr("data-href") }.trim()
                            Rules.absUrl(pageUrl, href)
                        }
                    }
                    if (name.isEmpty() || url.isEmpty() || url == pageUrl) null else Chapter(name, url, idx, vol)
                }
                if (parsed.size >= 2) {
                    list = parsed
                    break
                }
            }
        }
        if (list.size < 2) {
            val guess = guessToc(doc, pageUrl)
            if (guess.size >= 2) list = guess
        }
        if (list.size < 2) {
            val auto = Rules.autoList(doc, pageUrl, chapterMode = true)
            if (auto.size >= 2) list = auto.mapIndexed { i, b -> Chapter(b.name, b.url, i) }
        }
        // 去重 + 去导航 + 去读不了的章节（插图页/人设页这类只有图片的章节，规则里用 skip 指定）
        val nav = setOf("上一页", "下一页", "首页", "尾页", "目录", "返回", "加入书签", "上一章", "下一章")
        val skip = t?.skip?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
        val seen = HashSet<String>()
        var idx = 0
        return list.mapNotNull { c ->
            if (c.name in nav) return@mapNotNull null
            if (!seen.add(c.url)) return@mapNotNull null
            if (skip != null && skip.containsMatchIn(c.name)) return@mapNotNull null
            c.copy(index = idx++)
        }
    }

    /** 目录（支持单页抓取与基于 nextPage / page 模板的真实多页分页拉取） */
    fun getToc(src: Source, book: Book): Result<Toc> {
        if (src.id == "local") return Result.success(Toc(chapters = emptyList()))
        // 轻小说文库官方全本直下通道优先：绕过源站 Cloudflare 5秒盾与地区屏蔽
        if (src.id == "wenku8") {
            val aid = Wenku8Direct.extractAid(book.url)
            if (aid != null) {
                val directRes = Wenku8Direct.fetchAndUnpack(aid)
                if (directRes.isSuccess && directRes.getOrNull()?.chapters?.isNotEmpty() == true) {
                    recordTocResult(src.id, ok = true)
                    return directRes
                }
            }
        }
        val base = getActiveBase(src) ?: src.base
        val t = src.toc
        // 有的站书页和目录页是分开的两页（SF 手机站就是），规则里可以单独给目录页模板
        val tocTpl = t?.page.orEmpty()
        val tocUrl = if (tocTpl.isBlank()) book.url
        else Rules.absUrl(base + "/", fillTocPage(tocTpl, book.url, page = 1))
        var resp = Net.get(tocUrl, src.charset, base + "/", useCache = true)
        // 目录页偶发挂了（限流/换版），回书页再试一次，别直接判死
        if (!resp.ok && tocUrl != book.url) {
            val alt = Net.get(book.url, src.charset, base + "/", useCache = true)
            if (alt.ok) resp = alt
        }
        if (!resp.ok && src.mirrors.isNotEmpty()) {
            val curUri = runCatching { java.net.URI(tocUrl) }.getOrNull()
            if (curUri != null) {
                for (mirror in src.mirrors) {
                    val mirrorUri = runCatching { java.net.URI(mirror) }.getOrNull() ?: continue
                    val altUrl = java.net.URI(mirrorUri.scheme, mirrorUri.authority, curUri.path, curUri.query, curUri.fragment).toString()
                    val r2 = Net.get(altUrl, src.charset, "$mirror/", useCache = true)
                    if (r2.ok) {
                        resp = r2
                        recordActiveBase(src.id, mirror)
                        break
                    }
                }
            }
        }
        if (!resp.ok) {
            recordTocResult(src.id, ok = false, error = resp.error)
            return Result.failure(Exception(resp.error))
        }
        var doc = Rules.parse(resp.body)
        var pageUrl = resp.via.ifBlank { tocUrl }
        var list = parseTocList(doc, pageUrl, t)

        // 若当前页尚未解析出目录（如搜索结果直接落到详情页），尝试按 link 规则或「小说目录」链接跳转真实章节页
        if (list.size < 2) {
            val linkSel = t?.link?.ifBlank { "a:contains(小说目录), a:contains(完整目录), a:contains(章节目录)" }
                ?: "a:contains(小说目录), a:contains(完整目录), a:contains(章节目录)"
            val linkTarget = if (linkSel.contains("@")) Rules.extract(doc, linkSel, pageUrl).ifBlank { null }
            else doc.selectFirst(linkSel)?.let { it.attr("href").ifBlank { it.attr("data-href") } }
            if (!linkTarget.isNullOrBlank()) {
                val absLink = Rules.absUrl(pageUrl, linkTarget)
                if (absLink != pageUrl) {
                    val rLink = Net.get(absLink, src.charset, base + "/", useCache = true)
                    if (rLink.ok) {
                        val dLink = Rules.parse(rLink.body)
                        val pLink = rLink.via.ifBlank { absLink }
                        val lLink = parseTocList(dLink, pLink, t)
                        if (lLink.size >= 2) {
                            doc = dLink; pageUrl = pLink; list = lLink
                        }
                    }
                }
            }
        }

        // 目录页认不出来（页面换版 / 认成推荐书单），换书页再认一遍
        if ((list.size < 2 || !tocLooksReal(book.url, list)) && pageUrl != book.url) {
            val r2 = Net.get(book.url, src.charset, base + "/", useCache = true)
            if (r2.ok) {
                val d2 = Rules.parse(r2.body)
                val p2 = r2.via.ifBlank { book.url }
                val l2 = parseTocList(d2, p2, t)
                if (l2.size > list.size && tocLooksReal(book.url, l2)) {
                    doc = d2; pageUrl = p2; list = l2
                }
            }
        }
        if (list.isEmpty()) {
            if (isTakenDownPage(doc, "")) {
                recordTocResult(src.id, ok = false, error = "书籍已下架")
                return Result.failure(Exception("该书已下架（源站因版权或作者要求已停止提供在线阅读）"))
            }
            val textLen = runCatching {
                doc.body()?.text()?.replace(Regex("\\s"), "")?.length ?: 0
            }.getOrDefault(0)
            val scripts = runCatching { doc.select("script").size }.getOrDefault(0)
            if (textLen < 400 && scripts >= 3) {
                recordTocResult(src.id, ok = false, error = "目录靠 JS 渲染")
                return Result.failure(Exception("这站的目录靠 JS 渲染，App 抓静态页面拿不到（起点这类站点都这样），换别的源搜同名书吧"))
            }
            recordTocResult(src.id, ok = false, error = "没认出章节目录")
            return Result.failure(Exception("没认出章节目录，可能这站改版了"))
        }

        if (!tocLooksReal(book.url, list)) {
            recordTocResult(src.id, ok = false, error = "抓到的是书籍列表而非目录")
            return Result.failure(Exception("这页上认不出章节目录（抓到的是书籍列表），可能这站改版了"))
        }

        // ── 多页目录分页自动提取（支持 nextPage 选择器与 {page} 模板，最多抓取 maxPages 页，保留原始顺序并防环去重） ──
        if (t != null && (t.nextPage.isNotBlank() || (t.page.contains("{page}") && t.maxPages > 1))) {
            val allChapters = ArrayList<Chapter>()
            val seenChapterUrls = HashSet<String>()
            val seenPageUrls = HashSet<String>()
            seenPageUrls.add(pageUrl)

            fun appendNewChapters(chs: List<Chapter>): Int {
                var count = 0
                for (ch in chs) {
                    if (ch.url.isNotBlank() && seenChapterUrls.add(ch.url)) {
                        allChapters.add(ch)
                        count++
                    }
                }
                return count
            }

            appendNewChapters(list)

            var curDoc = doc
            var curPageUrl = pageUrl
            var pageCount = 1
            val maxPages = t.maxPages.coerceIn(1, 100)

            while (pageCount < maxPages) {
                val nextPageSelector = t.nextPage.trim()
                val nextPageTarget = if (nextPageSelector.isNotBlank()) {
                    if (nextPageSelector.contains("@")) Rules.extract(curDoc, nextPageSelector, curPageUrl).ifBlank { null }
                    else curDoc.selectFirst(nextPageSelector)?.let { it.attr("href").ifBlank { it.attr("data-href") } }
                } else if (t.page.contains("{page}")) {
                    Rules.absUrl(base + "/", fillTocPage(t.page, book.url, page = pageCount + 1))
                } else null

                if (nextPageTarget.isNullOrBlank()) break

                val absNextUrl = Rules.absUrl(curPageUrl, nextPageTarget)
                if (absNextUrl.isBlank() || !seenPageUrls.add(absNextUrl)) {
                    // 已访问过或无效目标，立即停止，防止死循环
                    break
                }

                val nextResp = Net.get(absNextUrl, src.charset, base + "/", useCache = true)
                if (!nextResp.ok) break

                curDoc = Rules.parse(nextResp.body)
                curPageUrl = nextResp.via.ifBlank { absNextUrl }
                val nextList = parseTocList(curDoc, curPageUrl, t)
                val added = appendNewChapters(nextList)
                if (added == 0) {
                    // 连续没有新增章节时提前停止
                    break
                }
                pageCount++
            }
            if (allChapters.size >= list.size) {
                list = allChapters.mapIndexed { idx, ch -> ch.copy(index = idx) }
            }
        }

        val tocUrlReal = pageUrl

        var cover = book.cover
        var intro = book.intro
        var author = book.author
        val detailDoc = if (tocUrlReal == book.url) doc else {
            val r = Net.get(book.url, src.charset, base + "/", useCache = true)
            if (r.ok) Rules.parse(r.body) else doc
        }
        detailDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (cover.isBlank()) cover = Rules.absUrl(book.url, it)
        }
        src.detail?.let { d ->
            if (d.cover.isNotBlank() && cover.isBlank())
                Rules.extract(detailDoc, d.cover, book.url).ifBlank { null }?.let { cover = normalizeCover(src, it, book.url) }
            if (d.intro.isNotBlank() && intro.isBlank())
                Rules.extract(detailDoc, d.intro, book.url).ifBlank { null }?.let { intro = it }
            if (d.author.isNotBlank() && author.isBlank())
                Rules.extract(detailDoc, d.author, book.url).ifBlank { null }?.let { author = it }
        }
        if (author.isBlank()) {
            detailDoc.selectFirst("meta[name=author]")?.attr("content")?.ifBlank { null }?.let { author = it }
        }
        cover = normalizeCover(src, cover, book.url)
        recordTocResult(src.id, ok = true)
        return Result.success(Toc(list, cover, intro, author))
    }

    /** 识别盗版转码失败、广告推销、防盗占位等伪正文 */
    fun isTranscodeOrPiracyError(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return text.contains("转码失败") ||
            text.contains("换源切换源站阅读") ||
            text.contains("前往源网站进行阅读") ||
            text.contains("防盗采集") ||
            text.contains("内容正在手打中") ||
            text.contains("防采集系统") ||
            lower.contains("<h1>welcome</h1>") ||
            lower.contains("welcome to nginx")
    }

    /** 软错误页识别（源站 HTTP 200 但内容是报错/空壳/下架/转码失败） */
    fun isSoftError(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase()
        return body.contains("您访问的页面暂时无法访问") || body.contains("页面找不到了") ||
            body.contains("页面不存在") || body.contains("系统繁忙") || body.contains("访问过于频繁") ||
            body.contains("暂时无法访问哦") || body.contains("本书已下架") || body.contains("因版权问题") ||
            body.contains("文章不存在") || isTranscodeOrPiracyError(body) ||
            lower.contains("just a moment") || lower.contains("cf-browser-verification")
    }

    /** 正文 */
    fun getChapter(src: Source, chapter: Chapter): Result<Content> {
        if (src.id == "local") {
            val file = java.io.File(chapter.url.removePrefix("file://"))
            if (file.exists()) {
                val text = file.readText()
                NovelContentCache.put("local", chapter.url, text)
                return Result.success(Content(chapter.name, text, text))
            }
        }
        // 1. 优先命中正文两级缓存（L1 内存 / L2 磁盘）
        val cached = NovelContentCache.get(src.id, chapter.url)
        if (cached != null) {
            return Result.success(Content(chapter.name, cached, cached))
        }

        // 1.1 轻小说文库官方直解引擎：规避源站 403 与 Cloudflare 5秒盾
        if (src.id == "wenku8") {
            val aid = Wenku8Direct.extractAid(chapter.url)
            if (aid != null) {
                val directText = Wenku8Direct.getChapterContent(aid, chapter)
                if (!directText.isNullOrBlank()) {
                    recordContentResult(src.id, ok = true)
                    return Result.success(Content(chapter.name, directText, directText))
                }
            }
        }

        // 2. 模式检查：若配置为 WEBVIEW 渲染模式，提示在阅读器中触发网页渲染
        if (src.renderMode.equals("WEBVIEW", ignoreCase = true)) {
            val msg = "当前书源已配置为 WebView 渲染模式，请在阅读器中触发网页渲染"
            recordContentResult(src.id, ok = false, error = msg)
            return Result.failure(Exception(msg))
        }

        val base = getActiveBase(src) ?: src.base
        val isSlow = src.id == "sfacg" || chapter.url.contains("sfacg.com")
        var resp = Net.get(chapter.url, src.charset, base + "/", slow = isSlow, profile = src.requestProfile)
        if (!resp.ok || isSoftError(resp.body)) {
            // 正文拿失败、或拿到软错误页 → 隔 450ms 重试一次（SF 等源站偶发错误页/排队抖动，重试大多能成功）
            runCatching { Thread.sleep(450) }
            val retry = Net.get(chapter.url, src.charset, base + "/", slow = isSlow, profile = src.requestProfile)
            if (retry.ok && !isSoftError(retry.body)) {
                resp = retry
            }
        }
        if (!resp.ok && src.mirrors.isNotEmpty()) {
            val curUri = runCatching { java.net.URI(chapter.url) }.getOrNull()
            if (curUri != null) {
                for (mirror in src.mirrors) {
                    val mirrorUri = runCatching { java.net.URI(mirror) }.getOrNull() ?: continue
                    val altUrl = java.net.URI(mirrorUri.scheme, mirrorUri.authority, curUri.path, curUri.query, curUri.fragment).toString()
                    val r2 = Net.get(altUrl, src.charset, "$mirror/", slow = isSlow, profile = src.requestProfile)
                    if (r2.ok && !isSoftError(r2.body)) {
                        resp = r2
                        recordActiveBase(src.id, mirror)
                        break
                    }
                }
            }
        }
        if (!resp.ok) {
            // 轻小说文库在普通请求失败时做二次直解兜底
            if (src.id == "wenku8") {
                val aid = Wenku8Direct.extractAid(chapter.url)
                if (aid != null) {
                    val directText = Wenku8Direct.getChapterContent(aid, chapter)
                    if (!directText.isNullOrBlank()) {
                        recordContentResult(src.id, ok = true)
                        return Result.success(Content(chapter.name, directText, directText))
                    }
                }
            }
            val err = resp.error
            val msg = when {
                err.contains("超时") -> "正文请求超时（源站排队过慢，建议稍后刷新重试）"
                err.contains("403") -> "源站 403 拦截（防爬机制触发，建议换源或稍后再试）"
                err.isNotBlank() -> "网络请求失败：$err"
                else -> "正文请求失败"
            }
            recordContentResult(src.id, ok = false, httpCode = resp.code, error = msg)
            return Result.failure(Exception(msg))
        }

        if (isSoftError(resp.body) || isTranscodeOrPiracyError(resp.body)) {
            val lower = resp.body.lowercase()
            val isTakenDown = resp.body.contains("本书已下架") || resp.body.contains("因版权问题") || resp.body.contains("文章不存在")
            val isTranscode = resp.body.contains("转码失败") || resp.body.contains("换源切换源站阅读") || resp.body.contains("前往源网站进行阅读")
            val isChallenge = lower.contains("<h1>welcome</h1>") || lower.contains("just a moment") || lower.contains("cf-browser-verification")
            val msg = when {
                isTakenDown -> "该书籍在源站已下架（因版权或源站调整下线），换个源搜同名书试试"
                isTranscode -> "源站转码失败或该章节未收录（返回转码失败/推荐占位），建议换源阅读"
                isChallenge -> "源站触发浏览器安全验证（Cloudflare/WAF），请在书源管理中完成网页验证"
                src.id == "sfacg" -> "该章节在SF手机站暂未开放或已下线（如外链/公告），建议换下一章或选择目录"
                else -> "源站返回「页面暂时无法访问」（该老书在手机站未开放或章节已下线），换个源搜同名书试试"
            }
            recordContentResult(src.id, ok = false, httpCode = resp.code, error = msg)
            return Result.failure(Exception(msg))
        }

        val doc = Rules.parse(resp.body)
        val c = src.content

        var html = ""
        if (c != null && c.body.isNotBlank()) {
            for (br in c.body.split("||")) {
                var expr = br.trim()
                if (expr.isEmpty()) continue
                var regex = ""
                val hashIdx = expr.indexOf("##")
                if (hashIdx >= 0) {
                    regex = expr.substring(hashIdx)
                    expr = expr.substring(0, hashIdx)
                }
                val at = expr.lastIndexOf('@')
                val sel = if (at >= 0) expr.substring(0, at).trim() else expr
                val field = if (at >= 0) expr.substring(at + 1).trim().ifBlank { "html" } else "html"
                val el = runCatching { if (sel.isBlank()) doc.body() else doc.selectFirst(sel) }.getOrNull()
                if (el != null) {
                    var text = when (field) {
                        "html" -> el.html()
                        "texts" -> el.children().joinToString("\n") { it.text().trim() }.ifBlank { el.text().trim() }
                        else -> el.text()
                    }
                    if (regex.isNotEmpty()) {
                        text = Rules.applyRegex(text, regex)
                    }
                    val hasImg = el.select("img").isNotEmpty() || text.contains("<img") || text.contains("<!--image-->")
                    val minLen = if (src.id == "esjzone") 15 else 80
                    if (text.replace(Regex("\\s"), "").length >= minLen || hasImg) {
                        html = if (field == "html") text else "<p>" + text.replace("\n", "</p><p>") + "</p>"
                        break
                    }
                }
            }
        }
        if (html.isBlank()) html = guessContent(doc)
        // 规则命中的东西太短，多半抓到的是导航/提示条，换成整页里最像正文的那块
        if (html.replace(Regex("\\s"), "").length < 180 && src.id != "esjzone") {
            val guess = guessContent(doc)
            if (guess.replace(Regex("\\s"), "").length > html.replace(Regex("\\s"), "").length) html = guess
        }
        // 有的站免费章节正常给正文，付费章节只给一段购买提示——这时别报"被拦截"，要报准
        val hasHtmlImages = html.contains("<img") || html.contains("<!--image-->")
        val minLenCheck = if (src.id == "esjzone") 15 else 120
        if (html.replace(Regex("\\s"), "").length < minLenCheck && !hasHtmlImages) {
            val page = doc.text()
            val vipMark = listOf("VIP章节", "vip章节", "购买本章", "订阅本章", "本章需购买", "余额不足", "充值后阅读", "登录后阅读", "请支持作者", "VIP章节必须登录购买")
                .firstOrNull { page.contains(it) }
            if (vipMark != null) {
                recordContentResult(src.id, ok = false, error = "VIP 章节")
                return Result.failure(Exception("这章是源站的付费/VIP 章节，得先在源站登录购买才能看，建议换个源搜同名书"))
            }
            if (isErrorPage(doc)) {
                recordContentResult(src.id, ok = false, error = "源站错误页")
                return Result.failure(Exception("源站返回「暂时无法访问」（该老书在手机站未开放或已下线），建议换个源搜同名书"))
            }
            // 如果正文区里有图片（插图画/彩页/人设），保留交给阅读器以插图卡片形式呈现
        }
        if (html.isBlank()) {
            if (isTakenDownPage(doc)) {
                recordContentResult(src.id, ok = false, error = "书籍已下架")
                return Result.failure(Exception("该书已下架（源站因版权或作者要求已停止提供在线阅读）"))
            }
            if (isErrorPage(doc)) {
                recordContentResult(src.id, ok = false, error = "源站错误页")
                return Result.failure(Exception("源站返回「暂时无法访问」（该老书在手机站未开放或已下线），建议换个源搜同名书"))
            }
            recordContentResult(src.id, ok = false, error = "正文未解析出内容")
            return Result.failure(Exception("正文未解析出内容（可能章节需要登录或该页结构改版）"))
        }

        // 分页拼接
        if (c != null && c.next.isNotBlank()) {
            var curDoc = doc
            var curUrl = chapter.url
            var guard = 0
            while (guard++ < 8) {
                val nextEl = runCatching { curDoc.selectFirst(c.next) }.getOrNull() ?: break
                val href = nextEl.attr("href")
                if (href.isBlank()) break
                val nextUrl = Rules.absUrl(curUrl, href)
                if (!Regex("[_\\-/]\\d+(_\\d+)?\\.html?$").containsMatchIn(nextUrl)) break
                val r2 = Net.get(nextUrl, src.charset, base + "/", slow = isSlow, profile = src.requestProfile)
                if (!r2.ok) break
                val d2 = Rules.parse(r2.body)
                val more = Rules.autoContent(d2)
                if (more.isBlank()) break
                html += more
                curDoc = d2; curUrl = nextUrl
            }
        }

        val cleaned = Rules.stripAds(html)
        val text = Rules.htmlToParagraphs(cleaned, chapter.url)
        if (isTranscodeOrPiracyError(text)) {
            recordContentResult(src.id, ok = false, error = "源站转码失败或盗版占位")
            return Result.failure(Exception("源站转码失败或该章节未收录（返回转码失败/推荐占位），建议换源阅读"))
        }
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { chapter.name }
        if (text.contains("请支持作者") || text.contains("VIP章节必须登录购买") || text.contains("购买后才可阅读")) {
            recordContentResult(src.id, ok = false, error = "VIP 章节")
            return Result.failure(Exception("这章是源站的付费/VIP 章节，得先在源站登录购买才能看，建议换个源搜同名书"))
        }
        val hasImages = text.contains("<!--image-->") || cleaned.contains("<img")
        val len = text.replace(Regex("\\s"), "").length
        // 一百字以下且没有插图的通常不是正文：要么被拦，要么抓到的是书页/推荐列表那几行字
        if (len < 120 && !hasImages) {
            if (isTakenDownPage(doc, text)) {
                recordContentResult(src.id, ok = false, error = "书籍已下架")
                return Result.failure(Exception("该书已下架（源站因版权或作者要求已停止提供在线阅读）"))
            }
            if (isErrorPage(doc)) {
                recordContentResult(src.id, ok = false, error = "源站错误页")
                return Result.failure(Exception("源站返回「暂时无法访问」（该老书在手机站未开放或已下线），建议换个源搜同名书"))
            }
            if (len == 0) {
                recordContentResult(src.id, ok = false, error = "章节内容为空")
                return Result.failure(Exception("源站这一章是空的（有的站把同一章挂了两条，其中一条只有标题），翻到下一章吧"))
            }
            if (doc.select("a[href]").size >= 12) {
                recordContentResult(src.id, ok = false, error = "抓到书籍页而非正文")
                return Result.failure(Exception("抓到的这页是书籍页/目录页，不是正文，可能这站改版了"))
            }
            // 若显式规则已精确命中正文区域（如 SF 的 .Content_Frame、正文 div），短文本多为作者通知/小节，正常展示，不阻断报错
            val ruleMatched = c != null && c.body.isNotBlank() && html.isNotBlank()
            if (!ruleMatched) {
                val err = "正文内容过短（仅 ${len} 字），可能触发源站拦截或防盗提示"
                recordContentResult(src.id, ok = false, error = err)
                return Result.failure(Exception(err))
            }
        }

        NovelContentCache.put(src.id, chapter.url, text)
        recordContentResult(src.id, ok = true)
        return Result.success(Content(title, text, cleaned))
    }

    // ─────────── 目录 / 正文的兜底识别 ───────────
    // 站点改版后规则选择器往往整套失效，这里用「候选清单 + 打分」硬认出目录和正文，
    // 只要页面结构别太离谱，就不用等发新版才能看。

    /** 是否是源站版权下架提示页 */
    fun isTakenDownPage(doc: org.jsoup.nodes.Document, text: String = ""): Boolean {
        val full = (doc.title() + " " + (doc.body()?.text().orEmpty()) + " " + doc.html() + " " + text).lowercase()
        return listOf("已下架", "已被下架", "已被删除", "已被刪除", "已被屏蔽", "已被封禁", "下架处理", "停止提供", "版权原因", "因版权", "版权问题", "不再提供", "不再提供该小说", "暂时下架", "内容已被删除", "作品已被下架", "停止在线阅读").any { full.contains(it) }
    }

    /** 源站自己吐的出错页（不是我们解析失败），提示要说准，别让人以为软件坏了 */
    private fun isErrorPage(doc: org.jsoup.nodes.Document): Boolean {
        val t = runCatching { doc.title() }.getOrDefault("")
        if (t.contains("出错") || t.contains("无法访问") || t.contains("页面找不到") || t.contains("404")) return true
        val body = runCatching { doc.body()?.text().orEmpty() }.getOrDefault("")
        return isSoftError(body) || body.contains("出错啦")
    }

    /** 地址形态：数字统一抹成 N。/b/785881/ 和 /b/142354/ 是同形，/c/9958959/ 不是 */
    private fun shapeOf(url: String): String = runCatching {
        java.net.URI(url).path.orEmpty().replace(Regex("\\d+"), "N").lowercase()
    }.getOrDefault("")

    /**
     * 目录真不真：章节地址不能和书籍页同形。
     * 兜底识别在书页上跑时，最容易把「猜你喜欢」那几个推荐书链接当章节，
     * 结果目录只有 3、4 条，点进去看到的是别人的简介。
     */
    fun tocLooksReal(bookUrl: String, chapters: List<Chapter>): Boolean {
        if (chapters.isEmpty()) return false
        // 章节名大量重复 = 抓到的是站点导航/栏目菜单（「手机小说」「全部小说」这类），不是章节
        val distinct = chapters.map { it.name }.distinct().size
        if (chapters.size >= 8 && distinct * 3 < chapters.size) return false
        if (chapters.size >= 15) return true
        val bookShape = shapeOf(bookUrl)
        if (bookShape.isBlank()) return true
        val same = chapters.count { shapeOf(it.url) == bookShape }
        return same * 2 < chapters.size
    }

    private val TOC_SELECTORS = listOf(
        "#list dl dd a", "#list dd a", "dl dd a", "#chapterlist a", "#chapter-list a",
        "#list-chapterAll a", ".listmain a", "#catalog a", ".catalog a", "ul.catalogul a",
        "#vol_1 a", ".volume a", ".dirlist a", ".chapter a", ".section-list a",
        ".book_list a", "#booklist a", "#newlist a", "#content table td a",
        ".module-row-text", ".chapter-list a", ".mulu_list a", "a[href*=chapter]",
        "a[href*=/read/]", "dd a[href$='.html']", ".list-chapter a",
    )

    private fun guessToc(doc: org.jsoup.nodes.Document, pageUrl: String): List<Chapter> {
        var best: List<Pair<String, String>> = emptyList()
        var bestScore = 0
        for (sel in TOC_SELECTORS) {
            val anchors = runCatching { doc.select(sel) }.getOrDefault(emptyList())
            if (anchors.size < 2) continue
            val items = anchors.mapNotNull { el ->
                val target = if (el.tagName().equals("a", true)) el else el.selectFirst("a") ?: el
                val name = target.text().replace(Regex("\\s+"), " ").trim()
                val href = target.attr("href").ifBlank { target.attr("data-href") }.trim()
                if (name.isEmpty() || href.isEmpty()) return@mapNotNull null
                if (href.startsWith("#") || href.startsWith("javascript")) return@mapNotNull null
                val abs = Rules.absUrl(pageUrl, href)
                if (!abs.startsWith("http") || abs == pageUrl) return@mapNotNull null
                name to abs
            }.distinctBy { it.second }
            if (items.size < 2) continue
            var score = items.size
            if (sel.contains("chapter") || sel.contains("catalog") || sel.contains("volume") ||
                sel.contains("list") || sel.contains("read") || sel.contains("mulu")
            ) score += 8
            if (items.size > 400) score -= 60      // 整站链接堆，不是目录
            if (score > bestScore) { bestScore = score; best = items }
        }
        return best.mapIndexed { i, (n, u) -> Chapter(n, u, i) }
    }

    private val CONTENT_SELECTORS = listOf(
        "div.yuedu.Content_Frame > div", ".Content_Frame > div", ".yuedu [style*=\"text-indent\"]",
        "[style*=\"text-indent\"]", ".yuedu",
        "#content", "#chaptercontent", "#acontent", "#booktext", "#txtContent",
        "#nr1", "#nr", "#mlfy_main_text", "#zjny", "#contentbox", "#booktxt",
        "#htmlContent", "#chapter-content", "#article-content", "#BookText",
        ".read-content", ".article-content", ".readbox .content", ".showtxt",
        ".yd_text2", ".content", ".text", "article",
    )

    private fun guessContent(doc: org.jsoup.nodes.Document): String {
        var bestHtml = ""
        var bestLen = 0
        for (sel in CONTENT_SELECTORS) {
            val el = runCatching { doc.selectFirst(sel) }.getOrNull() ?: continue
            val len = el.text().replace(Regex("\\s"), "").length
            if (len >= 180 && len > bestLen) { bestLen = len; bestHtml = el.html() }
        }
        if (bestHtml.isNotBlank()) return bestHtml
        return Rules.autoContent(doc)
    }

    /** 书源体检：支持 probeKeyword、probeUrl 与 probeMode */
    fun health(src: Source): SourceResult {
        if (src.probeMode.equals("URL", ignoreCase = true) && src.probeUrl.isNotBlank()) {
            val t0 = System.currentTimeMillis()
            val base = getActiveBase(src) ?: src.base
            val targetUrl = if (src.probeUrl.startsWith("http")) src.probeUrl else base + src.probeUrl
            val resp = Net.get(targetUrl, src.charset, base + "/", profile = src.requestProfile)
            val ms = System.currentTimeMillis() - t0
            val ok = resp.ok
            val st = classifyStatus(ok, resp.code, resp.error, if (ok) 1 else 0)
            return SourceResult(
                sourceId = src.id,
                sourceName = src.name,
                ok = ok,
                books = emptyList(),
                error = if (ok) "" else resp.error.ifBlank { "HTTP ${resp.code}" },
                ms = ms,
                status = st,
                httpCode = resp.code,
            )
        }
        val key = if (src.probeKeyword.isNotBlank()) src.probeKeyword else when {
            src.apiKind == "sfacg" -> "魔王"
            src.group.contains("轻小说") -> "魔王"
            else -> "凡人修仙传"
        }
        return searchSource(src, key)
    }

    /**
     * 分段体检：搜索 → 目录 → 正文，哪一步断了直接说哪一步断的。
     * 支持探针配置，不因未搜到固定书名而误杀源站。
     */
    fun healthDeep(src: Source): DeepHealthResult {
        val s = health(src)
        if (!s.ok) {
            return DeepHealthResult(false, "搜索/连接：${s.error.ifBlank { "异常" }}", s.status, s.httpCode)
        }
        if (s.books.isEmpty()) {
            if (src.probeMode.equals("URL", ignoreCase = true)) {
                return DeepHealthResult(true, "探针 URL 正常连通 · HTTP ${s.httpCode}", SourceStatus.NORMAL, s.httpCode)
            }
            val key = if (src.probeKeyword.isNotBlank()) src.probeKeyword else if (src.apiKind == "sfacg" || src.group.contains("轻小说")) "魔王" else "凡人修仙传"
            return DeepHealthResult(false, "搜索无匹配结果（HTTP ${if (s.httpCode > 0) s.httpCode else 200}，但未搜到「$key」，可能源站未收录该词）", SourceStatus.RULE_INVALID, s.httpCode)
        }
        val book = s.books.first()
        val tocRes = runCatching { getToc(src, book) }
        val toc = tocRes.getOrNull()
        if (toc == null) {
            val err = tocRes.exceptionOrNull()?.message ?: "目录获取失败"
            val st = classifyStatus(false, 0, err, 0)
            return DeepHealthResult(false, "目录：$err", st)
        }
        val list = toc.getOrElse {
            val err = it.message ?: "目录解析失败"
            val st = classifyStatus(false, 0, err, 0)
            return DeepHealthResult(false, "目录：$err", st)
        }
        if (list.chapters.size < 2) {
            return DeepHealthResult(false, "目录：只认出 ${list.chapters.size} 章", SourceStatus.RULE_INVALID)
        }
        // 抽查正文：第1章、中间章节、1/4章节
        val probes = listOfNotNull(
            list.chapters.firstOrNull(),
            list.chapters.getOrNull(list.chapters.size / 2),
            list.chapters.getOrNull(list.chapters.size / 4),
        ).distinctBy { it.url }
        var lastErr = ""
        var tried = 0
        for (ch in probes) {
            tried++
            val res = runCatching { getChapter(src, ch) }
            val out = res.getOrNull()?.getOrNull()
            if (out == null) {
                val e = res.getOrNull()?.exceptionOrNull() ?: res.exceptionOrNull()
                lastErr = e?.message ?: "正文取不到"
                continue
            }
            val n = out.text.count { !it.isWhitespace() }
            if (n >= 120) {
                val where = if (tried == 1) "" else "（换到第 ${ch.index + 1} 章试的）"
                return DeepHealthResult(true, "目录 ${list.chapters.size} 章 · 正文 $n 字$where", SourceStatus.NORMAL, 200)
            }
            lastErr = "正文内容过短（仅 $n 字）"
        }
        val finalStatus = classifyStatus(false, 0, lastErr, 0)
        return DeepHealthResult(false, "目录 ${list.chapters.size} 章 · 正文：${lastErr.ifBlank { "抽了 $tried 章都取不到正文" }}", finalStatus)
    }

    /** 并发多源搜索，边出结果边回调，支持底层任务取消与统一 Deadline */
    fun searchAll(
        sources: List<Source>,
        key: String,
        tag: Any? = null,
        onUpdate: (SourceResult) -> Unit,
    ) {
        val active = sources.filterNot { isBlacklisted(it.id) }
        if (active.isEmpty()) return
        val latch = java.util.concurrent.CountDownLatch(active.size)
        val deadlineMs = System.currentTimeMillis() + 14_000L
        val searchTag = tag ?: "searchAll_${System.currentTimeMillis()}"
        val futures = java.util.Collections.synchronizedList(ArrayList<java.util.concurrent.Future<*>>())
        activeSearchFutures[searchTag] = futures

        for (src in active) {
            val future = searchPool.submit {
                val t0 = System.currentTimeMillis()
                val r = runCatching {
                    searchSource(src, key, deadlineMs = deadlineMs, tag = searchTag)
                }.getOrElse {
                    val err = Net.friendlyError(it)
                    val st = classifyStatus(false, 0, err, 0)
                    SourceResult(src.id, src.name, false, error = err, ms = System.currentTimeMillis() - t0, status = st)
                }
                if (!r.ok && (r.error.contains("502") || r.error.contains("504") || r.error.contains("拒绝连接"))) {
                    markFailed(src.id, 60_000L)
                }
                try { onUpdate(r) } finally { latch.countDown() }
            }
            futures.add(future)
        }
        try {
            latch.await(14, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // interrupted
        } finally {
            // 超时后尚未完成的任务调用 cancel，并真正取消未完成的网络请求
            for (f in futures) {
                if (!f.isDone) {
                    runCatching { f.cancel(true) }
                }
            }
            activeSearchFutures.remove(searchTag)
        }
    }
}

/** Z-Library 官方 App 接口 */
object Zlib {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    private val bases = listOf("https://z-lib.gd", "https://z-library.sk", "https://1lib.sk")

    /** 返回 (书籍列表, 错误) */
    fun search(key: String): Pair<List<Book>, String> {
        var lastErr = "连不上"
        for (base in bases) {
            val resp = Net.post(
                "$base/eapi/book/search",
                mapOf("message" to key, "page" to "1", "limit" to "20"),
                charset = "utf-8",
                referer = "$base/",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            )
            if (!resp.ok) { lastErr = resp.error; continue }
            val parsed = runCatching { parse(resp.body, base) }.getOrNull()
            if (parsed != null && parsed.first.isNotEmpty()) return parsed
            lastErr = parsed?.second ?: "接口返回异常"
        }
        return emptyList<Book>() to lastErr
    }

    private fun parse(json: String, base: String): Pair<List<Book>, String> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("books") ?: return emptyList<Book>() to "没有结果"
        val out = ArrayList<Book>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val id = o.optString("id")
            val hash = o.optString("hash")
            val cover = o.optString("cover")
            val year = o.optString("year")
            val lang = o.optString("language")
            val publisher = o.optString("publisher")
            val desc = buildString {
                if (publisher.isNotBlank()) append("出版社：$publisher\n")
                if (year.isNotBlank() && year != "0") append("年份：$year\n")
                if (lang.isNotBlank()) append("语言：$lang\n")
                append("格式：").append(
                    buildList {
                        if (o.optString("extension").isNotBlank()) add(o.optString("extension").uppercase())
                    }.joinToString(" / ").ifBlank { "EPUB / PDF" }
                )
            }
            out.add(
                Book(
                    name = title,
                    author = o.optString("author"),
                    cover = cover,
                    intro = desc,
                    url = if (id.isNotBlank() && hash.isNotBlank()) "$base/book/$id/$hash" else "$base/s/$title",
                    sourceId = "zlib",
                    sourceName = "Z-Library",
                    year = year,
                    language = lang,
                    kind = o.optString("extension").uppercase(),
                    external = true,
                    downloadUrl = "$base/book/$id/$hash",
                )
            )
        }
        return out to ""
    }
}
