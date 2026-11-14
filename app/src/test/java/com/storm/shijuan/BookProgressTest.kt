package com.storm.shijuan

import com.storm.shijuan.data.Store
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class BookProgressTest {

    @Test
    fun testBookProgressDefaultsAndCopy() {
        val p = Store.BookProgress()
        assertEquals(0, p.chapterIndex)
        assertEquals(0, p.page)
        assertEquals(0, p.scrollY)

        val updated = p.copy(chapterIndex = 5, page = 2, scrollY = 1200)
        assertEquals(5, updated.chapterIndex)
        assertEquals(2, updated.page)
        assertEquals(1200, updated.scrollY)
    }

    @Test
    fun testSha256KeyCollisionProof() {
        fun sha256Key(bookUrl: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(bookUrl.toByteArray(Charsets.UTF_8))
            return "bp_" + bytes.joinToString("") { "%02x".format(it) }
        }

        // 两个在 Java hashCode() 下碰撞的经典字符串对
        val s1 = "FB"
        val s2 = "Ea"
        assertEquals("Java hashCode 应当碰撞", s1.hashCode(), s2.hashCode())

        val k1 = sha256Key(s1)
        val k2 = sha256Key(s2)
        assertNotEquals("SHA-256 key 决不允许碰撞", k1, k2)
        assertTrue(k1.startsWith("bp_"))
        assertEquals(3 + 64, k1.length)
    }

    @Test
    fun testProgressFormatSerializationAndParsing() {
        val p = Store.BookProgress(chapterIndex = 42, page = 3, scrollY = 1580)
        val serialized = "${p.chapterIndex},${p.page},${p.scrollY}"
        assertEquals("42,3,1580", serialized)

        val parts = serialized.split(',')
        assertEquals(3, parts.size)
        val parsed = Store.BookProgress(
            chapterIndex = parts[0].toInt(),
            page = parts[1].toInt(),
            scrollY = parts[2].toInt(),
        )
        assertEquals(p, parsed)
    }

    @Test
    fun testSingleRecordPerBookRule() {
        // 模拟一本书在阅读过程中只维护一条记录（只存当前章）：
        var current = Store.BookProgress()

        // 1. 在第 0 章滚动到 500px
        current = if (current.chapterIndex == 0) current.copy(scrollY = 500)
        else Store.BookProgress(0, 0, 500)
        assertEquals(0, current.chapterIndex)
        assertEquals(500, current.scrollY)

        // 2. 切到第 1 章
        current = if (current.chapterIndex == 1) current.copy(scrollY = 0)
        else Store.BookProgress(chapterIndex = 1, page = 0, scrollY = 0)
        assertEquals(1, current.chapterIndex)
        assertEquals(0, current.page)
        assertEquals(0, current.scrollY)

        // 3. 翻页模式下在第 1 章翻到第 4 页
        current = if (current.chapterIndex == 1) current.copy(page = 4)
        else Store.BookProgress(chapterIndex = 1, page = 4, scrollY = 0)
        assertEquals(1, current.chapterIndex)
        assertEquals(4, current.page)

        // 4. 查询第 0 章时，由于已不在第 0 章，返回 0
        val pageCh0 = if (current.chapterIndex == 0) current.page else 0
        val scrollCh0 = if (current.chapterIndex == 0) current.scrollY else 0
        assertEquals(0, pageCh0)
        assertEquals(0, scrollCh0)

        // 5. 查询当前章第 1 章，正确返回第 4 页
        val pageCh1 = if (current.chapterIndex == 1) current.page else 0
        assertEquals(4, pageCh1)
    }
}
