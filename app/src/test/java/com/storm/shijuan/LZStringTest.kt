package com.storm.shijuan

import com.storm.shijuan.data.LZString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LZStringTest {
    @Test
    fun testRoundTrip() {
        val original = "Hello World! 孤独摇滚 ぼっち・ざ・ろっく！ 1234567890 | chapter | image.jpg"
        val compressed = LZString.compressToBase64(original)
        assertTrue(compressed.isNotBlank())
        val decompressed = LZString.decompressFromBase64(compressed)
        assertEquals(original, decompressed)
    }
}
