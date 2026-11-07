package com.storm.shijuan.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * 包子漫画源 (BaoziMH)
 * 采用极速高可用镜像 cn.webmota.com，免登录免翻墙，超全漫画资源（古见同学等热门日漫全收录且章节完整）
 */
class BaoziComicSource : ComicSource {
    override val id: String = "baozimh"
    override val name: String = "包子漫画"

    private val base: String = "https://cn.webmota.com"
    private val mirrors: List<String> = listOf("https://cn.webmota.com", "https://www.webmota.com")

    private fun fetch(path: String): Net.Resp {
        for (b in mirrors) {
            val url = if (path.startsWith("http")) path else "$b$path"
            val r = Net.get(url, referer = "$b/", useCache = true)
            if (r.ok) return r
        }
        return Net.Resp(false, 0, "", "包子漫画网络不通")
    }

    override fun home(): List<Comic> {
        val r = fetch("/classify")
        if (!r.ok) return emptyList()
        return parseComics(r.body)
    }

    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val r = fetch("/search?q=$kw")
        if (!r.ok) {
            throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        }
        val comics = parseComics(r.body)
        if (comics.isEmpty()) {
            throw IllegalStateException("包子漫画未搜到「$keyword」")
        }
        return comics
    }

    private fun parseComics(html: String): List<Comic> {
        // 包子漫画条目形式：<a href="/comic/..." title="..."> 或者内部带有 h3 class="text-truncate"
        val regex = Regex("""<a[^>]+href="(/comic/[^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val list = mutableListOf<Comic>()
        regex.findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val inner = m.groupValues[2]
            // 从 inner 中提取标题
            var title = Regex("""<h3[^>]*class="[^"]*text-truncate[^"]*"[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)?.trim().orEmpty()
            if (title.isBlank()) {
                title = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)?.trim().orEmpty()
            }
            if (title.isBlank()) {
                title = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)?.trim().orEmpty()
            }
            title = stripHtml(title)
            if (title.isBlank() || href.contains("/comic/chapter/")) return@forEach

            // 提取封面
            val cover = Regex("""(?:src|data-src)="([^"]+cover[^"]*)"""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)
                ?: Regex("""amp-img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1).orEmpty()

            val cleanUrl = if (href.startsWith("http")) href else "$base$href"
            list.add(
                Comic(
                    name = title,
                    url = cleanUrl,
                    cover = cover,
                    sourceId = id,
                    sourceName = name,
                )
            )
        }
        return list.distinctBy { it.url }
    }

    override fun detail(comic: Comic): Comic {
        val r = fetch(comic.url)
        if (!r.ok) return comic
        val s = r.body
        val name = Regex("""<h1[^>]*class="[^"]*comics-detail__title[^"]*"[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.let { stripHtml(it) }?.ifBlank { null }
            ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.let { stripHtml(it).split(" - ").firstOrNull() }
            ?: comic.name

        val cover = Regex("""<amp-img[^>]+src="([^"]+)"[^>]*alt=""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
            ?: Regex("""<img[^>]+class="[^"]*comics-detail__cover[^"]*"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
            ?: comic.cover

        val author = Regex("""<h2[^>]*class="[^"]*comics-detail__author[^"]*"[^>]*>([\s\S]*?)</h2>""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()

        val intro = Regex("""<p[^>]*class="[^"]*comics-detail__desc[^"]*"[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()

        val latest = Regex("""<span[^>]*class="[^"]*tag[^"]*"[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
            .findAll(s).map { stripHtml(it.groupValues[1]) }.firstOrNull { it.contains("话") || it.contains("更新") }.orEmpty()

        return comic.copy(
            name = name,
            cover = cover,
            author = author.ifBlank { comic.author },
            intro = intro.ifBlank { comic.intro },
            latest = latest.ifBlank { comic.latest },
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val r = fetch(comic.url)
        if (!r.ok) {
            throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        }
        val s = r.body
        val list = mutableListOf<ComicChapter>()

        // 包子漫画章节有两种形式：
        // 形式 1: <a href="/user/page_direct?comic_id=...&section_slot=...&chapter_slot=..."><span>第1话</span></a>
        // 形式 2: <a href="/comic/chapter/...">第1话</a>
        val regex = Regex("""<a[^>]+href="(/user/page_direct\?[^"]+|/comic/chapter/[^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        var idx = 0
        regex.findAll(s).forEach { m ->
            val href = m.groupValues[1]
            val inner = m.groupValues[2]
            var title = stripHtml(inner).trim()
            if (title.isBlank()) {
                val slot = Regex("""chapter_slot=(\d+)""").find(href)?.groupValues?.get(1)
                title = if (slot != null) "第${slot.toInt() + 1}话" else "第${idx + 1}话"
            }
            val absUrl = if (href.startsWith("http")) href else "$base$href"
            list.add(
                ComicChapter(
                    name = title,
                    url = absUrl,
                    index = idx++,
                    sourceId = id,
                )
            )
        }

        if (list.isEmpty()) {
            throw IllegalStateException("包子漫画暂无章节或已下架")
        }
        return list.distinctBy { it.url }
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val r = fetch(chapter.url)
        if (!r.ok) {
            throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        }
        val s = r.body
        val imgs = mutableListOf<String>()

        // 提取 amp-img 或 img src，滤掉静态图标
        val imgRegex = Regex("""<(?:amp-img|img)[^>]+(?:src|data-src)="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        imgRegex.findAll(s).forEach { m ->
            val src = m.groupValues[1]
            if ((src.contains("scomic") || src.contains("comic") || src.contains(".bzcdn.net")) &&
                !src.contains("favicon") && !src.contains("apple-icon") && !src.contains("logo")
            ) {
                imgs.add(src)
            }
        }

        val cleanImgs = imgs.distinct()
        if (cleanImgs.isEmpty()) {
            throw IllegalStateException("该章节暂无可用图片")
        }
        return ComicPages(
            images = cleanImgs,
            referer = chapter.url,
        )
    }

    override fun headers(): Map<String, String> = mapOf("Referer" to "$base/")

    private fun stripHtml(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "").replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()
}
