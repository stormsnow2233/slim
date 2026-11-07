package com.zaka.shijuan

import com.zaka.shijuan.data.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Wenku8OfflineParseTest {

    private fun loadSample(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("samples/wenku8/$name")
        if (stream != null) {
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        val file = File("src/test/resources/samples/wenku8/$name")
        if (file.exists()) return file.readText(Charsets.UTF_8)
        val fileRoot = File("app/src/test/resources/samples/wenku8/$name")
        if (fileRoot.exists()) return fileRoot.readText(Charsets.UTF_8)
        throw IllegalArgumentException("Sample not found: $name")
    }

    @Test
    fun testOfflineSearchCardParsing() {
        val html = loadSample("search_sample.html")
        val src = Sources.byId("wenku8")!!
        val rule = src.search!!

        // 调用私有 parseSearchPage
        val m = BookRepo::class.java.getDeclaredMethod(
            "parseSearchPage",
            Source::class.java, String::class.java, String::class.java, SearchRule::class.java, String::class.java
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val books = m.invoke(BookRepo, src, html, "https://www.wenku8.cc/modules/article/articlelist.php", rule, "") as List<Book>

        assertTrue("应成功解析出 20 本书", books.size >= 20)
        val first = books.first()
        assertTrue("书名应包含落第贤者: ${first.name}", first.name.contains("落第贤者"))
        assertEquals("书本详情地址应为 /book/3095.htm", "https://www.wenku8.cc/book/3095.htm", first.url)
        assertTrue("封面地址应匹配 img.wenku8", first.cover.contains("img.wenku8.com/image/3/3095/3095s.jpg"))
        assertTrue("封面地址应自动升级为 https", first.cover.startsWith("https://"))
        assertTrue("作者应提取成功: ${first.author}", first.author.contains("白石新"))
    }

    @Test
    fun testWenku8RegexSearchFallbackAndDeduplication() {
        val snippet = """
            <html><body>
                <div class="result">
                    <a href="/book/3497.htm" title="BLADE＆BASTARD">BLADE＆BASTARD</a>
                    <a href="/book/3497.htm">我要阅读</a>
                    <a href="/book/1305.htm">绝对双刃</a>
                    <a href="/book/1305.htm">放入书架</a>
                </div>
            </body></html>
        """.trimIndent()
        val src = Sources.byId("wenku8")!!
        val emptyRule = SearchRule(url = "", list = "", fields = emptyMap())

        val m = BookRepo::class.java.getDeclaredMethod(
            "parseSearchPage",
            Source::class.java, String::class.java, String::class.java, SearchRule::class.java, String::class.java
        )
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val books = m.invoke(BookRepo, src, snippet, "https://www.wenku8.cc/modules/article/search.php", emptyRule, "") as List<Book>

        assertEquals("应通过正则兜底提取出 2 本书", 2, books.size)
        val b1 = books[0]
        assertEquals("BLADE＆BASTARD", b1.name)
        assertEquals("https://www.wenku8.cc/book/3497.htm", b1.url)
        assertEquals("https://img.wenku8.com/image/3/3497/3497s.jpg", b1.cover)

        val b2 = books[1]
        assertEquals("绝对双刃", b2.name)
        assertEquals("https://www.wenku8.cc/book/1305.htm", b2.url)
        assertEquals("https://img.wenku8.com/image/1/1305/1305s.jpg", b2.cover)
    }

    @Test
    fun testUpgradeWenku8ImageProtocol() {
        assertEquals(
            "https://img.wenku8.com/image/3/3095/3095s.jpg",
            Rules.upgradeWenku8Image("http://img.wenku8.com/image/3/3095/3095s.jpg")
        )
        assertEquals(
            "https://pic.777743.xyz/pictures/1/1973/69567/01.jpg",
            Rules.upgradeWenku8Image("http://pic.777743.xyz/pictures/1/1973/69567/01.jpg")
        )
        assertEquals(
            "https://image.wenku8.cc/image/0/12/12s.jpg",
            Rules.upgradeWenku8Image("http://image.wenku8.cc/image/0/12/12s.jpg")
        )
        // 非文库域名保持原样
        assertEquals(
            "http://other.com/cover.jpg",
            Rules.upgradeWenku8Image("http://other.com/cover.jpg")
        )
    }

    @Test
    fun testWenku8SpecificTakedownPhrase() {
        val html = """
            <html>
            <head><title>轻小说文库</title></head>
            <body>
                <div id="content">
                    因版权问题，文库不再提供该小说的阅读！
                </div>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        assertTrue("因版权问题提示应被准确判定为下架", BookRepo.isTakenDownPage(doc, "因版权问题，文库不再提供该小说的阅读！"))
    }

    @Test
    fun testOfflineTocParsing() {
        val html = loadSample("toc_sample.html")
        val src = Sources.byId("wenku8")!!
        val doc = Jsoup.parse(html)
        val chapters = BookRepo.parseTocList(doc, "https://www.wenku8.cc/novel/1/1973/index.htm", src.toc)

        assertEquals("章节总数应为 517 章", 517, chapters.size)
        val first = chapters.first()
        assertEquals("第一章卷名应为第一卷", "第一卷", first.volume)
        assertEquals("第一章章节名应为 KEYWORDS", "KEYWORDS", first.name)
        assertEquals("第一章绝对地址应正确", "https://www.wenku8.cc/novel/1/1973/69567.htm", first.url)

        val last = chapters.last()
        assertTrue("最后一章分卷应包含第二十八卷", last.volume.contains("第二十八卷"))
    }

    @Test
    fun testOfflineBookDetailPageCatalogLinkAndFillPage() {
        val html = loadSample("book_sample.html")
        val src = Sources.byId("wenku8")!!
        val doc = Jsoup.parse(html)

        // 1. 通过选择器在书籍详情页直接提取「小说目录」链接
        val linkUrl = Rules.extract(doc, src.toc!!.link, "https://www.wenku8.cc/book/1973.htm")
        assertEquals("https://www.wenku8.cc/novel/1/1973/index.htm", linkUrl)

        // 2. 通过 {idk} 模板转换
        val mFill = BookRepo::class.java.getDeclaredMethod("fillTocPage", String::class.java, String::class.java)
        mFill.isAccessible = true
        val filled = mFill.invoke(BookRepo, src.toc!!.page, "https://www.wenku8.cc/book/1973.htm") as String
        assertEquals("/novel/1/1973/index.htm", filled)
    }

    @Test
    fun testOfflineChapterContentParsing() {
        val html = loadSample("chapter_sample.html")
        val src = Sources.byId("wenku8")!!
        val doc = Jsoup.parse(html)
        val bodyHtml = Rules.extract(doc.body(), src.content!!.body, "https://www.wenku8.cc/novel/1/1973/69567.htm")
        assertTrue("正文 HTML 应提取出 #content 内容", bodyHtml.isNotBlank())

        val text = Rules.htmlToParagraphs(bodyHtml, "https://www.wenku8.cc/novel/1/1973/69567.htm")
        assertTrue("正文文本字数应正常 (字数 > 500)", text.length > 500)
    }

    @Test
    fun testOfflineTakedownDetection() {
        val html = loadSample("takedown_sample.html")
        val doc = Jsoup.parse(html)
        val isTakedown = BookRepo.isTakenDownPage(doc, "")
        assertTrue("下架页面应被准确识别", isTakedown)
    }

    @Test
    fun testStripAdsRemovesAdParagraphs() {
        val html = """
            <p>这是正文的第一段内容，讲述主角出发的故事。</p>
            <ul id="contentdp">本文来自 轻小说文库(http://www.wenku8.com)</ul>
            <p>请记住本站网址 www.wenku8.cc 最新网址</p>
            <div>
                <p>这是第二段正文内容，战斗十分激烈。</p>
                <span>手机用户请浏览 m.wenku8.com 阅读</span>
            </div>
            <ul id="contentdp">最新最全的日本动漫轻小说 轻小说文库(http://www.wenku8.com) 为你一网打尽！</ul>
        """.trimIndent()

        val cleaned = Rules.stripAds(html)
        assertFalse("包含网址广告的段落应被彻底移除", cleaned.contains("www.wenku8.cc"))
        assertFalse("包含移动端广告的 span 应被移除", cleaned.contains("m.wenku8.com"))
        assertFalse("contentdp 广告列表必须被剔除", cleaned.contains("contentdp"))
        assertTrue("正常正文第一段必须保留", cleaned.contains("这是正文的第一段内容"))
        assertTrue("正常正文第二段必须保留", cleaned.contains("这是第二段正文内容"))
    }

    @Test
    fun testWenku8HtmlToParagraphsAdAndImageUpgrade() {
        val html = """
            <div id="content">
                <ul id="contentdp">本文来自 轻小说文库(http://www.wenku8.com)</ul>
                &nbsp;&nbsp;&nbsp;&nbsp;正文第一段，魔法实验正在进行中。<br /><br />
                <!--image-->http://img.wenku8.com/image/3/3497/123.jpg<!--image-->
                &nbsp;&nbsp;&nbsp;&nbsp;正文第二段，主角展开了行动。<br /><br />
                <ul id="contentdp">最新最全的日本动漫轻小说 轻小说文库 为你一网打尽！</ul>
            </div>
        """.trimIndent()

        val parsed = Rules.htmlToParagraphs(html, "https://www.wenku8.cc/novel/3/3497/145110.htm")
        assertFalse("正文不应包含 contentdp", parsed.contains("contentdp"))
        assertFalse("正文不应包含文库尾部广告", parsed.contains("为你一网打尽"))
        assertTrue("正文应包含第一段内容", parsed.contains("正文第一段"))
        assertTrue("正文应包含第二段内容", parsed.contains("正文第二段"))
        assertTrue("图片协议应自动升级为 https", parsed.contains("<!--image-->https://img.wenku8.com/image/3/3497/123.jpg<!--image-->"))
    }

    @Test
    fun testWenku8StatusClassification() {
        val loginSt = BookRepo.classifyStatus(false, 401, "轻小说文库搜索需在网页中登录后使用", 0)
        assertEquals("登录失效或需登录应判定为 NEEDS_LOGIN", SourceStatus.NEEDS_LOGIN, loginSt)

        val rateLimitSt = BookRepo.classifyStatus(false, 429, "源站限制两次搜索间隔不得少于 5 秒，请稍后重试", 0)
        assertEquals("5 秒限频应判定为 ACCESS_RESTRICTED", SourceStatus.ACCESS_RESTRICTED, rateLimitSt)
    }

    @Test
    fun testNetCookieJarStoresAndSendsCookies() {
        val httpUrl = "https://www.wenku8.cc/test".toHttpUrl()
        val cookie = okhttp3.Cookie.Builder()
            .domain("www.wenku8.cc")
            .path("/")
            .name("session_token")
            .value("xyz123")
            .build()
        Net.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
        val loaded = Net.cookieJar.loadForRequest(httpUrl)
        assertTrue("CookieJar 应保存并返回 session_token", loaded.any { it.name == "session_token" && it.value == "xyz123" })
    }
}
