package com.storm.shijuan

import com.storm.shijuan.data.Zlib
import org.junit.Assert.assertEquals
import org.junit.Test

class ZlibLinkTest {
    @Test fun resolvesBookAndDownloadPaths() {
        assertEquals("https://example.org/book/123/hash", Zlib.resolveLink("https://example.org", "/book/123/hash"))
        assertEquals("https://example.org/dl/123", Zlib.resolveLink("https://example.org/", "dl/123"))
        assertEquals("https://other.org/file.epub", Zlib.resolveLink("https://example.org", "https://other.org/file.epub"))
        assertEquals("", Zlib.resolveLink("https://example.org", "javascript:alert(1)"))
    }
}
