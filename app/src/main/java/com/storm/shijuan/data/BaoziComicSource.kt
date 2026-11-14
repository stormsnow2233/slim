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

    private val mirrors: List<String> = listOf(
        "https://cn.webmota.com",
        "https://www.webmota.com",
        "https://baozimh.org",
        "https://cn.baozimh.com",
        "https://www.baozimh.com",
    )

    private val appHosts: List<String> = listOf(
        "appgb-vdkr.baozimh.com",
        "appgb1-vdkr.baozimh.com",
        "appgb2-vdkr.baozimh.com",
        "app1-vdkr.baozimh.com",
        "app2-vdkr.baozimh.com",
    )

    private val appHeaders: Map<String, String> = mapOf(
        "User-Agent" to "baozimh_android/1.0.31/gb/adset",
        "Referer" to "https://app.baozimh.com/",
        "app-id" to "cn.sts.xiaoyun.ordermeals",
        "app-version" to "1.0.31",
        "device-code" to "2c712c6ba4e95a9f4157f94e1794a86c",
        "device-id" to "BE2A.250530.026.F3",
    )

    @Volatile
    private var liveBase: String = mirrors.first()
    private val base: String get() = liveBase

    private fun extractPath(urlOrPath: String): String {
        val s = urlOrPath.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) {
            val schemeEnd = s.indexOf("://")
            val pathStart = s.indexOf('/', schemeEnd + 3)
            return if (pathStart >= 0) s.substring(pathStart) else "/"
        }
        return if (s.startsWith("/")) s else "/$s"
    }

    private fun normalizeChapterPath(urlOrPath: String): String {
        val path = extractPath(urlOrPath).replace("&amp;", "&")
        if (path.contains("/user/page_direct")) {
            val comicId = Regex("""[?&]comic_id=([^&]+)""").find(path)?.groupValues?.get(1).orEmpty()
            val sectionSlot = Regex("""[?&]section_slot=(\d+)""").find(path)?.groupValues?.get(1) ?: "0"
            val chapterSlot = Regex("""[?&]chapter_slot=(\d+)""").find(path)?.groupValues?.get(1) ?: "0"
            if (comicId.isNotBlank()) {
                return "/comic/chapter/$comicId/${sectionSlot}_${chapterSlot}.html"
            }
        }
        return path
    }

    private fun fetch(path: String, fast: Boolean = false): Net.Resp {
        val cleanPath = path.replace("&amp;", "&")
        val relPath = normalizeChapterPath(cleanPath)
        val order = listOf(liveBase) + mirrors.filter { it != liveBase }
        var lastErr = ""
        for (b in order) {
            val url = "$b$relPath"
            val r = Net.get(url, referer = "$b/", useCache = true, fast = fast)
            if (r.ok) {
                if (r.body.contains("Just a moment...") || r.body.contains("cf-browser-verification") || r.body.contains("gk-gatekeeper")) {
                    lastErr = "触发防火墙验证"
                    continue
                }
                liveBase = b
                return r
            } else {
                lastErr = r.error.ifBlank { "HTTP ${r.code}" }
            }
        }
        return Net.Resp(false, 0, "", "包子漫画镜像暂时不可达: ${lastErr.ifBlank { "网络不通或触发验证" }}")
    }

    override fun home(): List<Comic> {
        val r = fetch("/classify", fast = true)
        if (!r.ok) return emptyList()
        return parseComics(r.body)
    }

    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val r = fetch("/search?q=$kw", fast = true)
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

        // 关键修复：包子漫画页面顶部有「最新章节」区块（以倒序展示最新20余话），下方才是「章节目录」全部章节。
        // 若直接全文扫描并在首部去重，会导致最新几话倒插在列表开头（如 500-490-1-30）。
        // 因此优先从「章节目录」或专属容器开始解析：
        val catalogStart = listOf(
            s.indexOf("id=\"chapter-items\""),
            s.indexOf("章节目录"),
            s.indexOf("全部章节"),
            s.indexOf("id=\"chapters_other_list\""),
        ).filter { it >= 0 }.minOrNull() ?: 0

        val catalogHtml = if (catalogStart > 0) s.substring(catalogStart) else s

        // 包子漫画章节有两种形式：
        // 形式 1: <a href="/user/page_direct?comic_id=...&section_slot=...&chapter_slot=..."><span>第1话</span></a>
        // 形式 2: <a href="/comic/chapter/...">第1话</a>
        val regex = Regex("""<a[^>]+href="(/user/page_direct\?[^"]+|/comic/chapter/[^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        var matches = regex.findAll(catalogHtml).toList()
        if (matches.isEmpty() && catalogStart > 0) {
            matches = regex.findAll(s).toList()
        }

        val rawList = mutableListOf<ComicChapter>()
        val seen = mutableSetOf<String>()

        for (m in matches) {
            val href = m.groupValues[1]
            val inner = m.groupValues[2]
            var title = stripHtml(inner).trim()
            val cleanHref = href.replace("&amp;", "&")
            val normalizedHref = normalizeChapterPath(cleanHref)
            val absUrl = if (normalizedHref.startsWith("http")) normalizedHref else "$base$normalizedHref"
            if (!seen.add(absUrl)) continue

            if (title.isBlank()) {
                val slot = extractSlot(cleanHref, "chapter_slot")
                title = "第${slot + 1}话"
            }
            rawList.add(
                ComicChapter(
                    name = title,
                    url = absUrl,
                    index = 0,
                    sourceId = id,
                )
            )
        }

        if (rawList.isEmpty()) {
            throw IllegalStateException("包子漫画暂无章节或已下架")
        }

        // 若链接带有 section_slot 和 chapter_slot 参数或 /section_slot.html，按槽位升序做最终排序校准，保证 100% 正序排列
        val hasSlot = rawList.any { it.url.contains("chapter_slot=") || Regex("""/\d+_\d+\.html""").containsMatchIn(it.url) }
        val sortedList = if (hasSlot) {
            rawList.sortedWith(compareBy(
                { extractSlot(it.url, "section_slot") },
                { extractSlot(it.url, "chapter_slot") }
            ))
        } else {
            rawList
        }

        return sortedList.mapIndexed { idx, ch -> ch.copy(index = idx) }
    }

    private fun extractSlot(url: String, key: String): Int {
        val querySlot = Regex("""[?&]$key=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        if (querySlot != null) return querySlot
        val pathMatch = Regex("""/(\d+)_(\d+)\.html""").find(url)
        if (pathMatch != null) {
            return if (key == "section_slot") {
                pathMatch.groupValues[1].toIntOrNull() ?: 0
            } else {
                pathMatch.groupValues[2].toIntOrNull() ?: 0
            }
        }
        return 0
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val cleanPath = normalizeChapterPath(chapter.url)
        val comicSlug = Regex("""/comic/chapter/([^/]+)/""").find(cleanPath)?.groupValues?.get(1)
            ?: Regex("""/comic/([^/?#]+)""").find(comic.url)?.groupValues?.get(1).orEmpty()

        val imgs = mutableListOf<String>()

        // 策略 1: 走 Baozi 官方 App bypass 接口（最新章及单话漫画必备，突破防爬与 1x1 假图）
        for (host in appHosts) {
            val appUrl = "https://$host/baozimhapp$cleanPath"
            val r = Net.get(appUrl, referer = "https://app.baozimh.com/", headers = appHeaders, useCache = true, fast = true)
            if (r.ok && !r.body.contains("cf-browser-verification")) {
                val pageImgs = parseAppImages(r.body, comicSlug)
                if (pageImgs.isNotEmpty()) {
                    imgs.addAll(pageImgs)
                    // 检查是否有后续分页（如“点击进入下一页”）
                    var nextHref = extractNextSubpage(r.body)
                    var subLoops = 0
                    while (!nextHref.isNullOrBlank() && subLoops < 15) {
                        subLoops++
                        val nextClean = normalizeChapterPath(nextHref)
                        val nextUrl = "https://$host/baozimhapp$nextClean"
                        val nextResp = Net.get(nextUrl, referer = "https://app.baozimh.com/", headers = appHeaders, useCache = true, fast = true)
                        if (nextResp.ok) {
                            val subImgs = parseAppImages(nextResp.body, comicSlug)
                            if (subImgs.isNotEmpty()) {
                                imgs.addAll(subImgs)
                            }
                            nextHref = extractNextSubpage(nextResp.body)
                        } else {
                            break
                        }
                    }
                    if (imgs.isNotEmpty()) break
                }
            }
        }

        // 策略 2: 降级回退至网页镜像抓取（轮询所有镜像，避免单一镜像被注入防爬假图或 403）
        if (imgs.isEmpty()) {
            val order = listOf(liveBase) + mirrors.filter { it != liveBase }
            for (b in order) {
                val url = "$b$cleanPath"
                val r = Net.get(url, referer = "$b/", useCache = true, fast = true)
                if (r.ok && !r.body.contains("cf-browser-verification") && !r.body.contains("Just a moment...")) {
                    val pageImgs = parseWebImages(r.body, comicSlug)
                    if (pageImgs.isNotEmpty()) {
                        imgs.addAll(pageImgs)
                        liveBase = b
                        var nextHref = extractNextSubpage(r.body)
                        var subLoops = 0
                        while (!nextHref.isNullOrBlank() && subLoops < 15) {
                            subLoops++
                            val nextClean = normalizeChapterPath(nextHref)
                            val nextResp = Net.get("$b$nextClean", referer = "$b/", useCache = true, fast = true)
                            if (nextResp.ok) {
                                val subImgs = parseWebImages(nextResp.body, comicSlug)
                                if (subImgs.isNotEmpty()) {
                                    imgs.addAll(subImgs)
                                }
                                nextHref = extractNextSubpage(nextResp.body)
                            } else {
                                break
                            }
                        }
                        if (imgs.isNotEmpty()) break
                    }
                }
            }
        }

        val cleanImgs = imgs.distinct()
        if (cleanImgs.isEmpty()) {
            throw IllegalStateException("该章节暂无可用图片或已被下架")
        }
        return ComicPages(
            images = cleanImgs,
            headers = headers(),
            referer = "https://www.baozimh.com/",
        )
    }

    private fun parseAppImages(html: String, comicSlug: String): List<String> {
        val list = mutableListOf<String>()
        val regex = Regex("""data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { m ->
            val src = m.groupValues[1]
            if (isValidComicImage(src, comicSlug)) {
                list.add(normalizeImageUrl(src))
            }
        }
        if (list.isEmpty()) {
            val srcRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            srcRegex.findAll(html).forEach { m ->
                val src = m.groupValues[1]
                if (isValidComicImage(src, comicSlug)) {
                    list.add(normalizeImageUrl(src))
                }
            }
        }
        return list.distinct()
    }

    private fun parseWebImages(html: String, comicSlug: String): List<String> {
        val list = mutableListOf<String>()
        // 1. amp-state json 数据块
        val ampStateRegex = Regex("""<amp-state[^>]*id="chapter[^"]*Src"[^>]*>[\s\S]*?<script[^>]*type="application/json"[^>]*>([\s\S]*?)</script>[\s\S]*?</amp-state>""", RegexOption.IGNORE_CASE)
        ampStateRegex.findAll(html).forEach { m ->
            val jsonStr = m.groupValues[1].trim()
            runCatching {
                val json = JSONObject(jsonStr)
                val url = json.optString("url")
                if (isValidComicImage(url, comicSlug)) {
                    list.add(normalizeImageUrl(url))
                }
            }
        }
        // 2. amp-img 或 img src/data-src
        val imgRegex = Regex("""<(?:amp-img|img)[^>]+(?:src|data-src)="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
        imgRegex.findAll(html).forEach { m ->
            val tag = m.value
            if (tag.contains("""width="1"""") && tag.contains("""height="1"""")) return@forEach
            val src = m.groupValues[1]
            if (isValidComicImage(src, comicSlug)) {
                list.add(normalizeImageUrl(src))
            }
        }
        return list.distinct()
    }

    private fun isValidComicImage(src: String, comicSlug: String): Boolean {
        if (src.isBlank()) return false
        val lower = src.lowercase()
        if (lower.contains("favicon") || lower.contains("apple-icon") || lower.contains("logo") ||
            lower.contains("/cover/") || lower.contains("pixel.gif") || lower.contains("loading.gif") ||
            lower.contains("default_cover")
        ) return false

        val isMangaPath = lower.contains("/scomic/") || lower.contains("/comic/") ||
                lower.contains(".baozicdn.com") || lower.contains(".bzcdn.net")
        if (!isMangaPath) return false

        // 防爬钓鱼假图过滤：包子漫画对被锁章节会注入其它作品的 1.jpg（例如 meishaonianbangjiashijian）
        if (comicSlug.isNotBlank() && lower.contains("meishaonianbangjiashijian") && !comicSlug.contains("meishaonian")) {
            return false
        }
        return true
    }

    private fun normalizeImageUrl(url: String): String {
        var clean = url.trim().replace("&amp;", "&")
        if (clean.startsWith("//")) clean = "https:$clean"
        if (clean.contains(".bzcdn.net")) {
            clean = clean.replace(".bzcdn.net", ".baozicdn.com")
        }
        return clean
    }

    private fun extractNextSubpage(html: String): String? {
        val nextRegex = Regex("""<a[^>]+href=["']([^"']+)["'][^>]*>(?:[\s\S]*?)(?:点击进入下一页|點擊進入下一頁)""", RegexOption.IGNORE_CASE)
        return nextRegex.find(html)?.groupValues?.get(1)
    }

    override fun headers(): Map<String, String> = mapOf(
        "User-Agent" to "baozimh_android/1.0.31/gb/adset",
        "Referer" to "https://www.baozimh.com/",
        "app-id" to "cn.sts.xiaoyun.ordermeals",
        "app-version" to "1.0.31",
        "device-code" to "2c712c6ba4e95a9f4157f94e1794a86c",
        "device-id" to "BE2A.250530.026.F3",
    )

    private fun stripHtml(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "").replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()
}
