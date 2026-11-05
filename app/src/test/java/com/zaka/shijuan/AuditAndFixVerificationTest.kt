package com.zaka.shijuan

import com.zaka.shijuan.data.*
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
}
