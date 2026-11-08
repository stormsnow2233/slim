package com.storm.shijuan

import com.storm.shijuan.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SourceSwitchAndLegadoTest {

    @Test
    fun testBookAltSourcesSerialization() {
        val alt1 = Book(name = "邻家天使", author = "佐伯さん", sourceId = "sodugu", sourceName = "速读谷", url = "https://sodugu.org/1")
        val alt2 = Book(name = "邻家天使", author = "佐伯さん", sourceId = "17k", sourceName = "17K小说网", url = "https://17k.com/2")
        val mainBook = Book(
            name = "关于邻家的天使大人不知不觉把我惯成了废人这档事",
            author = "佐伯さん",
            sourceId = "lnovel",
            sourceName = "轻小说百科",
            url = "https://lnovel.tw/books/1",
            altSources = listOf(alt1, alt2)
        )

        val json = mainBook.toJson()
        val restored = Book.from(json)

        assertEquals(mainBook.name, restored.name)
        assertEquals(2, restored.altSources.size)
        assertEquals("sodugu", restored.altSources[0].sourceId)
        assertEquals("17k", restored.altSources[1].sourceId)
    }

    @Test
    fun testLegadoSearchUrlParsing() {
        // Pattern 1: GET with {{key}} and {{page}}
        val raw1 = "/books?page={{page}}&q%5Bname_cont%5D={{key}}"
        val res1 = CustomSources.parseSearchUrl(raw1)
        assertEquals("/books?page=1&q%5Bname_cont%5D={key}", res1.url)
        assertEquals("GET", res1.method)
        assertEquals("", res1.body)

        // Pattern 2: POST with JSON body and charset
        val raw2 = """https://www.deqixs.org/modules/article/search.php,{
            "body": "searchkey={{key}}&action=login&searchtype=all",
            "method": "POST",
            "charset": "gbk"
        }"""
        val res2 = CustomSources.parseSearchUrl(raw2)
        assertEquals("https://www.deqixs.org/modules/article/search.php", res2.url)
        assertEquals("POST", res2.method)
        assertEquals("searchkey={key}&action=login&searchtype=all", res2.body)
        assertEquals("gbk", res2.charset)

        // Pattern 3: With cookie prefix
        val raw3 = "{{cookie.removeCookie(source.key)}}/e/search/indexsearch.php,{\"method\":\"POST\",\"body\":\"keyboard={{key}}\"}"
        val res3 = CustomSources.parseSearchUrl(raw3)
        assertEquals("/e/search/indexsearch.php", res3.url)
        assertEquals("POST", res3.method)
        assertEquals("keyboard={key}", res3.body)
    }

    @Test
    fun testLegadoCssParsingAndFiltering() {
        // Safe CSS selector conversion
        assertEquals(".bookbox a@text", CustomSources.readCss("class.bookbox@tag.a@text"))
        assertEquals("#content@html", CustomSources.readCss("@css:#content@html"))
        assertEquals(".card-text", CustomSources.readCss(".card-text##regex_remove"))
        
        // JS script filter
        assertEquals("", CustomSources.readCss("@js:java.ajax(url)"))
        assertEquals("", CustomSources.readCss("<js>fqContent(result)</js>"))
    }

    @Test
    fun testSmartChapterMatching() {
        val chapters = listOf(
            Chapter("序章", "url0", 0),
            Chapter("第一卷 第一章 邂逅天使", "url1", 1),
            Chapter("第一卷 第二章 雨中的关怀", "url2", 2),
            Chapter("第一卷 第三章 邻居的日常", "url3", 3),
            Chapter("第一卷 第四章 感冒与照料", "url4", 4),
            Chapter("第一卷 第五章 圣诞节的约定", "url5", 5),
        )

        // Exact match
        assertEquals(1, NovelAliasHelper.matchChapterIndex("第一卷 第一章 邂逅天使", 0, chapters))

        // Normalized match without volume prefix
        assertEquals(2, NovelAliasHelper.matchChapterIndex("第二章 雨中的关怀", 0, chapters))

        // Chinese numeral vs Arabic numeral match
        assertEquals(3, NovelAliasHelper.matchChapterIndex("第3章 邻居的日常", 0, chapters))
        assertEquals(4, NovelAliasHelper.matchChapterIndex("第4章", 0, chapters))
        assertEquals(5, NovelAliasHelper.matchChapterIndex("第5章", 0, chapters))

        // Fallback when no match
        assertEquals(0, NovelAliasHelper.matchChapterIndex("未知外传", 0, chapters))
        assertEquals(3, NovelAliasHelper.matchChapterIndex("未知外传", 3, chapters))
    }
}
