package com.storm.shijuan

import com.storm.shijuan.ui.parseInlineMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testParseBoldAndCode() {
        val input = "1. **本地多格式导入**：支持 `TXT` 与 `EPUB`"
        val res = parseInlineMarkdown(input)
        assertEquals("1. 本地多格式导入：支持  TXT  与  EPUB ", res.text)
        // Check span styles
        val styles = res.spanStyles
        assertTrue("Should have span styles for bold and code", styles.isNotEmpty())
    }

    @Test
    fun testParseLink() {
        val input = "点击 [下载地址](https://example.com/apk) 查看详情"
        val res = parseInlineMarkdown(input)
        assertEquals("点击 下载地址 查看详情", res.text)
    }

    @Test
    fun testPlainText() {
        val input = "普通纯文本内容没有任何修饰"
        val res = parseInlineMarkdown(input)
        assertEquals("普通纯文本内容没有任何修饰", res.text)
        assertTrue(res.spanStyles.isEmpty())
    }
}
