package com.storm.shijuan

import com.storm.shijuan.data.JmComicSource
import com.storm.shijuan.data.JmImageDescrambler
import org.junit.Assert.*
import org.junit.Test

class JmComicTest {

    @Test
    fun testParseSrcAndSegNum() {
        val src = "https://cdn-msp3.jmdanjonproxy.vip/media/photos/1488712/00001.webp"
        val parsed = JmImageDescrambler.parseSrc(src)
        assertNotNull(parsed)
        assertEquals(1488712, parsed!!.first)
        assertEquals("00001", parsed.second)

        val num = JmImageDescrambler.segNum(parsed.first, parsed.second)
        assertEquals(4, num)

        // Below SCRAMBLE_ID should not be scrambled
        val numLow = JmImageDescrambler.segNum(100000, "00001")
        assertEquals(0, numLow)
    }

    @Test
    fun testJmSourceInstantiationAndHeaders() {
        val source = JmComicSource()
        assertEquals("jm", source.id)
        assertEquals("JMcomic", source.name)
        val headers = source.headers()
        assertTrue(headers.containsKey("token"))
        assertTrue(headers.containsKey("User-Agent"))
    }
}
