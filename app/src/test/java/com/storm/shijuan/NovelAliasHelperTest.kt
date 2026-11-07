package com.storm.shijuan

import com.storm.shijuan.data.NovelAliasHelper
import org.junit.Assert.*
import org.junit.Test

class NovelAliasHelperTest {

    @Test
    fun testMakeineAliasAndFuzzyMatch() {
        val q = "败犬女主角太多了"
        val expanded = NovelAliasHelper.expandQueries(q)
        println("Expanded for '$q': $expanded")
        assertTrue("Expanded queries should contain 败北女角太多了 or 败北女角",
            expanded.any { it.contains("败北") })

        val score = NovelAliasHelper.matchScore("败北女角太多了！", "雨森焚火", q)
        println("Score for '败北女角太多了！' with '$q': $score")
        assertTrue("Score should be > 1.0 for alias match", score > 1.0)
    }

    @Test
    fun testKomiAliasAndFuzzyMatch() {
        val q = "古见同学有社交障碍"
        val expanded = NovelAliasHelper.expandQueries(q)
        println("Expanded for '$q': $expanded")
        assertTrue("Expanded queries should contain 交流障碍",
            expanded.any { it.contains("交流障碍") })

        val score = NovelAliasHelper.matchScore("古见同学有交流障碍症", "小田智仁", q)
        println("Score for '古见同学有交流障碍症' with '$q': $score")
        assertTrue("Score should be > 1.0 for Komi match", score > 1.0)
    }

    @Test
    fun testWenku8LocalIndexMakeineSearch() {
        val books = com.storm.shijuan.data.Wenku8LocalIndex.search("败犬女主角太多了")
        println("Wenku8 search for '败犬女主角太多了' returned ${books.size} books:")
        books.take(3).forEach { println(" - ${it.name} by ${it.author} (${it.latest})") }
        assertTrue("Should return at least 1 book", books.isNotEmpty())
        assertEquals("败北女角太多了！", books.first().name)
    }
}
