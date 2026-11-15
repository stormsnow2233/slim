package com.storm.shijuan

import com.storm.shijuan.data.*
import org.junit.Assert.*
import org.junit.Test

class CustomProxyAndNovelDownloaderTest {

    @Test
    fun testNetProxySwitching() {
        // 初始状态为直连
        Net.setProxy(Net.ProxyCfg(enabled = false))
        assertFalse(Net.proxy().enabled)
        assertFalse(Net.proxy().usable)

        // 开启自定义代理
        val custom = Net.ProxyCfg(enabled = true, host = "127.0.0.1", port = 7890)
        Net.setProxy(custom)
        assertTrue(Net.proxy().enabled)
        assertTrue(Net.proxy().usable)
        assertEquals("127.0.0.1", Net.proxy().host)
        assertEquals(7890, Net.proxy().port)

        // 关闭自定义代理，回归直连
        Net.setProxy(Net.ProxyCfg(enabled = false))
        assertFalse(Net.proxy().enabled)
    }

    @Test
    fun testBuiltinSourceVaultContainsDeqixs() {
        val list = BuiltinSourceVault.load()
        assertTrue("Vault should load sources", list.isNotEmpty())

        val deqixs = list.firstOrNull { it.id == "deqixs" }
        assertNotNull("deqixs source should be present", deqixs)
        assertEquals("得奇小说", deqixs?.name)
        assertEquals("https://www.deqixs.org", deqixs?.base)
        assertTrue("deqixs should be enabled by default", deqixs?.enabledByDefault == true)
        assertNotNull(deqixs?.search)
        assertEquals("POST", deqixs?.search?.method)
        assertEquals("div.con@html", deqixs?.content?.body)
        assertEquals("div.dir a", deqixs?.toc?.list)

        val zlib = list.firstOrNull { it.id == "zlib" }
        assertNotNull("zlib source should be present", zlib)
        assertEquals("Z-Library", zlib?.name)
        assertEquals("zlib", zlib?.apiKind)
        assertTrue("zlib should be enabled by default", zlib?.enabledByDefault == true)
    }

    @Test
    fun testComicDownloadTaskSpeedFormatted() {
        val task0 = DownloadTask(state = STATE_WAITING, speedBytesPerSec = 0L)
        assertEquals("", task0.speedFormatted)

        val taskRunning = DownloadTask(state = STATE_RUNNING, speedBytesPerSec = 1024L * 512)
        assertEquals("512 KB/s", taskRunning.speedFormatted)

        val taskRunningMb = DownloadTask(state = STATE_RUNNING, speedBytesPerSec = 1024L * 1024 * 3)
        assertEquals("3.0 MB/s", taskRunningMb.speedFormatted)

        val taskPaused = DownloadTask(state = STATE_PAUSED, speedBytesPerSec = 1024L * 1024)
        assertEquals("", taskPaused.speedFormatted)
    }

    @Test
    fun testNovelDownloadTaskStateAndSpeed() {
        val chs = listOf(
            Chapter(name = "第1章", url = "http://test.com/1"),
            Chapter(name = "第2章", url = "http://test.com/2"),
            Chapter(name = "第3章", url = "http://test.com/3"),
            Chapter(name = "第4章", url = "http://test.com/4"),
        )
        val book = Book(name = "测试小说", url = "http://test.com/book", sourceId = "deqixs")
        val task = NovelDownloadTask(
            key = "test_key",
            book = book,
            chapters = chs,
            state = STATE_RUNNING,
            doneChapters = 2,
            speedChaptersPerSec = 4.5f,
        )

        assertEquals(4, task.total)
        assertEquals(0.5f, task.percent, 0.001f)
        assertEquals("4.5 章/s", task.speedFormatted)

        val json = task.toJson()
        val restored = NovelDownloadTask.from(json)
        assertEquals(task.key, restored.key)
        assertEquals(task.book.name, restored.book.name)
        assertEquals(task.chapters.size, restored.chapters.size)
        assertEquals(task.doneChapters, restored.doneChapters)
    }
}
