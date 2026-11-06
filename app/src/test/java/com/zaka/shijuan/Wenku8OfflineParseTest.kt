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
        assertTrue("作者应提取成功: ${first.author}", first.author.contains("白石新"))
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
            <p>请记住本站网址 www.wenku8.cc 最新网址</p>
            <div>
                <p>这是第二段正文内容，战斗十分激烈。</p>
                <span>手机用户请浏览 m.wenku8.com 阅读</span>
            </div>
        """.trimIndent()

        val cleaned = Rules.stripAds(html)
        assertFalse("包含网址广告的段落应被彻底移除", cleaned.contains("www.wenku8.cc"))
        assertFalse("包含移动端广告的 span 应被移除", cleaned.contains("m.wenku8.com"))
        assertTrue("正常正文第一段必须保留", cleaned.contains("这是正文的第一段内容"))
        assertTrue("正常正文第二段必须保留", cleaned.contains("这是第二段正文内容"))
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
