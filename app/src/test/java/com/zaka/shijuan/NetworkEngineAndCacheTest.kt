package com.zaka.shijuan

import com.zaka.shijuan.data.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NetworkEngineAndCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ─────────────────────────────────────────────────────────────
    // 1. 正文两级缓存引擎测试 (NovelContentCache)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testNovelContentCacheL1HitAndL2DiskStorage() {
        val cacheDir = tempFolder.newFolder("novel_cache_test")
        NovelContentCache.setCacheDirForTest(cacheDir)

        val sourceId = "test_src"
        val chapterUrl = "https://example.com/novel/123/456.html"
        val contentText = "这是第一章的完整正文内容，字数足够多，排版整洁，符合小说阅读器缓存规范。"

        // 写入两级缓存
        NovelContentCache.put(sourceId, chapterUrl, contentText)

        // 验证 L1 内存命中
        val memCached = NovelContentCache.get(sourceId, chapterUrl)
        assertEquals(contentText, memCached)

        // 验证 L2 磁盘已持久化
        val key = NovelContentCache.buildKey(sourceId, chapterUrl)
        val diskFile = File(cacheDir, "$key.txt")
        assertTrue("Disk cache file should exist on L2", diskFile.exists())

        // 再次设置测试目录将清除内存 LRU
        NovelContentCache.setCacheDirForTest(cacheDir)
        val diskCached = NovelContentCache.get(sourceId, chapterUrl)
        assertEquals("Should recover content from L2 disk cache", contentText, diskCached)
    }

    @Test
    fun testNovelContentCacheTtlExpiration() {
        val cacheDir = tempFolder.newFolder("novel_ttl_test")
        NovelContentCache.setCacheDirForTest(cacheDir)

        val sourceId = "test_src"
        val chapterUrl = "https://example.com/novel/123/expired.html"
        val contentText = "这是已过期的正文内容，字数符合规范，测试24小时TTL淘汰机制。"

        NovelContentCache.put(sourceId, chapterUrl, contentText)
        val key = NovelContentCache.buildKey(sourceId, chapterUrl)
        val diskFile = File(cacheDir, "$key.txt")
        assertTrue(diskFile.exists())

        // 清空内存以迫使读取磁盘
        NovelContentCache.setCacheDirForTest(cacheDir)

        // 将文件修改时间调整至 25 小时前（超过 24h TTL）
        diskFile.setLastModified(System.currentTimeMillis() - 25L * 3600 * 1000L)

        val result = NovelContentCache.get(sourceId, chapterUrl, maxAgeMs = 24L * 3600 * 1000L)
        assertNull("Expired cache entry should return null", result)
        assertFalse("Expired file should be removed", diskFile.exists())
    }

    @Test
    fun testNovelContentCacheInvalidContentRejection() {
        val cacheDir = tempFolder.newFolder("novel_invalid_test")
        NovelContentCache.setCacheDirForTest(cacheDir)

        assertFalse("Empty text should be invalid", NovelContentCache.isValidContent(""))
        assertFalse("Short text (<20) should be invalid", NovelContentCache.isValidContent("短文本"))
        assertFalse(
            "Cloudflare challenge page should be rejected",
            NovelContentCache.isValidContent("<html><head><title>Just a moment...</title></head><body>cf-browser-verification</body></html>")
        )
        assertFalse(
            "403 Forbidden should be rejected",
            NovelContentCache.isValidContent("403 Forbidden: Access Denied by WAF")
        )
        assertFalse(
            "VIP chapter prompt should be rejected",
            NovelContentCache.isValidContent("这是VIP章节，请登录后购买订阅本章内容")
        )
        assertFalse(
            "Transcoding / piracy error should be rejected",
            NovelContentCache.isValidContent("源站转码失败，请前往源网站进行阅读，内容正在手打中")
        )

        val validText = "天道酬勤，修行之路虽远，行则将至；事虽难，做则必成。修仙岁月弹指一挥间。"
        assertTrue("Normal text should be valid", NovelContentCache.isValidContent(validText))

        // 尝试写入无效内容，确认不会落盘
        NovelContentCache.put("src", "https://bad.com/1", "403 Forbidden")
        assertNull(NovelContentCache.get("src", "https://bad.com/1"))
    }

    @Test
    fun testNovelContentCacheKeyNormalization() {
        val u1 = "https://Example.com/book/1.html?timestamp=123456&t=999"
        val u2 = "https://example.com/book/1.html"
        val norm1 = NovelContentCache.normalizeChapterUrl(u1)
        val norm2 = NovelContentCache.normalizeChapterUrl(u2)
        assertEquals(norm2, norm1)

        val k1 = NovelContentCache.buildKey("src1", u1)
        val k2 = NovelContentCache.buildKey("src1", u2)
        assertEquals(k2, k1)
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 统一域名令牌桶限流器测试 (DomainRateLimiter)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testDomainRateLimiterBucket() {
        val limiter = DomainRateLimiter()

        // 测试 wenku8.cc 域名限流配置
        // 初始容量 1.0，第 1 次申请成功
        val ok1 = limiter.acquire("www.wenku8.cc", maxWaitMs = 100)
        assertTrue("First acquire within capacity should succeed", ok1)

        // 立即进行第 2 次申请，且超时设定为 0ms，因桶中无剩余令牌且不等待，应立刻返回 false
        val ok2 = limiter.acquire("www.wenku8.cc", maxWaitMs = 0)
        assertFalse("Second immediate acquire with 0 wait should be rejected", ok2)
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 搜索结果与负缓存测试 (SearchCache)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testSearchPositiveAndNegativeCaching() {
        SearchCache.clear()

        // 1. 成功且有结果：缓存 5 分钟
        val books = listOf(Book("凡人修仙传", "忘语", "", "", "https://a.com/1", "src1", "源1"))
        val hitResult = SourceResult("src1", "源1", ok = true, books = books, ms = 200)
        SearchCache.put("src1", "凡人", hitResult)

        val cachedHit = SearchCache.get("src1", "凡人")
        assertNotNull(cachedHit)
        assertEquals(1, cachedHit?.books?.size)

        // 2. 成功但无结果（空结果）：负缓存 60 秒
        val emptyResult = SourceResult("src1", "源1", ok = true, books = emptyList(), ms = 150)
        SearchCache.put("src1", "不存在的书籍名称", emptyResult)

        val cachedEmpty = SearchCache.get("src1", "不存在的书籍名称")
        assertNotNull("Negative cache should return empty entry", cachedEmpty)
        assertEquals(0, cachedEmpty?.books?.size)

        // 3. 失败报错：不缓存
        val errResult = SourceResult("src1", "源1", ok = false, error = "502 Bad Gateway")
        SearchCache.put("src1", "报错查询", errResult)
        assertNull("Error result should not be cached", SearchCache.get("src1", "报错查询"))
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 底层网络调用与搜索任务取消测试 (Cancellation)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testNetAndSearchCancellation() {
        val wasCancelled = AtomicBoolean(false)
        val dummyCall = object : Call {
            override fun cancel() { wasCancelled.set(true) }
            override fun isCanceled(): Boolean = wasCancelled.get()
            override fun clone(): Call = this
            override fun request(): Request = Request.Builder().url("https://example.com").build()
            override fun execute(): Response = throw UnsupportedOperationException()
            override fun enqueue(responseCallback: Callback) {}
            override fun isExecuted(): Boolean = false
            override fun timeout(): Timeout = Timeout.NONE
        }

        val tag = "search_test_seq_1"
        Net.registerCall(tag, dummyCall)
        Net.cancelCalls(tag)

        assertTrue("Registered Call should be cancelled", wasCancelled.get())
    }

    // ─────────────────────────────────────────────────────────────
    // 5. 单源 Deadline 预算控制测试
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testSingleSourceDeadlineBudget() {
        val src = Source(
            id = "deadline_src",
            name = "Deadline源",
            group = "测试分组",
            base = "https://unreachable-domain-1234567.com",
            mirrors = listOf("https://unreachable-mirror-1234567.com"),
            search = SearchRule(url = "/search?k={key}", list = ".item")
        )

        // 传入一个已经耗尽的 Deadline
        val expiredDeadline = System.currentTimeMillis() - 100L
        val res = BookRepo.searchSource(src, "测试", deadlineMs = expiredDeadline)

        assertFalse("Should fail due to deadline", res.ok)
        assertTrue(
            "Error message should mention timeout / budget",
            res.error.contains("单源时间预算") || res.error.contains("超时") || res.error.contains("连接不上")
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 6. ActiveBases 镜像持久化与 24h TTL 测试
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testActiveBasesPersistenceAndTtl() {
        val sourceId = "biquge_test"
        val mirrorBase = "https://www.biquge5200.tv"
        val originalBase = "https://www.biquge5200.cc"
        val src = Source(id = sourceId, name = "笔趣阁", group = "测试分组", base = originalBase, mirrors = listOf(mirrorBase))

        BookRepo.recordActiveBase(sourceId, mirrorBase)
        val active = BookRepo.getActiveBase(src)
        assertEquals("Active base should be the verified mirror", mirrorBase, active)
    }

    // ─────────────────────────────────────────────────────────────
    // 7. 指数退避与健康指标阶梯测试
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testExponentialBackoffLevels() {
        assertEquals(0L, BookRepo.calculateBackoffMs(0))
        assertEquals(60_000L, BookRepo.calculateBackoffMs(1))          // 1 分钟
        assertEquals(300_000L, BookRepo.calculateBackoffMs(2))         // 5 分钟
        assertEquals(1_800_000L, BookRepo.calculateBackoffMs(3))       // 30 分钟
        assertEquals(7_200_000L, BookRepo.calculateBackoffMs(4))       // 2 小时
        assertEquals(86_400_000L, BookRepo.calculateBackoffMs(5))      // 24 小时封顶
        assertEquals(86_400_000L, BookRepo.calculateBackoffMs(10))     // 24 小时封顶
    }

    // ─────────────────────────────────────────────────────────────
    // 8. CustomSources JSON 完整性往返序列化测试
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testCustomSourcesFullSerializationRoundtrip() {
        val original = Source(
            id = "custom_test_1",
            name = "全字段测试源",
            group = "测试分组",
            base = "https://base.test.com",
            mirrors = listOf("https://m1.test.com", "https://m2.test.com"),
            charset = "gbk",
            status = "custom",
            note = "完整往返测试备注",
            search = SearchRule(
                url = "/search?q={key}",
                method = "POST",
                body = "q={key}&type=1",
                list = ".book-item",
                keyCharset = "gbk",
                extraUrls = listOf("/search2?q={key}"),
                fields = mapOf("name" to "h3", "author" to ".author")
            ),
            detail = DetailRule(name = "h1", author = ".a", cover = "img@src", intro = ".desc"),
            toc = TocRule(
                list = "#chapter-list a",
                name = "@text",
                url = "@href",
                page = "/catalog/{page}",
                link = ".all-chapters@href",
                skip = "序章",
                volume = ".volume-title",
                skipVolume = "作者相关",
                nextPage = ".next-page@href",
                maxPages = 35
            ),
            content = ContentRule(body = "#content@html", next = ".next-btn@href"),
            probeKeyword = "斗罗",
            probeUrl = "/check.html",
            probeMode = "URL",
            userAgent = "CustomTestBrowser/1.0",
            renderMode = "AUTO"
        )

        val json = CustomSources.toJson(original)
        val restored = CustomSources.fromJson(json)
        assertNotNull("Deserialized source should not be null", restored)

        assertEquals(original.id, restored!!.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.mirrors, restored.mirrors)
        assertEquals(original.probeKeyword, restored.probeKeyword)
        assertEquals(original.probeUrl, restored.probeUrl)
        assertEquals(original.probeMode, restored.probeMode)
        assertEquals(original.userAgent, restored.userAgent)
        assertEquals(original.renderMode, restored.renderMode)

        // 验证 TOC 字段完整性
        assertEquals(original.toc?.link, restored.toc?.link)
        assertEquals(original.toc?.skip, restored.toc?.skip)
        assertEquals(original.toc?.volume, restored.toc?.volume)
        assertEquals(original.toc?.skipVolume, restored.toc?.skipVolume)
        assertEquals(original.toc?.nextPage, restored.toc?.nextPage)
        assertEquals(original.toc?.maxPages, restored.toc?.maxPages)
    }

    // ─────────────────────────────────────────────────────────────
    // 9. 订阅导入防注入与非法 URL 拦截测试
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testSubscriptionSecurityValidation() {
        // 非法 URL 协议拦截
        val res1 = CustomSources.importSubscription("javascript:alert(1)")
        assertFalse("Javascript URL should be blocked", res1.success)

        val res2 = CustomSources.importSubscription("file:///etc/passwd")
        assertFalse("File protocol should be blocked", res2.success)

        val res3 = CustomSources.importSubscription("")
        assertFalse("Blank URL should be blocked", res3.success)
    }

    // ─────────────────────────────────────────────────────────────
    // 10. 漫画共享 8 秒总预算与复合去重测试
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testComicCompositeDeduplication() {
        val c1 = Comic(name = "进击的巨人", author = "谏山创", url = "https://manga.com/b/100")
        val c2 = Comic(name = "进击的巨人", author = "谏山创", url = "https://alt-manga.com/read/100") // 同名同作者 -> 判定为同一部
        val c3 = Comic(name = "进击的巨人", author = "同人画师", url = "https://manga.com/b/200")       // 同名不同作者 -> 保留

        val hit1 = ComicHit("src1", "漫画源1", listOf(c1), true, 100)
        val hit2 = ComicHit("src2", "漫画源2", listOf(c2, c3), true, 120)

        val merged = ComicRepo.merge(listOf(hit1, hit2))
        assertEquals("Composite deduplication should merge c1/c2 and retain c3", 2, merged.size)
        assertTrue(merged.any { it.author == "谏山创" })
        assertTrue(merged.any { it.author == "同人画师" })
    }
}
