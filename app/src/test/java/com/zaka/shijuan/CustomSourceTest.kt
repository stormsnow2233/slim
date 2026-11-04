package com.zaka.shijuan

import com.zaka.shijuan.data.BookRepo
import com.zaka.shijuan.data.ContentRule
import com.zaka.shijuan.data.CustomSources
import com.zaka.shijuan.data.SearchRule
import com.zaka.shijuan.data.Source
import com.zaka.shijuan.data.TocRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSourceTest {

    @Test
    fun readCss_converts_reading_syntax() {
        // 阅读的 class.xxx@tag.a@text 要能翻成 CSS
        assertEquals(".bookbox a@text", CustomSources.readCss("class.bookbox@tag.a@text"))
        assertEquals("#list dd a@href", CustomSources.readCss("id.list@tag.dd@tag.a@href"))
        // 已经是 CSS 的原样保留
        assertEquals(".item h3 a@text", CustomSources.readCss(".item h3 a@text"))
        assertEquals(".foo .bar@text", CustomSources.readCss("@css:.foo .bar@text"))
        // 空输入不炸
        assertEquals("", CustomSources.readCss(""))
    }

    @Test
    fun parse_reading_format_source() {
        val json = """
        {
          "bookSourceName": "测试源",
          "bookSourceUrl": "https://www.example.com/",
          "ruleSearch": {
            "bookList": "class.bookbox",
            "name": "tag.h4@tag.a@text",
            "bookUrl": "tag.h4@tag.a@href",
            "author": "class.author@text"
          },
          "ruleToc": { "chapterList": "id.list@tag.dd@tag.a" },
          "ruleContent": { "content": "id.content@html" }
        }
        """.trimIndent()
        val list = CustomSources.parseAll(json)
        assertEquals(1, list.size)
        val s = list[0]
        assertEquals("测试源", s.name)
        assertEquals("https://www.example.com", s.base)
        assertEquals(".bookbox", s.search?.list)
        assertEquals("h4 a@text", s.search?.fields?.get("name"))
        assertEquals("#list dd a", s.toc?.list)
        assertEquals("#content@html", s.content?.body)
        // id 要能生成，且不能跟内置源撞
        assertTrue(s.id.startsWith("custom_"))
    }

    @Test
    fun parse_array_and_single() {
        val one = """{"name":"甲","base":"a.com"}"""
        assertEquals(1, CustomSources.parseAll(one).size)

        val arr = """[{"name":"甲","base":"a.com"},{"name":"乙","base":"b.com"}]"""
        val l = CustomSources.parseAll(arr)
        assertEquals(2, l.size)
        assertEquals("乙", l[1].name)
        // 没写 https 也要自动补上
        assertEquals("https://b.com", l[1].base)
    }

    /** 存盘再读回来，规则和 id 都不能漂 */
    @Test
    fun roundtrip_keeps_rules_and_id() {
        val src = Source(
            id = "custom_stable", name = "甲站", group = "我的书源",
            base = "https://a.com", charset = "gbk", status = "custom",
            search = SearchRule(
                url = "/s?q={key}", method = "POST", body = "k={key}",
                list = ".item", fields = mapOf("name" to "a@text", "bookUrl" to "a@href"),
            ),
            toc = TocRule(list = "#list a", page = "https://a.com/i/{id}/"),
            content = ContentRule(body = "#content@html"),
        )
        val back = CustomSources.parseAll(CustomSources.toJson(src).toString()).single()
        assertEquals("custom_stable", back.id)
        assertEquals("甲站", back.name)
        assertEquals("gbk", back.charset)
        assertEquals("/s?q={key}", back.search?.url)
        assertEquals("POST", back.search?.method)
        assertEquals(".item", back.search?.list)
        assertEquals("a@text", back.search?.fields?.get("name"))
        assertEquals("#list a", back.toc?.list)
        assertEquals("https://a.com/i/{id}/", back.toc?.page)
        assertEquals("#content@html", back.content?.body)
    }

    /**
     * 只填「名称 + 网址」的源，靠通用识别能不能搜出书。
     * 网络不通不算失败（这台机器出口在海外），但拿到结果就校验字段完整。
     */
    @Test
    fun bare_source_auto_search() {
        val src = Source(
            id = "custom_probe", name = "裸源", group = "我的书源",
            base = "https://www.biqugese.com", status = "custom",
            search = SearchRule(url = "/search/result.html?searchkey={key}", auto = true),
        )
        val r = runCatching { BookRepo.searchSource(src, "斗破") }.getOrNull()
        println("裸源自动识别搜索: ok=${r?.ok} count=${r?.count} err=${r?.error}")
        r?.books?.take(3)?.forEach { println("  ${it.name} | ${it.author} | ${it.url}") }
        if (r != null && r.ok && r.books.isNotEmpty()) {
            val b = r.books.first()
            assertTrue(b.name.isNotBlank())
            assertTrue(b.url.startsWith("http"))
            assertEquals("custom_probe", b.sourceId)
        }
    }
}
