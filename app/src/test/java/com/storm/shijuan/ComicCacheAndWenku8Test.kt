package com.storm.shijuan

import com.storm.shijuan.data.Comic
import com.storm.shijuan.data.ComicChapter
import com.storm.shijuan.data.ComicDownloader
import com.storm.shijuan.data.ComicRepo
import com.storm.shijuan.data.DownloadTask
import com.storm.shijuan.data.LZString
import com.storm.shijuan.data.STATE_DONE
import com.storm.shijuan.data.STATE_PAUSED
import com.storm.shijuan.data.STATE_RUNNING
import com.storm.shijuan.data.Wenku8Direct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicCacheAndWenku8Test {

    @Test
    fun testComicSourcesIncludeManhuaguiAnd1kkk() {
        val sourceIds = ComicRepo.sources.map { it.id }
        assertTrue("漫画源中应包含漫画柜 manhuagui", sourceIds.contains("manhuagui"))
        assertTrue("漫画源中应包含极速漫画 1kkk", sourceIds.contains("1kkk"))
        assertTrue("漫画源中应包含包子漫画 baozimh", sourceIds.contains("baozimh"))

        val mhg = ComicRepo.byIdOrNull("manhuagui")
        assertNotNull("漫画柜实例应可获取", mhg)
        assertEquals("漫画柜", mhg!!.name)
    }

    @Test
    fun testLZStringRoundTripChineseJapanese() {
        val original = "孤独摇滚！后藤一里！古见同学有交流障碍症"
        val compressed = LZString.compressToBase64(original)
        val decompressed = LZString.decompressFromBase64(compressed)
        assertEquals("LZString 压缩解压应完全一致", original, decompressed)
    }

    @Test
    fun testNormalizeChaptersReverse() {
        val inverted = listOf(
            ComicChapter("第501话", "/c/501", 0),
            ComicChapter("第500话", "/c/500", 1),
            ComicChapter("第2话", "/c/2", 2),
            ComicChapter("第1话", "/c/1", 3),
        )
        val normalized = ComicRepo.normalizeChapters(inverted)
        assertEquals("第1话", normalized.first().name)
        assertEquals("第501话", normalized.last().name)
    }

    @Test
    fun testWenku8DirectUrlGeneration() {
        val aid = 1234
        val node1 = Wenku8Direct.directUrl(aid, 1)
        val node2 = Wenku8Direct.directUrl(aid, 2)
        val node3 = Wenku8Direct.directUrl(aid, 3)

        assertEquals("https://dl.wenku8.com/down.php?type=utf8&node=1&id=1234", node1)
        assertEquals("https://dl.wenku8.com/down.php?type=utf8&node=2&id=1234", node2)
        assertEquals("https://dl.wenku8.com/down.php?type=utf8&node=3&id=1234", node3)
    }

    @Test
    fun testWenku8DirectTxtParsing() {
        val fakeTxt = """
            轻小说文库(www.wenku8.net)
            《班里的人气偶像黏上了一辈子不想工作的我》
            第一卷
            第一章 序章
            今天的天气非常晴朗。
            第二章 与美少女的邂逅
            放学后的教室格外安静。
        """.trimIndent()

        val parsed = Wenku8Direct.parseTxt(9999, fakeTxt)
        assertTrue("应至少解析出两个章节", parsed.size >= 2)
        assertTrue("章节名包含序章", parsed[0].first.name.contains("序章") || parsed[0].first.name.contains("第一章"))
        assertTrue("第二章正文包含教室", parsed[1].second.contains("放学后的教室"))
        assertEquals("第一卷", parsed[1].first.volume)
    }

    @Test
    fun testDownloadTaskStateFlow() {
        val comic = Comic(name = "孤独摇滚！", url = "https://m.manhuagui.com/comic/32263/", sourceId = "manhuagui")
        val key = ComicDownloader.keyOf(comic)
        val task = DownloadTask(
            key = key,
            comic = comic,
            chapters = listOf(ComicChapter("第1话", "/c1", 0), ComicChapter("第2话", "/c2", 1)),
            state = STATE_RUNNING,
            doneChapters = 1,
            pagesDone = 20,
        )

        assertEquals(0.5f, task.percent, 0.01f)
        val pausedTask = task.copy(state = STATE_PAUSED)
        assertEquals(STATE_PAUSED, pausedTask.state)
        val doneTask = task.copy(state = STATE_DONE, doneChapters = 2)
        assertEquals(1.0f, doneTask.percent, 0.01f)
    }

    @Test
    fun testBaoziComicPagesParseAndFilter() {
        val baozi = ComicRepo.byIdOrNull("baozimh") as? com.storm.shijuan.data.BaoziComicSource
        assertNotNull("BaoziComicSource 应存在", baozi)

        val headers = baozi!!.headers()
        assertTrue("Headers 应包含 app-id", headers.containsKey("app-id"))
        assertEquals("baozimh_android/1.0.31/gb/adset", headers["User-Agent"])

        val testComic = Comic(
            name = "由里到外被欺负了个遍",
            url = "https://cn.webmota.com/comic/youlidaowaibeiqifuliaogebian-xiaolongkotatu",
            sourceId = "baozimh",
        )
        val chapter = ComicChapter(
            name = "第01话",
            url = "https://cn.webmota.com/comic/chapter/youlidaowaibeiqifuliaogebian-xiaolongkotatu/0_0.html",
            index = 0,
            sourceId = "baozimh",
        )
        val result = baozi.pages(testComic, chapter)
        assertTrue("章节图片数应大于 1", result.images.size >= 2)
        assertTrue("图片域名应为 baozicdn.com", result.images.all { it.contains("baozicdn.com") })
        assertTrue("不应包含被钓鱼替换的他作封面", result.images.none { it.contains("meishaonianbangjiashijian") })
    }
}
