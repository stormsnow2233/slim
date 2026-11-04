package com.zaka.shijuan

import com.zaka.shijuan.data.Book
import com.zaka.shijuan.data.Chapter
import com.zaka.shijuan.data.Exporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubTest {

    private fun sample(n: Int): List<Pair<Chapter, String>> =
        (1..n).map { i ->
            Chapter("第${i}章 测试 & <标题>", "https://x/$i", i) to
                "这是第 $i 章的正文。\n第二段内容，含特殊字符 <tag> & \"引号\"。\n第三段。"
        }

    @Test
    fun epubStructureIsValid() {
        val book = Book(name = "测试书", author = "作者", sourceName = "某源", url = "https://x/b")
        val bytes = Exporter.buildEpub(book, sample(3))

        val entries = LinkedHashMap<String, ByteArray>()
        val methods = HashMap<String, Int>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e: ZipEntry? = zis.nextEntry
            while (e != null) {
                entries[e.name] = zis.readBytes()
                methods[e.name] = e.method
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        // 第一个条目必须是 mimetype 且不压缩
        assertEquals("mimetype", entries.keys.first())
        assertEquals(ZipEntry.STORED, methods["mimetype"])
        assertEquals("application/epub+zip", String(entries["mimetype"]!!, Charsets.US_ASCII))

        assertTrue(entries.containsKey("META-INF/container.xml"))
        assertTrue(entries.containsKey("OEBPS/content.opf"))
        assertTrue(entries.containsKey("OEBPS/nav.xhtml"))
        assertTrue(entries.containsKey("OEBPS/toc.ncx"))
        assertTrue(entries.containsKey("OEBPS/style.css"))
        for (i in 1..3) assertTrue("缺章节 c$i", entries.containsKey("OEBPS/text/c$i.xhtml"))

        val opf = String(entries["OEBPS/content.opf"]!!, Charsets.UTF_8)
        assertEquals(3, Regex("<itemref idref=").findAll(opf).count())
        assertTrue(opf.contains("toc=\"ncx\""))
        assertTrue(opf.contains("<dc:title>测试书</dc:title>"))

        // 特殊字符必须被转义，不能破坏 XML
        val c1 = String(entries["OEBPS/text/c1.xhtml"]!!, Charsets.UTF_8)
        assertTrue(c1.contains("&amp;"))
        assertTrue(c1.contains("&lt;tag&gt;"))
        assertTrue(!c1.contains("<tag>"))

        // 目录 ncx 的条目数要对得上
        val ncx = String(entries["OEBPS/toc.ncx"]!!, Charsets.UTF_8)
        assertEquals(3, Regex("<navPoint ").findAll(ncx).count())
    }

    @Test
    fun txtContainsAllChapters() {
        val book = Book(name = "测试书", author = "作者", sourceName = "某源", url = "https://x/b")
        val txt = Exporter.buildTxt(book, sample(2))
        assertTrue(txt.contains("第1章"))
        assertTrue(txt.contains("第2章"))
        assertTrue(txt.contains("测试书"))
    }
}
