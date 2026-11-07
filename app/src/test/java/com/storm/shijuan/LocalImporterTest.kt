package com.storm.shijuan

import com.storm.shijuan.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalImporterTest {

    @Test
    fun testTxtChapterRegexMatching() {
        val chapterRegex = Regex("""(?m)^[ \t]*(第[0-9一二三四五六七八九十百千万]+[章回节卷集幕篇部][^\r\n]{0,35}|Chapter\s*\d+[^\r\n]{0,35}|(?:引子|序言|序章|楔子|尾声|后记|番外)[^\r\n]{0,35})[ \t]*$""")

        val sampleText = """
            序章 命运的起点
            这里是序章内容，讲述主角初入异世界。
            
            第一章 新的旅途
            主角开始踏上征程。
            
            第2章 遭遇劲敌
            激烈交战。
            
            Chapter 3 The Revelation
            秘密被揭开。
            
            后记 写在完结之后
            作者写在最后的话。
        """.trimIndent()

        val matches = chapterRegex.findAll(sampleText).map { it.value.trim() }.toList()
        assertEquals(5, matches.size)
        assertEquals("序章 命运的起点", matches[0])
        assertEquals("第一章 新的旅途", matches[1])
        assertEquals("第2章 遭遇劲敌", matches[2])
        assertEquals("Chapter 3 The Revelation", matches[3])
        assertEquals("后记 写在完结之后", matches[4])
    }

    @Test
    fun testLocalComicSourceManifestParsing() {
        val tempDir = File.createTempFile("test_comic_", "_dir").apply {
            delete()
            mkdirs()
        }
        try {
            val manifest = JSONObject().apply {
                put("name", "测试漫画")
                put("cover", "file://${tempDir.absolutePath}/cover.jpg")
                put("chapters", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "第 1 话 启程")
                        put("url", "ch_0")
                        put("images", JSONArray().apply {
                            put("file://${tempDir.absolutePath}/page1.jpg")
                            put("file://${tempDir.absolutePath}/page2.jpg")
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "第 2 话 激战")
                        put("url", "ch_1")
                        put("images", JSONArray().apply {
                            put("file://${tempDir.absolutePath}/page3.jpg")
                        })
                    })
                })
            }
            File(tempDir, "manifest.json").writeText(manifest.toString())

            val source = LocalComicSource()
            assertEquals("local", source.id)
            assertEquals("本地导入", source.name)

            val comic = Comic(
                name = "测试漫画",
                url = "file://${tempDir.absolutePath}",
                sourceId = "local",
            )

            val chapters = source.chapters(comic)
            assertEquals(2, chapters.size)
            assertEquals("第 1 话 启程", chapters[0].name)
            assertEquals("ch_0", chapters[0].url)
            assertEquals("第 2 话 激战", chapters[1].name)
            assertEquals("ch_1", chapters[1].url)

            val pages0 = source.pages(comic, chapters[0])
            assertEquals(2, pages0.images.size)
            assertTrue(pages0.images[0].endsWith("page1.jpg"))
            assertTrue(pages0.images[1].endsWith("page2.jpg"))

            val pages1 = source.pages(comic, chapters[1])
            assertEquals(1, pages1.images.size)
            assertTrue(pages1.images[0].endsWith("page3.jpg"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testHtmlImageTagConversionToSliceFormat() {
        val html = """
            <html>
            <body>
            <p>第一段文本描述。</p>
            <p><img src="../images/illus_1.jpg" alt="插画1"/></p>
            <p>第二段文本继续展开。</p>
            <div><img src="illus_2.png"/></div>
            </body>
            </html>
        """.trimIndent()

        val doc = org.jsoup.Jsoup.parse(html)
        val sb = StringBuilder()
        val dummyBase = File("C:/test/OEBPS/text")
        for (node in doc.body().children()) {
            if (node.tagName() == "img" || node.selectFirst("img") != null) {
                val img = if (node.tagName() == "img") node else node.selectFirst("img")!!
                val src = img.attr("src")
                val imgTarget = File(dummyBase, src).normalize()
                sb.append("\n<!--image-->file://${imgTarget.absolutePath}<!--image-->\n")
            } else {
                val text = node.text().trim()
                if (text.isNotBlank()) sb.append(text).append("\n\n")
            }
        }
        val converted = sb.toString()
        assertTrue(converted.contains("第一段文本描述。"))
        assertTrue(converted.contains("<!--image-->file://"))
        assertTrue(converted.contains("illus_1.jpg<!--image-->"))
        assertTrue(converted.contains("第二段文本继续展开。"))
        assertTrue(converted.contains("illus_2.png<!--image-->"))
    }
}
