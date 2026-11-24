package com.storm.shijuan

import com.storm.shijuan.data.Exporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class BookImportProgressTest {

    @Test
    fun testDetectCharsetUtf8AndGbk() {
        val sampleUtf8 = "测试UTF-8文本，包含中文字符与标点符号。".toByteArray(Charsets.UTF_8)
        val sampleGbk = "测试GBK文本，包含简体中文字符。".toByteArray(java.nio.charset.Charset.forName("GB18030"))

        val detectedUtf8 = Exporter.detectCharset(sampleUtf8)
        assertEquals(Charsets.UTF_8, detectedUtf8)

        val detectedGbk = Exporter.detectCharset(sampleGbk)
        assertEquals(java.nio.charset.Charset.forName("GB18030"), detectedGbk)
    }

    @Test
    fun testStreamingChapterMatchingAndProgress() {
        val rawBook = """
            序章 梦境之始
            天地混沌，盘古开天辟地。
            
            第1章 初入修真
            少年踏上了修行长路。
            
            第2章 遇阻与破境
            面对强敌，他临危突破。
            
            第3章 尾声与归途
            历尽千帆，归来仍是少年。
        """.trimIndent()

        val bytes = rawBook.toByteArray(Charsets.UTF_8)
        val charset = Exporter.detectCharset(bytes)
        val chapterLineRegex = Regex("""^[ \t]*[\[【（(]?[ \t]*(第[0-9零一二三四五六七八九十百千万两]+[章回节卷集幕篇部][^\r\n]{0,35}|Chapter\s*\d+[^\r\n]{0,35}|(?:引子|序言|序章|楔子|尾声|后记|番外)[^\r\n]{0,35})[\]】）)]?[ \t]*$""")

        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(bytes), charset))
        val matchedChapters = mutableListOf<String>()

        reader.useLines { lines ->
            for (line in lines) {
                if (chapterLineRegex.matches(line)) {
                    matchedChapters.add(line.trim())
                }
            }
        }

        assertEquals(4, matchedChapters.size)
        assertEquals("序章 梦境之始", matchedChapters[0])
        assertEquals("第1章 初入修真", matchedChapters[1])
        assertEquals("第2章 遇阻与破境", matchedChapters[2])
        assertEquals("第3章 尾声与归途", matchedChapters[3])
    }
}
