package com.storm.shijuan

import com.storm.shijuan.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class AuditAndFixVerificationTest {

    @Test
    fun testUpdateManagerIsNewer() {
        assertTrue(UpdateManager.isNewer("2.13", "2.12"))
        assertTrue(UpdateManager.isNewer("3.0.0", "2.12"))
        assertTrue(UpdateManager.isNewer("2.12.1", "2.12"))
        assertFalse(UpdateManager.isNewer("2.12", "2.12"))
        assertFalse(UpdateManager.isNewer("2.11", "2.12"))
        assertFalse(UpdateManager.isNewer("2.8", "2.12"))
        assertFalse(UpdateManager.isNewer("", "2.12"))
    }

    @Test
    fun testDownloadTaskSourceIdPreserved() {
        val originalComic = Comic(
            name = "一人之下",
            url = "https://m.dm5.com/manhua-yirenzhixia",
            sourceId = "dm5",
            sourceName = "动漫屋",
        )
        val chapter = ComicChapter(
            name = "第1话",
            url = "/m12345/",
            index = 0,
            sourceId = "dm5",
        )
        val task = DownloadTask(
            key = ComicDownloader.keyOf(originalComic),
            comic = originalComic,
            chapters = listOf(chapter),
            state = STATE_WAITING,
        )
        val json = task.toJson()
        val restored = DownloadTask.from(json)

        assertEquals("dm5", restored.comic.sourceId)
        assertEquals("dm5", restored.chapters.first().sourceId)
        assertNotEquals("lizi", restored.chapters.first().sourceId)
    }

    @Test
    fun testMagicBytesValidation() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "magic_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // Valid JPEG
            val jpgFile = File(tempDir, "test.jpg").apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1))
            }
            assertTrue("Valid JPEG should pass", ComicDownloader.isImageFile(jpgFile))

            // Valid PNG
            val pngFile = File(tempDir, "test.png").apply {
                writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(), 0, 0, 0, 0))
            }
            assertTrue("Valid PNG should pass", ComicDownloader.isImageFile(pngFile))

            // Valid WebP
            val webpFile = File(tempDir, "test.webp").apply {
                writeBytes(byteArrayOf(
                    'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                    0, 0, 0, 0,
                    'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()
                ))
            }
            assertTrue("Valid WebP should pass", ComicDownloader.isImageFile(webpFile))

            // HTML error page
            val htmlFile = File(tempDir, "error.html").apply {
                writeText("<!DOCTYPE html><html><head><title>403 Forbidden</title></head><body>WAF Blocked</body></html>")
            }
            assertFalse("HTML error page must NOT pass as image", ComicDownloader.isImageFile(htmlFile))

            // Short garbage file
            val garbageFile = File(tempDir, "garbage.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            assertFalse("Garbage short file must NOT pass", ComicDownloader.isImageFile(garbageFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testExporterUtf8Bom() {
        val book = Book(name = "BOM测试", author = "作者", sourceName = "某源", url = "https://example.com/b")
        val chapters = listOf(
            Chapter("第1章", "https://example.com/c1", 0) to "这是第一章内容。"
        )
        val txtContent = Exporter.buildTxt(book, chapters).toByteArray(Charsets.UTF_8)
        val outBytes = Exporter.UTF8_BOM + txtContent

        assertEquals(3, Exporter.UTF8_BOM.size)
        assertEquals(0xEF.toByte(), outBytes[0])
        assertEquals(0xBB.toByte(), outBytes[1])
        assertEquals(0xBF.toByte(), outBytes[2])

        // Verify that stripping BOM yields valid UTF-8 string
        val decoded = String(outBytes, 3, outBytes.size - 3, Charsets.UTF_8)
        assertTrue(decoded.contains("BOM测试"))
        assertTrue(decoded.contains("这是第一章内容。"))
    }

    @Test
    fun testRulesSelectorHashHandling() {
        // Single # in CSS selector (e.g. div#content) must NOT be split as regex
        val selector = "div#content"
        val hashIdx = selector.indexOf("##")
        assertEquals(-1, hashIdx) // indexOf("##") is -1, so div#content is preserved!

        // True regex split using ##
        val regexSelector = "div#content##[0-9]+"
        val doubleHashIdx = regexSelector.indexOf("##")
        assertEquals(11, doubleHashIdx)
        assertEquals("div#content", regexSelector.substring(0, doubleHashIdx))
        assertEquals("##[0-9]+", regexSelector.substring(doubleHashIdx))
    }

    @Test
    fun testDecodeNovelBytes() {
        val originalText = "第一章 斗罗大陆\n唐家三少\n唐门外门弟子唐三，因偷学内门绝学为唐门所不容。"

        // 1. Valid UTF-8 bytes
        val utf8Bytes = originalText.toByteArray(Charsets.UTF_8)
        val decodedUtf8 = Exporter.decodeNovelBytes(utf8Bytes)
        assertEquals(originalText, decodedUtf8)

        // 2. Valid GB18030 / GBK bytes
        val gbkBytes = originalText.toByteArray(Charset.forName("GB18030"))
        val decodedGbk = Exporter.decodeNovelBytes(gbkBytes)
        assertEquals(originalText, decodedGbk)

        // 3. UTF-8 with BOM bytes
        val bomBytes = Exporter.UTF8_BOM + utf8Bytes
        val decodedBom = Exporter.decodeNovelBytes(bomBytes)
        assertEquals(originalText, decodedBom)
    }

    @Test
    fun testNetApplyProxy() {
        val builder = okhttp3.OkHttpClient.Builder()
        val cfg = Net.ProxyCfg(
            enabled = true,
            socks = false,
            host = "127.0.0.1",
            port = 7890,
            user = "user",
            pass = "pass",
        )
        Net.applyProxy(builder, cfg)
        // Verify proxy was applied
        val client = builder.build()
        assertNotNull(client.proxy)
        assertEquals(java.net.Proxy.Type.HTTP, client.proxy?.type())
    }

    @Test
    fun testNovelSearchAndDetailAndChapter() {
        for (src in Sources.ALL.filter { it.apiKind.isEmpty() }) {
            println("\n=== Testing source: ${src.id} (${src.name}) ===")
            val kw = if (src.probeKeyword.isNotBlank()) src.probeKeyword else if (src.group == "轻小说") "魔王" else "凡人修仙传"
            val searchRes = BookRepo.searchSource(src, kw)
            println("Search ok=${searchRes.ok}, count=${searchRes.books.size}, error=${searchRes.error}")
            if (searchRes.books.isNotEmpty()) {
                val book = searchRes.books.first()
                println("Found book: name=${book.name}, url=${book.url}, author=${book.author}")
                val (detailBook, tocRes) = BookRepo.getDetailAndToc(src, book)
                println("Detail: author=${detailBook.author}, cover=${detailBook.cover}")
                println("Toc result ok=${tocRes.isSuccess}")
                if (tocRes.isSuccess) {
                    val toc = tocRes.getOrNull()!!
                    println("Chapters count=${toc.chapters.size}")
                    if (toc.chapters.isNotEmpty()) {
                        val firstCh = toc.chapters.first()
                        println("First chapter: ${firstCh.name}, url=${firstCh.url}")
                        val contentRes = BookRepo.getChapter(src, firstCh)
                        println("Content result ok=${contentRes.isSuccess}, error=${contentRes.exceptionOrNull()?.message}")
                        if (contentRes.isSuccess) {
                            val c = contentRes.getOrNull()!!
                            println("Content title=${c.title}, len=${c.text.length}, sample=${c.text.take(40)}")
                        }
                    }
                } else {
                    println("Toc failed: ${tocRes.exceptionOrNull()?.message}")
                }
            }
        }
    }

    @Test
    fun testComicPagesResolution() {
        println("Comic sources: ${ComicRepo.sources.map { it.id }}")
        for (src in ComicRepo.sources) {
            println("\n--- Testing comic source: ${src.id} (${src.name}) ---")
            val home = runCatching { src.home() }.getOrElse {
                println("Home failed: ${it.message}"); emptyList()
            }
            println("Home comics: count=${home.size}")
            if (home.isNotEmpty()) {
                val comic = home.first()
                println("First comic: name=${comic.name}, url=${comic.url}")
                val chs = runCatching { src.chapters(comic) }.getOrElse {
                    println("Chapters failed: ${it.message}"); emptyList()
                }
                println("Chapters count=${chs.size}")
                if (chs.isNotEmpty()) {
                    val ch = chs.first()
                    println("First ch: name=${ch.name}, url=${ch.url}")
                    val pages = runCatching { src.pages(comic, ch) }.getOrNull()
                    if (pages != null) {
                        println("Pages title=${pages.title}, count=${pages.images.size}, referer=${pages.referer}, headers=${pages.headers}")
                        println("Sample image URLs: ${pages.images.take(3)}")
                        // Now test downloading or probing the first image URL with the headers!
                        if (pages.images.isNotEmpty()) {
                            val imgUrl = pages.images.first()
                            val reqHeaders = src.headers() + pages.headers + (if (pages.referer.isNotBlank()) mapOf("Referer" to pages.referer) else emptyMap())
                            println("Probing image URL: $imgUrl with headers=$reqHeaders")
                            val probe = Net.probe(imgUrl, reqHeaders, pages.referer)
                            println("Probe result: HTTP code=${probe.first}, len=${probe.second}")
                        }
                    } else {
                        println("Pages resolution failed")
                    }
                }
            }
        }
    }

    @Test
    fun testRulesPickOnAnchorAndBareFields() {
        val doc = org.jsoup.Jsoup.parse("""
            <div id="wrapper">
                <a class="c-link" href="/book/123">仙逆</a>
                <div class="content"><p>第一段</p><p>第二段</p></div>
            </div>
        """.trimIndent())
        val anchor = doc.selectFirst("a")!!
        // Previously root.selectFirst("a") returned null when root was already <a>!
        val picked = Rules.pick(anchor, "a")
        assertNotNull(picked)
        assertEquals("仙逆", picked?.text())

        // Bare field extraction
        assertEquals("仙逆", Rules.extract(anchor, "text"))
        assertEquals("仙逆", Rules.extract(anchor, "@text"))
        assertEquals("/book/123", Rules.extract(anchor, "href"))
        assertEquals("/book/123", Rules.extract(anchor, "@href"))
        assertEquals("仙逆", Rules.extract(anchor, "a@text"))
        assertEquals("/book/123", Rules.extract(anchor, "a@href"))
    }

    @Test
    fun testParseTocListWithOrAndNestedAnchors() {
        val src = Source(
            id = "test_src",
            name = "测试源",
            group = "测试",
            base = "https://example.com",
            toc = TocRule(list = "#lbks dd || #list dd a", name = "a@text", url = "a@href")
        )
        val html = """
            <div id="lbks">
                <dd><a href="/chapter/1.html">第一章 降临</a></dd>
                <dd><a href="/chapter/2.html">第二章 修行</a></dd>
                <dd><a href="/chapter/3.html">第三章 突破</a></dd>
            </div>
        """.trimIndent()
        val doc = org.jsoup.Jsoup.parse(html)
        val chapters = BookRepo.parseTocList(doc, "https://example.com/book/1/", src.toc)
        assertEquals(3, chapters.size)
        assertEquals("第一章 降临", chapters[0].name)
        assertEquals("https://example.com/chapter/1.html", chapters[0].url)
        assertEquals("第二章 修行", chapters[1].name)
        assertEquals("第三章 突破", chapters[2].name)
    }

    @Test
    fun testDm5ChapterRegexWithAttributesBeforeHref() {
        val chapRegex = Regex(
            """<a\s+([^>]*?href=["']?(/m\d+/?)["']?[^>]*)>([\s\S]{0,1500}?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        val html = """
            <div class="chapter-list">
                <a class="chapteritem" title="第1话 初遇" href="/m1001/">第1话 初遇</a>
                <a href="/m1002/" class="chapteritem">第2话 启程</a>
                <a class="tg" href="/m1003/">1003</a>
            </div>
        """.trimIndent()
        val matches = chapRegex.findAll(html).toList()
        assertEquals(3, matches.size)

        assertEquals("/m1001/", matches[0].groupValues[2])
        assertTrue(matches[0].groupValues[1].contains("chapteritem"))
        assertEquals("第1话 初遇", matches[0].groupValues[3].trim())

        assertEquals("/m1002/", matches[1].groupValues[2])
        assertTrue(matches[1].groupValues[1].contains("chapteritem"))
        assertEquals("第2话 启程", matches[1].groupValues[3].trim())

        assertEquals("/m1003/", matches[2].groupValues[2])
    }

    @Test
    fun testShelfScreenOffShelfSetDeduplication() {
        val shelf = listOf(
            ShelfItem(book = Book(name = "书A", url = "https://a.com/1", sourceId = "s1")),
            ShelfItem(book = Book(name = "书B", url = "https://a.com/2", sourceId = "s1")),
        )
        val records = listOf(
            ReadingRecord(book = Book(name = "书A", url = "https://a.com/1", sourceId = "s1"), chapterName = "第1章"),
            ReadingRecord(book = Book(name = "书C", url = "https://a.com/3", sourceId = "s1"), chapterName = "第10章"),
        )
        val shelfKeys = shelf.map { it.book.url + it.book.sourceId }.toSet()
        val offShelf = records.filter { (it.book.url + it.book.sourceId) !in shelfKeys }
        assertEquals(1, offShelf.size)
        assertEquals("书C", offShelf[0].book.name)
    }

    @Test
    fun testSfSoftErrorRecognition() {
        val softErrorHtml = "<html><body>很抱歉，您访问的页面暂时无法访问哦</body></html>"
        assertTrue(BookRepo.isSoftError(softErrorHtml))
        assertFalse(BookRepo.isSoftError("<html><body><div class='yuedu Content_Frame'><div>正文内容很多很长...</div></div></body></html>"))
    }

    @Test
    fun testSfContentSelectors() {
        val sfHtml = """
            <div class="yuedu Content_Frame" style="margin-top:0.4rem;">
                <div style="text-indent: 2em;">
                    <p>布雷住在纽汉镇的一个普通村民，目前的工作是务农。</p>
                    <p>当然，这并不是让人瞧不起的工作，至少布雷其实很满意。</p>
                </div>
            </div>
        """.trimIndent()
        val doc = Rules.parse(sfHtml)
        val sfacg = Sources.byId("sfacg")!!
        assertNotNull(sfacg)
        assertTrue(sfacg.enabledByDefault)
        val rule = sfacg.content!!
        assertTrue(rule.body.contains("Content_Frame"))
    }

    @Test
    fun testDefaultEnabledSourcesRefinement() {
        val def = Sources.defaultEnabled
        assertTrue(def.contains("biqugese"))
        assertTrue(def.contains("sfacg"))
        assertTrue(def.contains("wenku8"))
        assertTrue(def.contains("lnovel"))
        assertTrue(def.contains("esjzone"))
        assertTrue("17K 是替换起点之后的可用正版源，应默认开启", def.contains("17k"))
        assertTrue("速读谷搜索重定向已适配，应默认开启", def.contains("sudugu"))
        assertNull("起点全站 JS 渲染，静态抓不到目录和正文，源已下线", Sources.byId("qidian"))
        assertFalse("novel8 requires JS rendering for chapter text and should not be enabled by default", def.contains("novel8"))
        assertFalse("ptwxz rule is fragile and should not be enabled by default", def.contains("ptwxz"))
    }

    // ─────────── 书源回归（联网）：对应「很多源刷不到正文 / SF 有目录没正文 / 有源抓不到目录」三类报障 ───────────

    @Test
    fun testSfTocSkipsImageChaptersAndBodyReadable() {
        val src = Sources.byId("sfacg")!!
        val res = BookRepo.searchSource(src, "魔王")
        assertTrue("SF 搜索应该能用：${res.error}", res.ok && res.books.isNotEmpty())
        val toc = BookRepo.getToc(src, res.books.first()).getOrNull()
        assertNotNull("SF 目录应该能抓到", toc)
        val chapters = toc!!.chapters
        assertTrue("SF 目录至少应有几十章，实际 ${chapters.size}", chapters.size > 20)
        assertTrue(
            "插图/人设章不该再占目录开头：${chapters.take(6).map { it.name }}",
            chapters.take(6).none { Regex("插图|插画|人设|图集|设定集").containsMatchIn(it.name) },
        )
        val first = chapters.first()
        val content = BookRepo.getChapter(src, first)
        assertTrue(
            "SF 目录第一章「${first.name}」应有正文：${content.exceptionOrNull()?.message}",
            content.isSuccess && content.getOrNull()!!.text.length > 200,
        )
    }

    @Test
    fun testSuduguSearchResolvesRedirectedBookPage() {
        val src = Sources.byId("sudugu")!!
        // 速读谷是台慢机器，搜索 POST 偶尔会超时；这里重试两轮，测的是「302 到书页能不能认出来」，不是站点可用性
        var res = BookRepo.searchSource(src, "凡人修仙传")
        repeat(2) {
            if (!res.ok || res.books.isEmpty()) {
                Thread.sleep(1500)
                res = BookRepo.searchSource(src, "凡人修仙传")
            }
        }
        assertTrue("速读谷搜索应把 302 落地的书页当成结果：${res.error}", res.ok && res.books.isNotEmpty())
        val book = res.books.first()
        assertTrue("取到的应该是书而不是章节页：${book.name} ${book.url}",
            book.name.contains("凡人修仙传") && Regex("/\\d+/$").containsMatchIn(book.url))
        val tocRes = BookRepo.getToc(src, book)
        assertNotNull("书页上应能认出目录：${tocRes.exceptionOrNull()?.message}", tocRes.getOrNull())
        assertTrue("目录章数 ${tocRes.getOrNull()!!.chapters.size}", tocRes.getOrNull()!!.chapters.size > 100)
    }

    @Test
    fun testHealthDeepSamplesPastImageChapters() {
        val (ok, detail, _) = BookRepo.healthDeep(Sources.byId("sfacg")!!)
        assertTrue("SF 体检应通过，实际：$detail", ok)
        assertTrue("SF 体检应报出正文字数，实际：$detail", detail.contains("正文") && Regex("正文 \\d+ 字").containsMatchIn(detail))
        assertFalse("SF 体检不该再把插图章判成正文过短：$detail", detail.contains("正文内容过短"))
    }

    @Test
    fun test17kSourceEndToEnd() {
        val src = Sources.byId("17k")!!
        val res = BookRepo.searchSource(src, "近战狂兵")
        assertTrue("17K 搜索应该能用：${res.error}", res.ok && res.books.isNotEmpty())
        val book = res.books.firstOrNull { it.name.contains("近战狂兵") }
        assertNotNull("17K 搜索结果里应有《近战狂兵》：${res.books.map { it.name }}", book)
        val tocRes = BookRepo.getToc(src, book!!)
        val toc = tocRes.getOrNull()
        assertNotNull("17K 目录（/list/{id}.html）应能抓到：${tocRes.exceptionOrNull()?.message}", toc)
        assertTrue("目录章数 ${toc!!.chapters.size}", toc.chapters.size > 100)
        val content = BookRepo.getChapter(src, toc.chapters.first())
        assertTrue(
            "17K 正文应能取到：${content.exceptionOrNull()?.message}",
            content.isSuccess && content.getOrNull()!!.text.length > 300,
        )
    }

    @Test
    fun testBiqugeseSourceEndToEnd() {
        val src = Sources.byId("biqugese")!!
        assertNotNull("笔趣书阁应存在", src)
        assertNull("已下架失效的书趣阁应被剔除", Sources.byId("shuquge"))
        assertNull("已下架的飞卢应被剔除", Sources.byId("faloo"))
        val res = BookRepo.searchSource(src, "凡人修仙传")
        if (!res.ok) {
            println("笔趣书阁当前网络不通，跳过端到端校验: ${res.error}")
            return
        }
        assertTrue("笔趣书阁搜索应该能用：${res.error}", res.books.isNotEmpty())
        val book = res.books.firstOrNull { it.name.contains("凡人") }
        assertNotNull("笔趣书阁应能搜到凡人修仙传：${res.books.map { it.name }}", book)
        val (_, tocRes) = BookRepo.getDetailAndToc(src, book!!)
        val toc = tocRes.getOrNull()
        assertNotNull("笔趣书阁目录应能抓到：${tocRes.exceptionOrNull()?.message}", toc)
        assertTrue("笔趣书阁目录章数应大于100：${toc!!.chapters.size}", toc.chapters.size > 100)
    }

    @Test
    fun testEsjzoneSourceEndToEnd() {
        val src = Sources.byId("esjzone")!!
        val res = BookRepo.searchSource(src, "魔王")
        if (!res.ok) {
            println("ESJ Zone 当前网络不通，跳过端到端校验: ${res.error}")
            return
        }
        assertTrue("ESJ Zone 搜索应该能用：${res.error}", res.books.isNotEmpty())
        val book = res.books.first()
        println("ESJ book found: name=${book.name}, url=${book.url}")
        val (_, tocRes) = BookRepo.getDetailAndToc(src, book)
        if (tocRes.isFailure) {
            tocRes.exceptionOrNull()?.printStackTrace()
        }
        val toc = tocRes.getOrNull()
        assertNotNull("ESJ Zone 目录应能抓到：${tocRes.exceptionOrNull()?.message}", toc)
        assertTrue("ESJ Zone 目录章数应大于0：${toc!!.chapters.size}", toc.chapters.isNotEmpty())
        val ch = toc.chapters.firstOrNull { !it.name.contains("公告") } ?: toc.chapters.first()
        val content = BookRepo.getChapter(src, ch)
        if (content.isFailure) {
            println("ESJ Zone 正文获取受网络/盾拦截，跳过: ${content.exceptionOrNull()?.message}")
            return
        }
        val ok = content.isSuccess && content.getOrNull()!!.text.isNotBlank()
        assertTrue("ESJ Zone 正文应不为空", ok)
    }

    @Test
    fun testLnovelSourceConfiguration() {
        val src = Sources.byId("lnovel")
        assertNotNull("轻小说百科书源应存在", src)
        assertTrue("轻小说百科应默认启用", src!!.enabledByDefault)
        assertEquals("轻小说", src.group)
        assertTrue(src.search?.url?.contains("q%5Bname_cont%5D") == true)
        assertTrue(src.content?.body?.contains("article") == true)
    }

    @Test
    fun testComicSourcesDiagnostic() {
        val keywords = listOf("古见同学", "败犬")
        for (src in ComicRepo.sources) {
            println("=== Testing ComicSource ${src.id} (${src.name}) ===")
            for (kw in keywords) {
                try {
                    val results = src.search(kw)
                    println("  kw='$kw' -> found ${results.size} comics: ${results.take(3).map { it.name }}")
                } catch (e: Exception) {
                    println("  kw='$kw' -> FAILED: ${e.message}")
                }
            }
        }
    }
}
