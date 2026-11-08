package com.storm.shijuan

import com.storm.shijuan.data.CustomSources
import com.storm.shijuan.data.Rules
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class JsonBookSourcesImportTest {

    @Test
    fun testParseAllSixSampleFiles() {
        val desktopPath = "C:\\Users\\Administrator\\Desktop"
        val sampleFiles = listOf(
            "bookSource_笔趣阁.json",
            "bookSource_欲晚书屋.json",
            "shareBookSource(1).json",
            "bookSource_可乐小说.json",
            "bookSource_uu书库.json",
            "rssSource_苹果MCS·影视小说图片聚合.json"
        )

        for (fileName in sampleFiles) {
            val file = File(desktopPath, fileName)
            if (!file.exists()) continue
            val jsonText = file.readText(Charsets.UTF_8)
            val sources = CustomSources.parseAll(jsonText)
            assertTrue("文件 $fileName 解析出的书源列表不应为空", sources.isNotEmpty())
            val s = sources.first()
            assertTrue("书源名称不应为空: $fileName", s.name.isNotBlank())
            assertTrue("书源网址不应为空: $fileName", s.base.isNotBlank())
            assertNotNull("应具备搜索规则: $fileName", s.search)
            assertNotNull("应具备目录规则: $fileName", s.toc)
            assertNotNull("应具备正文规则: $fileName", s.content)
        }
    }

    @Test
    fun testBiqugeOnclickAndRegexExtraction() {
        val html = """
            <div class="book-info">
                <h4><a href="/book/100/">败犬女主</a></h4>
                <div class="author">作者：雨森たきび</div>
            </div>
            <ul class="chapter-list">
                <li><a onclick="location.href='/book/100/1.html';">第一章 序幕</a></li>
                <li><a onclick="location.href='/book/100/2.html';">第二章 惨败</a></li>
            </ul>
            <div id="chaptercontent">
                <p>提示您：看后求收藏，这是正文内容第一段。</p>
                <p>这是第二段正文内容。</p>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html)

        // 1. 测试作者过滤
        val authorRule = ".book-info .author@text##作者：##"
        val author = Rules.extract(doc, authorRule)
        assertEquals("雨森たきび", author)

        // 2. 测试 onclick 正则反向引用提取
        val tocRule = "a@onclick##.*?location\\.href\\s*=\\s*'([^']+)'.*##$1##"
        val chapterEl = doc.selectFirst(".chapter-list li a")!!
        val chapterUrl = Rules.extract(chapterEl, tocRule, baseUrl = "https://m.piquge.com")
        assertEquals("https://m.piquge.com/book/100/1.html", chapterUrl)
    }

    @Test
    fun testContentRuleJsStrip() {
        // 欲晚书屋格式: @css:#rtext@html@js:var t=...
        val yuwanRule = CustomSources.readCss("@css:#rtext@html@js:var t=(typeof title!=='undefined'&&title)?String(title):'';")
        assertEquals("#rtext@html", yuwanRule)

        // 西瓜书屋格式: #C0NTENT@html||all\n<js>\n...
        val xiguaRule = CustomSources.readCss("#C0NTENT@html||all\n<js>\nif (baseUrl.includes('_2')) { ... }")
        assertEquals("#C0NTENT@html", xiguaRule)
    }
}
