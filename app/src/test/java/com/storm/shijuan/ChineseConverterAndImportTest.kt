package com.storm.shijuan

import com.storm.shijuan.data.Book
import com.storm.shijuan.data.ChineseConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseConverterAndImportTest {

    @Test
    fun testChineseConverterTraditionalDetection() {
        // 典型繁体中文短语
        val tradSample = "敗北女角太多了！(敗犬女主太多了！) 關於鄰家的天使大人，在開戰前就已經慘敗了。"
        assertTrue("应识别为繁体中文", ChineseConverter.isTraditional(tradSample))

        // 典型简体中文短语
        val simpSample = "败北女角太多了！(败犬女主太多了！) 关于邻家的天使大人，在开战前就已经惨败了。"
        assertFalse("不应识别为繁体中文", ChineseConverter.isTraditional(simpSample))
    }

    @Test
    fun testChineseConverterToSimplified() {
        val input = "敗北女角太多了！(敗犬女主太多了！) 關於鄰家的天使大人不知不覺把我給慣成了廢人 這檔事"
        val expected = "败北女角太多了！(败犬女主太多了！) 关于邻家的天使大人不知不觉把我给惯成了废人 这档事"
        val converted = ChineseConverter.toSimplified(input)
        assertEquals(expected, converted)
    }

    @Test
    fun testImportedBookProperties() {
        val imported = Book(name = "测试EPUB", author = "作者", sourceId = "local", isImported = true)
        assertTrue(imported.isLocalOrImported)

        val searched = Book(name = "网络搜索书籍", author = "作者", sourceId = "wenku8", isImported = false)
        assertFalse(searched.isLocalOrImported)

        // 序列化与反序列化测试
        val json = imported.toJson()
        val restored = Book.from(json)
        assertTrue(restored.isImported)
        assertTrue(restored.isLocalOrImported)
    }
}
