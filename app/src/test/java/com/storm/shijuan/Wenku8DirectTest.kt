package com.storm.shijuan

import com.storm.shijuan.data.Wenku8Direct
import org.junit.Assert.*
import org.junit.Test

class Wenku8DirectTest {

    @Test
    fun testExtractAid() {
        assertEquals(3001, Wenku8Direct.extractAid("https://www.wenku8.cc/book/3001.htm"))
        assertEquals(3001, Wenku8Direct.extractAid("https://www.wenku8.net/novel/3/3001/index.htm"))
        assertEquals(2580, Wenku8Direct.extractAid("https://dl.wenku8.com/down.php?type=utf8&node=1&id=2580"))
        assertEquals(2580, Wenku8Direct.extractAid("wenku8://2580/chapter_0"))
        assertEquals(1234, Wenku8Direct.extractAid("wenku8://1234"))
    }

    @Test
    fun testParseTxt() {
        val sampleText = """
★☆★☆★☆轻小说文库(Www.WenKu8.Com)☆★☆★☆★

<测试轻小说>

第一卷 序曲

    这是序曲内容。

第一卷 第一章 初遇

    这是第一章内容。
    今天天气很好。

第一卷 第二章 重逢

    这是第二章内容。

第一卷 尾声

    全书完。

第一卷 后记

    感谢读者支持！
        """.trimIndent()

        val chapters = Wenku8Direct.parseTxt(9999, sampleText)
        assertEquals(5, chapters.size)

        assertEquals("第一卷 序曲", chapters[0].first.name)
        assertEquals("第一卷", chapters[0].first.volume)
        assertEquals("这是序曲内容。", chapters[0].second)

        assertEquals("第一卷 第一章 初遇", chapters[1].first.name)
        assertEquals("第一卷", chapters[1].first.volume)
        assertTrue(chapters[1].second.contains("今天天气很好。"))

        assertEquals("第一卷 后记", chapters[4].first.name)
        assertEquals("第一卷", chapters[4].first.volume)
        assertEquals("感谢读者支持！", chapters[4].second)
    }
}
