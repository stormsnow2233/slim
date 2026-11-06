package com.zaka.shijuan

import com.zaka.shijuan.data.*
import com.zaka.shijuan.ui.PageSlice
import com.zaka.shijuan.ui.TextPaginator
import org.junit.Assert.*
import org.junit.Test

class Wenku8AndReaderTest {

    @Test
    fun testWenku8SourceConfiguration() {
        val src = Sources.byId("wenku8")
        assertNotNull("轻小说文库书源应存在", src)
        assertEquals("轻小说文库编码应为 GBK", "gbk", src!!.charset.lowercase())
        assertEquals("轻小说文库应标记为 verified 已实测", "verified", src.status)
        assertTrue("应包含 wenku8.cc 镜像直连载点", src.mirrors.any { it.contains("wenku8.cc") })
        
        // TOC 规则验证
        val toc = src.toc
        assertNotNull("TOC 规则应存在", toc)
        assertTrue("TOC 列表选择器应覆盖 table.css 与 td.ccss", toc!!.list.contains("td.ccss"))
        assertEquals("卷标题选择器应为 td.vcss", "td.vcss", toc.volume)
        
        // 验证正文选择器
        val content = src.content
        assertNotNull("正文规则应存在", content)
        assertTrue("正文规则应包含 div#content", content!!.body.contains("content"))
    }

    @Test
    fun testHtmlToParagraphsExtractsImages() {
        val html = """
            <p>第一段文本介绍主角。</p>
            <p><img src="http://pic.wenku8.cn/pictures/1/1973/69759/84737.jpeg" alt="插图1" /></p>
            <div>第二段战斗场景描述。</div>
            <!--image-->http://pic.wenku8.cn/pictures/1/1973/69759/84738.jpeg<!--image-->
            <p>第三段尾声。</p>
        """.trimIndent()

        val text = Rules.htmlToParagraphs(html)
        assertTrue("正文应提取出第一张插图", text.contains("<!--image-->http://pic.wenku8.cn/pictures/1/1973/69759/84737.jpeg<!--image-->"))
        assertTrue("正文应保留第二张注释插图", text.contains("<!--image-->http://pic.wenku8.cn/pictures/1/1973/69759/84738.jpeg<!--image-->"))
        assertTrue("文字段落应保留", text.contains("第一段文本介绍主角。") && text.contains("第三段尾声。"))
    }

    @Test
    fun testHtmlToParagraphsResolvesRelativeImageUrls() {
        val html = """
            <p>测试相对路径图片：</p>
            <img src="/novel/1/1973/69759/01.jpg" />
        """.trimIndent()

        val text = Rules.htmlToParagraphs(html, baseUrl = "http://www.wenku8.cc/novel/1/1973/69759.htm")
        assertTrue("相对图片路径应自动补全为绝对 HTTP 地址", text.contains("<!--image-->http://www.wenku8.cc/novel/1/1973/69759/01.jpg<!--image-->"))
    }

    @Test
    fun testTextPaginatorNormalizePreservesImages() {
        val text = "第一段文字\n<!--image-->http://img.test/1.jpg<!--image-->\n第二段文字"
        val normalizedWithIndent = TextPaginator.normalize(text, indent = true)
        val lines = normalizedWithIndent.split("\n")

        assertEquals(3, lines.size)
        assertTrue("文字段落应添加全角双空格缩进", lines[0].startsWith("\u3000\u3000第一段文字"))
        assertEquals("插图行不应被添加文字缩进", "<!--image-->http://img.test/1.jpg<!--image-->", lines[1])
        assertTrue("第二段文字段落应添加全角双空格缩进", lines[2].startsWith("\u3000\u3000第二段文字"))
    }

    @Test
    fun testChapterVolumeField() {
        val ch1 = Chapter(name = "序章", url = "/c/1.htm", index = 0, volume = "第一卷 告白于苍刻之夜")
        assertEquals("第一卷 告白于苍刻之夜", ch1.volume)
        assertEquals("序章", ch1.name)

        // 默认参数向后兼容
        val chLegacy = Chapter(name = "旧章节", url = "/c/legacy.htm", index = 1)
        assertEquals("", chLegacy.volume)
    }

    @Test
    fun testExporterDirectTxtUrl() {
        val b1 = Book(name = "实教", author = "衣笠", url = "http://www.wenku8.cc/book/1973.htm", sourceId = "wenku8", sourceName = "轻小说文库")
        val b2 = Book(name = "绝对双刃", author = "柊巧", url = "https://www.wenku8.net/novel/1/1305/index.htm", sourceId = "wenku8", sourceName = "轻小说文库")

        val m = Exporter::class.java.getDeclaredMethod("directTxtUrl", Book::class.java)
        m.isAccessible = true
        val u1 = m.invoke(Exporter, b1) as? String
        val u2 = m.invoke(Exporter, b2) as? String

        assertEquals("https://dl.wenku8.com/down.php?type=utf8&node=1&id=1973", u1)
        assertEquals("https://dl.wenku8.com/down.php?type=utf8&node=1&id=1305", u2)
    }

    @Test
    fun testFilterFakeBooks() {
        val m = BookRepo::class.java.getDeclaredMethod("isFakeBook", String::class.java, String::class.java)
        m.isAccessible = true

        val isFake1 = m.invoke(BookRepo, "新用户注册", "https://www.wenku8.cc/register.php") as Boolean
        val isFake2 = m.invoke(BookRepo, "用户登录", "https://www.wenku8.cc/login.php") as Boolean
        val isFake3 = m.invoke(BookRepo, "取回密码", "https://www.wenku8.cc/getpass.php") as Boolean
        val isReal = m.invoke(BookRepo, "刀剑神域", "https://www.wenku8.cc/book/1.htm") as Boolean

        assertTrue("新用户注册链接应被判定为假书", isFake1)
        assertTrue("用户登录链接应被判定为假书", isFake2)
        assertTrue("取回密码链接应被判定为假书", isFake3)
        assertFalse("真实小说不应被判定为假书", isReal)
    }

    @Test
    fun testTranscodeAndPiracyErrorDetection() {
        val piracyError1 = "转码失败！请您使用右上换源切换源站阅读 或者直接前往源网站进行阅读！ 热门推荐：我名黄天"
        val piracyError2 = "<html><body><h1>Welcome</h1></body></html>"
        val normalText = "在风水极好的不知名小山上，坐落着一座破败的道观。"

        assertTrue("转码失败及盗版伪正文应被准确识别", BookRepo.isTranscodeOrPiracyError(piracyError1))
        assertTrue("转码失败应属于软错误页", BookRepo.isSoftError(piracyError1))
        assertTrue("Welcome 反爬拦截应被准确识别", BookRepo.isTranscodeOrPiracyError(piracyError2))
        assertTrue("Welcome 应属于软错误页", BookRepo.isSoftError(piracyError2))
        assertFalse("正常正文不应被识别为错误", BookRepo.isTranscodeOrPiracyError(normalText))
        assertFalse("正常正文不应被识别为软错误", BookRepo.isSoftError(normalText))
    }
}
