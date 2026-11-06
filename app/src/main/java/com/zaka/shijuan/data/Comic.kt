package com.zaka.shijuan.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 漫画条目 */
data class Comic(
    val name: String = "",
    val cover: String = "",
    val author: String = "",
    val intro: String = "",
    val latest: String = "",
    val url: String = "",
    val sourceId: String = "dm5",
    val sourceName: String = "动漫屋",
    val status: String = "",
    /** 部分源的标识（拷贝漫画的 path_word） */
    val pathWord: String = "",
    /** 是否已经点过封面（详情页里补充过信息） */
    val detailed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("cover", cover); put("author", author)
        put("intro", intro); put("latest", latest); put("url", url)
        put("sourceId", sourceId); put("sourceName", sourceName)
        put("status", status); put("pathWord", pathWord); put("detailed", detailed)
    }

    companion object {
        fun from(o: JSONObject) = Comic(
            name = o.optString("name"),
            cover = o.optString("cover"),
            author = o.optString("author"),
            intro = o.optString("intro"),
            latest = o.optString("latest"),
            url = o.optString("url"),
            sourceId = o.optString("sourceId").ifBlank { "dm5" },
            sourceName = o.optString("sourceName").ifBlank { "动漫屋" },
            status = o.optString("status"),
            pathWord = o.optString("pathWord"),
            detailed = o.optBoolean("detailed"),
        )
    }
}

/** 漫画章节 */
data class ComicChapter(
    val name: String,
    val url: String,
    val index: Int = 0,
    val sourceId: String = "dm5",
    /** 这一话的封面图。栗子这种站可以直接从封面所在目录推出整话的图片 */
    val cover: String = "",
)

/** 一话的图片列表 */
data class ComicPages(
    val title: String,
    val images: List<String>,
    val referer: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 一次搜索里，某个源的结果 */
data class ComicHit(
    val sourceId: String,
    val sourceName: String,
    val list: List<Comic> = emptyList(),
    val ok: Boolean = true,
    val ms: Long = 0,
    val error: String = "",
)

/**
 * 漫画源。要和小说源一样好加：新站只要实现这一个接口。
 */
interface ComicSource {
    val id: String
    val name: String

    /** 首页 / 排行（按热度给一屏） */
    fun home(): List<Comic>

    fun search(keyword: String): List<Comic>

    fun detail(comic: Comic): Comic = comic

    /** 搜索结果里补封面；默认什么都不做（接口自带封面的源不用管） */
    fun fillCovers(list: List<Comic>): List<Comic> = list

    fun chapters(comic: Comic): List<ComicChapter>

    fun pages(comic: Comic, chapter: ComicChapter): ComicPages

    /** 图片请求头：这些站的图床都校验 Referer，不带就是 403 */
    fun headers(): Map<String, String>
}

/* ─────────────────────── 动漫屋家族 ─────────────────────── */

/**
 * 动漫屋 / 漫画人 是同一套模板，页面结构完全一致：
 * 搜索页、详情页（detail-main-*）、章节列表（chapteritem）、
 * 章节页把图片地址塞在 eval(function(p,a,c,k,e,d)) 打包壳里，靠 JsPacker 本地解。
 *
 * 所以这里用一个类、两个实例，加第三个同类站点只要再配一行。
 */
class Dm5FamilySource(
    override val id: String,
    override val name: String,
    private val base: String,
    /** HTML 搜索页模板（JSON 接口挂了才用） */
    private val searchPath: String,
    /** 首页 / 榜单页 */
    private val homePath: String = "",
    /** 列表页模板：dm5（动漫屋 / 漫画人）用 <li>，漫画 BZ 用 manga-i-list-item */
    private val listStyle: String = "dm5",
    /** JSON 搜索接口，这一类站点共用；留空就只走 HTML */
    private val jsonSearchPath: String = "/search.ashx",
    /** 备用域名：主域名在部分地区解析不到 / 被拦，就挨个换 */
    private val altBases: List<String> = emptyList(),
) : ComicSource {

    /** 所有可用域名，主域名排第一 */
    private val bases: List<String> = (listOf(base) + altBases).distinct()

    /** 当前用得通的那个域名，成功后记住，后面直接走它 */
    @Volatile
    private var liveBase: String = base

    private val origin: String get() = liveBase

    /** 依次试域名，谁通用谁 */
    private fun fetch(path: String, referer: String = "", fast: Boolean = false): Net.Resp {
        val order = listOf(liveBase) + bases.filter { it != liveBase }
        var last = Net.Resp(false, 0, "", "网络不通")
        for (b in order) {
            val r = Net.get(b + path, referer = referer.ifBlank { "$b/" }, useCache = true, fast = fast)
            if (r.ok) { liveBase = b; return r }
            last = r
        }
        return last
    }

    /** 详情 / 章节这类页面地址，只取路径部分，域名用当前能用的那个 */
    private fun pathOf(url: String): String =
        Regex("""^https?://[^/]+(/.*)$""").find(url)?.groupValues?.get(1) ?: url

    /** 搜索结果里补封面的上限：限制并发，避免霸占网络带宽 */
    private val coverFillLimit = 6

    override fun home(): List<Comic> {
        val paths = buildList {
            if (homePath.isNotBlank()) add(homePath)
            add(if (listStyle == "bz") "/manga-list/" else "/manhua-list/")
        }
        for (p in paths) {
            val r = fetch(p, fast = true)
            if (!r.ok) continue
            val out = parseListPage(r.body)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /**
     * 搜索走 JSON 接口 search.ashx。
     *
     * 以前是抓搜索页 HTML，那几个站点的页面里塞满了前端框架的英文单词
     * （list / original / jpkr 之类），正则一松就把它们当书名抓出来了，
     * 出来一堆垃圾条目。接口返回的是干净 JSON，字段就四个：
     * Title / Url / LastPartName / LastPartUrl。
     */
    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        if (jsonSearchPath.isNotBlank()) {
            val r = fetch("$jsonSearchPath?t=$kw&language=1&isremovehtml=1")
            // 接口本身都打不开：直接报错，别再去抓搜索页重复等一轮超时
            if (!r.ok) {
                throw IllegalStateException(
                    Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })),
                )
            }
            val arr = runCatching { JSONArray(r.body.trim()) }.getOrNull()
            if (arr != null && arr.length() > 0) {
                val out = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = o.optString("Title").trim()
                    val url = o.optString("Url").trim()
                    if (title.isBlank() || url.isBlank()) return@mapNotNull null
                    val latest = o.optString("LastPartName").trim()
                    Comic(
                        name = title,
                        url = abs(url.trimEnd('/')),
                        latest = if (latest == "暂无章节") "" else latest,
                        sourceId = id,
                        sourceName = name,
                    )
                }.distinctBy { it.url }
                if (out.isNotEmpty()) return out
            }
        }
        // 接口没这个关键词（或这个站没接口），退回抓搜索页
        val r = fetch(searchPath.replace("{kw}", kw))
        if (!r.ok) {
            throw IllegalStateException(
                Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })),
            )
        }
        return parseListPage(r.body)
    }

    override fun detail(comic: Comic): Comic {
        val r = fetch(pathOf(comic.url))
        if (!r.ok) return comic
        val s = r.body
        val name = TITLE.find(s)?.groupValues?.get(1)?.trim().orEmpty()
            .ifBlank {
                TITLE_TAG.find(s)?.groupValues?.get(1)?.trim()
                    ?.removeSuffix("漫畫")?.removeSuffix("漫画")?.trim().orEmpty()
            }
            .ifBlank { comic.name }
        val cover = COVER.find(s)?.groupValues?.get(1)?.trim().orEmpty()
            .ifBlank { COVER_BZ.find(s)?.groupValues?.get(1)?.trim().orEmpty() }
            .ifBlank { srcNear(s, "detail-main-cover") }
            .ifBlank { comic.cover }
        val author = AUTHOR.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val intro = INTRO.find(s)?.groupValues?.get(1)?.trim().orEmpty()
            .ifBlank { DESC.find(s)?.groupValues?.get(1)?.trim().orEmpty() }
        val latest = LATEST.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val status = STATUS.findAll(s).mapNotNull { m ->
            (m.groupValues.getOrNull(1) ?: "").ifBlank { m.groupValues.getOrNull(2) ?: "" }.trim()
        }.firstOrNull { it.isNotBlank() }.orEmpty()
        return comic.copy(
            name = name,
            cover = abs(cover),
            author = author.ifBlank { comic.author },
            intro = intro.ifBlank { comic.intro },
            latest = latest.ifBlank { comic.latest },
            status = status.ifBlank { comic.status },
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val r = Net.get(comic.url, referer = "$origin/", useCache = true)
        if (!r.ok) {
            throw IllegalStateException(
                Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })),
            )
        }
        val list = CHAP.findAll(r.body).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val href = m.groupValues[2]
            val inner = m.groupValues[3]
            // 只认目录项：class 里有 chapteritem，或内部带详情标题节点，或正文就是个话数
            var title = stripTags(inner).trim()
            // 漫画 BZ 的目录项是 <div class="detail-list-item"><a/></div>，
            // class 在 a 外面，括号里只剩个话数（1151），所以纯数字也算目录项
            val digitOnly = title.matches(Regex("""\d{1,5}"""))
            val isItem = attrs.contains("chapteritem") || inner.contains("detail-list-2-info") ||
                inner.contains("detail-list-item") || digitOnly ||
                title.contains("话") || title.contains("章") || title.contains("回") ||
                title.contains("卷") || href.matches(Regex("""^/m\d+/?$"""))
            if (!isItem) return@mapNotNull null
            if (digitOnly) title = "第${title}话"
            if (title.isBlank()) title = TITLE_ATTR.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            ComicChapter(
                name = title.ifBlank { href.trim('/') },
                url = abs(href),
                index = 0,
                sourceId = id,
            )
        }.distinctBy { it.url }.toList().reversed()
        return list.mapIndexed { i, c -> c.copy(index = i) }
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val chapterUrl = abs(chapter.url)
        val r = fetch(pathOf(chapter.url))
        if (!r.ok) {
            throw IllegalStateException(
                Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })),
            )
        }
        val imgs = JsPacker.imagesFromHtml(r.body)
        if (imgs.isEmpty()) throw IllegalStateException("这一话没解出图片，可能要先在浏览器里看")
        return ComicPages(chapter.name, imgs, chapterUrl, headers(chapterUrl))
    }

    /**
     * 搜索结果本身不带封面，这里并行去详情页把封面捞回来。
     * 只补前 12 个，剩下的保持占位图，靠用户点进去看详情。
     */
    override fun fillCovers(list: List<Comic>): List<Comic> {
        val need = list.withIndex().filter { it.value.cover.isBlank() }.take(coverFillLimit)
        if (need.isEmpty()) return list
        val out = list.toMutableList()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = need.map { (idx, c) -> idx to pool.submit(Callable { c to detail(c) }) }
            for ((idx, f) in jobs) {
                val got = runCatching { f.get(15, TimeUnit.SECONDS) }.getOrNull() ?: continue
                if (got.second.cover.isNotBlank()) out[idx] = got.second
            }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

    fun headers(referer: String = "$origin/"): Map<String, String> = mapOf(
        "Referer" to referer.ifBlank { "$origin/" },
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
    )

    override fun headers(): Map<String, String> = headers("$origin/")

    // ── 解析 ──

    private fun parseListPage(html: String): List<Comic> =
        if (listStyle == "bz") parseBzList(html) else parseDm5List(html)

    private fun parseDm5List(html: String): List<Comic> {
        val out = ArrayList<Comic>(40)
        val seen = HashSet<String>()
        for (part in html.split("<li")) {
            val block = part.take(1400)
            val href = HREF_COMIC.find(block)?.groupValues?.get(1) ?: continue
            if (!seen.add(href)) continue
            val cover = IMG_SRC.find(block)?.groupValues?.get(1).orEmpty()
            val title = (DM5_TITLE.find(block) ?: BZ_TITLE.find(block) ?: ANY_TITLE.find(block))
                ?.groupValues?.get(1)?.trim().orEmpty()
            if (title.isBlank()) continue
            val latest = LATEST_TIP.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            out.add(
                Comic(
                    name = title,
                    cover = abs(cover),
                    url = abs(href.trimEnd('/')),
                    latest = latest,
                    sourceId = id,
                    sourceName = name,
                ),
            )
        }
        return out
    }

    private fun parseBzList(html: String): List<Comic> {
        val out = ArrayList<Comic>(40)
        val seen = HashSet<String>()
        for (part in html.split("class=\"manga-i-list-item\"").drop(1)) {
            val block = part.take(1600)
            val href = BZ_HREF.find(block)?.groupValues?.get(1) ?: continue
            if (!seen.add(href)) continue
            val cover = IMG_SRC.find(block)?.groupValues?.get(1).orEmpty()
            val title = BZ_TITLE.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val sub = BZ_SUB.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (title.isBlank()) continue
            out.add(
                Comic(
                    name = title,
                    cover = abs(cover),
                    url = abs(href.trimEnd('/')),
                    intro = sub,
                    sourceId = id,
                    sourceName = name,
                ),
            )
        }
        return out
    }

    private fun srcNear(html: String, marker: String, window: Int = 220): String {
        val i = html.indexOf(marker)
        if (i < 0) return ""
        val seg = html.substring(maxOf(0, i - window), minOf(html.length, i + marker.length + window))
        return IMG_SRC.find(seg)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ")

    private fun abs(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        path.startsWith("/") -> origin + path
        else -> "$origin/$path"
    }

    private val IMG_SRC = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val HREF_COMIC = Regex("""href="(/(?:manhua-[\w\-]+|\d+bz)/)(?:\?[^"]*)?""")
    private val BZ_HREF = Regex("""href="(/[\w\-]+/)"""")
    private val DM5_TITLE = Regex("""manga-list-2-title"[^>]*>\s*<a[^>]*>([^<]+)<""")
    private val BZ_TITLE = Regex("""manga-i-list-title"[^>]*>([^<]+)<""")
    private val ANY_TITLE = Regex("""title="([^"]+)"""")
    private val LATEST_TIP = Regex("""manga-list-2-tip"[^>]*>\s*<a[^>]*>([^<]+)<""")
    private val BZ_SUB = Regex("""manga-i-list-subtitle"[^>]*>([^<]+)<""")

    private val CHAP = Regex(
        """<a\s+([^>]*?href=["']?(/m\d+/?)["']?[^>]*)>([\s\S]{0,1500}?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_ATTR = Regex("""title="([^"]*)"""")

    private val TITLE = Regex("""detail-main-info-title">([^<]*)<""")
    private val TITLE_TAG = Regex("""<title>\s*([^<_（(]+)""")
    private val COVER = Regex("""detail-main-cover"><img[^>]*src="([^"]+)"""")
    private val COVER_BZ = Regex("""<img[^>]+src="([^"]+)"[^>]*class="detail-bar-(?:bg|img)"""")
    private val AUTHOR = Regex("""detail-main-info-author">\s*作者[：:]\s*<a[^>]*>([^<]*)<""")
    private val INTRO = Regex("""class="detail-desc"\s*>([^<]*)<""")
    private val DESC = Regex("""<meta[^>]+name="[Dd]escription"[^>]+content="([^"]{0,400})"""")
    private val LATEST = Regex("""detail-list-title-2"[^>]*>([^<]*)<""")
    private val STATUS = Regex("""detail-list-title-1">([^<]*)<|detail-list-left">([^<]*)<""")
}

/* ─────────────────────── 拷贝漫画 ─────────────────────── */

/**
 * 拷贝漫画（Copymanga / MangaCopy）。
 *
 * 它家反爬主要看 UA 和 region 头，接口本身是公开的 v3：
 *   /api/v3/search/comic        搜索
 *   /api/v3/comic2/{path_word}  详情
 *   /api/v3/comic/{pw}/group/default/chapters  章节
 *   /api/v3/comic/{pw}/chapter2/{uuid}         图片
 *
 * 换了几个镜像，哪个通用哪个，连不上就返回空，不影响其它源。
 */
class CopymangaSource : ComicSource {
    override val id = "copymanga"
    override val name = "拷贝漫画"

    private val defaultHosts = listOf(
        "https://api.mangacopy.com",
        "https://api.copymanga.tv",
        "https://api.copymanga.site",
        "https://api.copy-manga.com",
        "https://api.copymanga.org",
    )

    @Volatile private var dynamicHosts: List<String> = emptyList()
    @Volatile private var activeHost: String? = null
    @Volatile private var lastDiscoveryMs: Long = 0L

    private fun candidateHosts(): List<String> {
        val list = ArrayList<String>()
        activeHost?.let { list.add(it) }
        list.addAll(dynamicHosts)
        list.addAll(defaultHosts)
        return list.distinct()
    }

    private fun discoverDynamicRoutes() {
        val now = System.currentTimeMillis()
        if (now - lastDiscoveryMs < 10 * 60 * 1000L && dynamicHosts.isNotEmpty()) return
        lastDiscoveryMs = now
        val probeList = (listOfNotNull(activeHost) + defaultHosts).distinct()
        for (h in probeList) {
            val r = Net.get(
                "$h/api/v3/system/network2",
                referer = "$h/",
                headers = mapOf(
                    "region" to "1",
                    "platform" to "3",
                    "version" to "3.0.0",
                    "Accept" to "application/json",
                ),
                fast = true,
            )
            if (r.ok) {
                val o = runCatching { JSONObject(r.body) }.getOrNull() ?: continue
                if (o.optInt("code") == 200) {
                    val res = o.optJSONObject("results") ?: continue
                    val lines = res.optJSONArray("lines") ?: continue
                    val found = ArrayList<String>()
                    for (i in 0 until lines.length()) {
                        val item = lines.optJSONObject(i) ?: continue
                        val u = item.optString("url")
                        if (u.isNotBlank() && u.startsWith("http")) {
                            found.add(u.trimEnd('/'))
                        }
                    }
                    if (found.isNotEmpty()) {
                        dynamicHosts = found
                        break
                    }
                }
            }
        }
    }

    private fun api(path: String, cache: Boolean = false): JSONObject? {
        val hosts = candidateHosts()
        for (h in hosts) {
            val r = Net.get(
                h + path,
                referer = "https://www.copymanga.site/",
                headers = mapOf(
                    "region" to "1",
                    "platform" to "3",
                    "version" to "3.0.0",
                    "webp" to "1",
                    "Accept" to "application/json",
                ),
                useCache = cache,
                fast = true,
            )
            if (r.ok) {
                val o = runCatching { JSONObject(r.body) }.getOrNull() ?: continue
                if (o.optInt("code") == 200) {
                    activeHost = h
                    return o.optJSONObject("results") ?: o
                }
            }
        }
        // 如果候选线路全灭，尝试拉取动态路由
        discoverDynamicRoutes()
        for (h in dynamicHosts) {
            if (h in hosts) continue
            val r = Net.get(
                h + path,
                referer = "https://www.copymanga.site/",
                headers = mapOf(
                    "region" to "1",
                    "platform" to "3",
                    "version" to "3.0.0",
                    "webp" to "1",
                    "Accept" to "application/json",
                ),
                useCache = cache,
                fast = true,
            )
            if (r.ok) {
                val o = runCatching { JSONObject(r.body) }.getOrNull() ?: continue
                if (o.optInt("code") == 200) {
                    activeHost = h
                    return o.optJSONObject("results") ?: o
                }
            }
        }
        return null
    }

    override fun home(): List<Comic> = runCatching {
        var o = api("/api/v3/comics?ordering=-popular&limit=36&offset=0&platform=1", cache = true)
        var arr = o?.optJSONArray("list")
        if (arr == null || arr.length() == 0) {
            o = api("/api/v3/comics?ordering=-datetime_updated&limit=30&offset=0&platform=1", cache = true)
            arr = o?.optJSONArray("list")
        }
        if (arr == null) return emptyList()
        (0 until arr.length()).mapNotNull { parseComic(arr.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val o = api("/api/v3/search/comic?q=$kw&limit=30&offset=0&platform=1", cache = true)
            ?: throw IllegalStateException("拷贝漫画连不上，换个源试试")
        val arr = o.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { parseComic(arr.optJSONObject(it)) }
    }

    private fun parseComic(o: JSONObject?): Comic? {
        if (o == null) return null
        val pw = o.optString("path_word")
        if (pw.isBlank()) return null
        val authors = o.optJSONArray("author")
        val authorText = buildList {
            if (authors != null) for (i in 0 until authors.length()) {
                authors.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString("/")
        return Comic(
            name = o.optString("name"),
            cover = o.optString("cover"),
            author = authorText,
            intro = o.optString("brief"),
            latest = "",
            url = "https://www.copymanga.site/comic/$pw",
            sourceId = id,
            sourceName = name,
            status = o.optJSONObject("status")?.optString("display_value").orEmpty(),
            pathWord = pw,
        )
    }

    override fun detail(comic: Comic): Comic {
        val pw = comic.pathWord.ifBlank { comic.url.substringAfterLast('/') }
        val o = api("/api/v3/comic2/$pw?platform=1", cache = true) ?: return comic
        val c = o.optJSONObject("comic") ?: return comic
        val authors = c.optJSONArray("author")
        val authorText = buildList {
            if (authors != null) for (i in 0 until authors.length()) {
                authors.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString("/")
        return comic.copy(
            name = c.optString("name").ifBlank { comic.name },
            cover = c.optString("cover").ifBlank { comic.cover },
            author = authorText.ifBlank { comic.author },
            intro = c.optString("brief").ifBlank { comic.intro },
            status = c.optJSONObject("status")?.optString("display_value") ?: comic.status,
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val pw = comic.pathWord.ifBlank { comic.url.substringAfterLast('/') }
        val o = api("/api/v3/comic/$pw/group/default/chapters?limit=1000&offset=0&platform=1", cache = true)
            ?: throw IllegalStateException("拷贝漫画章节拉不到")
        val arr = o.optJSONArray("list") ?: JSONArray()
        val out = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val uuid = c.optString("uuid")
            if (uuid.isBlank()) return@mapNotNull null
            ComicChapter(
                name = c.optString("name").ifBlank { "第 ${i + 1} 话" },
                url = uuid,
                index = i,
                sourceId = id,
            )
        }
        return out
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val pw = comic.pathWord.ifBlank { comic.url.substringAfterLast('/') }
        val o = api("/api/v3/comic/$pw/chapter2/${chapter.url}?platform=1")
            ?: throw IllegalStateException("这一话的图拉不到")
        val words = o.optJSONArray("words") ?: JSONArray()
        val imgs = (0 until words.length()).mapNotNull { i ->
            words.optJSONObject(i)?.optString("url")?.takeIf { it.isNotBlank() }
        }
        if (imgs.isEmpty()) throw IllegalStateException("这一话没解出图片")
        return ComicPages(chapter.name, imgs, "https://www.copymanga.site/", headers())
    }

    override fun headers(): Map<String, String> = mapOf(
        "Referer" to "https://www.mangacopy.com/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
    )
}

/* ─────────────────────── 栗子漫画 ─────────────────────── */

/**
 * 栗子漫画（Flutter 版）。
 * 【已停用说明】官方旧接口 ai.qsmm.fun 已被服务端软屏蔽，新版改用登录鉴权与加密通信。
 * 该源暂不默认启用，保留源码供后续适配新版鉴权。
 */
class LiziSource : ComicSource {
    override val id = "lizi"
    override val name = "栗子漫画"

    /** 已知接口主机，按顺序探活 */
    private val knownHosts = listOf("http://ai.qsmm.fun", "http://ai.xajtl.com")

    /** 官方换域名后，通常会把新地址写进这份配置，这里跟着走 */
    private val configUrl =
        "https://lz-1382057604.cos.ap-hongkong.myqcloud.com/" +
            "5A6F4D2B7E9A1C3D8F0B2E4A6C8E0D2F4B6A8C0E2D4F6A8B0C2E4D6F8A0C2E4B.json"

    @Volatile
    private var remoteHost: String? = null

    @Volatile
    private var probed = false

    @Volatile
    private var base: String = knownHosts.first()

    /** 候选主机：远程配置的域名排最前，其次内置的几台 */
    private fun hosts(): List<String> {
        if (!probed) {
            probed = true
            remoteHost = runCatching { readConfigHost() }.getOrNull()
        }
        return (listOfNotNull(remoteHost) + knownHosts).distinct()
    }

    /** 官方配置是 AES-256-ECB 加密的 JSON，密钥在官方包里，解出来只有一个 domain */
    private fun readConfigHost(): String? {
        val r = Net.get(configUrl, headers = mapOf("Accept" to "application/json"))
        if (!r.ok) return null
        val raw = android.util.Base64.decode(r.body.trim(), android.util.Base64.DEFAULT)
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        val key = javax.crypto.spec.SecretKeySpec(
            "f8d992c74b29491d8a3e3fd5f07389d8".toByteArray(Charsets.UTF_8), "AES"
        )
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key)
        val plain = String(cipher.doFinal(raw), Charsets.UTF_8)
        val m = Regex("\"domain\"\\s*:\\s*\"([^\"]+)\"").find(plain) ?: return null
        val host = m.groupValues[1].trim().trimEnd('/')
        return host.takeIf { it.startsWith("http") }
    }

    /** 官方包的协议常量，不是谁的账号凭证 */
    private val signPrefix = "q2sIObYXCp2uBZgCNBlY93J3z67hK0wS"

    /** 图片线路，detail 里的 cover 一般已经带好了，探测兜底用 */
    private val imgLines = listOf(
        "https://cdn.lzimg.xyz",
        "https://i.lzimg.xyz",
        "https://cf-1.imgio.club",
        "https://t1.imge.store",
    )

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 拼签名：官方要求 path + 时间戳 + 协议常量的 md5 */
    private fun signed(path: String): String {
        val cut = path.split("?", limit = 2)
        val p = cut[0]
        val query = ArrayList<String>(cut.getOrNull(1)?.split("&")?.filter { it.isNotBlank() } ?: emptyList())
        fun addDefault(k: String, v: String) {
            if (query.none { it.substringBefore("=") == k }) {
                query.add("$k=" + URLEncoder.encode(v, "UTF-8"))
            }
        }
        if (p == "/app/api/home/data" || p == "/app/api/configv2") {
            addDefault("packname", "com.jy.zyds")
            addDefault("appsign256", "")
        }
        if (p == "/app/api/configv2") addDefault("platform", "ios")
        val t = (System.currentTimeMillis() / 1000).toString()
        query.add("lzsign=" + md5(signPrefix + p + t))
        query.add("t=$t")
        return p + "?" + query.joinToString("&")
    }

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Dart/3.5 (dart:io)",
        "Referer" to "$base/",
    )

    /** 拉接口：两台主机轮着试，code 必须是 201，返回 data */
    private fun api(path: String): JSONObject? {
        val all = hosts()
        val order = listOf(base) + all.filter { it != base }
        for (b in order) {
            val r = runCatching {
                Net.get(b + signed(path), headers = apiHeaders() + ("Referer" to "$b/"))
            }.getOrNull() ?: continue
            if (!r.ok) continue
            val o = runCatching { JSONObject(r.body) }.getOrNull() ?: continue
            val code = o.optInt("code", -1)
            if (code != 201 && code != 200) continue
            base = b
            return o.optJSONObject("data") ?: o
        }
        return null
    }

    override fun home(): List<Comic> {
        api("/app/api/home/data")?.let { d ->
            val list = comics(d)
            if (list.isNotEmpty()) return list.take(80)
        }
        api("/app/api/rank/list")?.let { return comics(it).take(80) }
        return emptyList()
    }

    override fun search(keyword: String): List<Comic> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val d = api("/app/api/search/full?q=$enc")
            ?: throw IllegalStateException("栗子漫画连不上，换个源试试")
        return comics(d)
    }

    override fun detail(comic: Comic): Comic {
        val d = api("/app/api/v2/detail/${comic.pathWord}") ?: return comic
        val one = comics(d).firstOrNull() ?: return comic
        return comic.copy(
            name = one.name.ifBlank { comic.name },
            cover = one.cover.ifBlank { comic.cover },
            author = one.author.ifBlank { comic.author },
            intro = one.intro.ifBlank { comic.intro },
            status = one.status.ifBlank { comic.status },
            latest = one.latest.ifBlank { comic.latest },
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val d = api("/app/api/v2/detail/${comic.pathWord}")
            ?: throw IllegalStateException("栗子漫画章节拉不到")
        val arr = d.optJSONArray("chapters") ?: findArray(d) { o ->
            s(o, "id", "chapter_id", "cid").isNotBlank() && s(o, "name", "title").isNotBlank()
        } ?: throw IllegalStateException("没解析到话数，接口可能改了")
        val out = ArrayList<ComicChapter>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cid = s(o, "id", "chapter_id", "cid")
            val nm = s(o, "name", "title", "chapter_name")
            if (nm.isBlank()) continue
            out.add(ComicChapter(nm, cid, i, id, s(o, "cover", "cover_url", "img", "image")))
        }
        // 官方顺序是按 id 排的，接口本身已经排好，保持原序
        return out
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        // ① 先要官方顺序（有 chapter_id 就带上，能拿到真实页序）
        val v3q = "/app/api/chapter/v3/${comic.pathWord}?chapter_id=${chapter.url}"
        api(v3q)?.let { d ->
            val pics = d.optJSONArray("pics")
            if (pics != null && pics.length() > 0) {
                val out = ArrayList<String>(pics.length())
                for (i in 0 until pics.length()) {
                    val raw = pics.optString(i)
                    if (raw.isNotBlank()) absPic(raw)?.let { out.add(it) }
                }
                if (out.isNotEmpty()) return ComicPages(chapter.name, out, "$base/", headers())
            }
        }
        // ② 退回 cover 目录推导：同目录 1..N
        val cover = chapter.cover
        if (cover.isNotBlank()) {
            val m = Regex("^(.*)/(\\d+)(\\.[A-Za-z0-9]+)$").find(cover)
            if (m != null) {
                val dir = m.groupValues[1]
                val ext = m.groupValues[3]
                val list = probePages(dir, ext)
                if (list.isNotEmpty()) return ComicPages(chapter.name, list, "$base/", headers())
            }
        }
        throw IllegalStateException("这一话没解出图片，可能要在官方 App 里看")
    }

    /** 官方 pics 可能是完整地址、站内路径，或者带 $jp$ 标记 */
    private fun absPic(raw: String): String? {
        var t = raw.trim()
        if (t.isBlank()) return null
        if (t.startsWith("\$jp\$")) {
            t = t.removePrefix("\$jp\$").removePrefix("cf")
        }
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        if (t.startsWith("//")) return "https:" + t
        if (t.startsWith("/")) return imgLines.first() + t
        return null
    }

    /**
     * 目录探测：同目录 1..N 按批并发探，连续三次没有就收。
     * CDN 对不存在的页会回 200 + 一张 82 字节的占位图，所以要按大小判，不能只看状态码。
     */
    private fun probePages(dir: String, ext: String, max: Int = 150): List<String> {
        val found = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        var start = 1
        var misses = 0
        try {
            while (start <= max && misses < 3) {
                val end = minOf(start + 11, max)
                val tasks = (start..end).map { i ->
                    val u = "$dir/$i$ext"
                    pool.submit(java.util.concurrent.Callable { if (exists(u)) u else null })
                }
                for (f in tasks) {
                    val u = runCatching { f.get() }.getOrNull()
                    if (u != null) {
                        found.add(u)
                        misses = 0
                    } else {
                        misses++
                    }
                }
                start = end + 1
            }
        } finally {
            pool.shutdown()
        }
        return found.sortedBy { it.substringAfterLast('/').substringBefore('.').toIntOrNull() ?: 0 }
    }

    private fun exists(url: String): Boolean {
        val (code, len) = Net.probe(url, headers())
        if (code == 206) return true
        if (code != 200) return false
        return len < 0 || len >= 1024
    }

    override fun headers(): Map<String, String> = mapOf(
        "Referer" to "$base/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
    )

    // ── 兼容解析 ──

    private fun s(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun findArray(root: JSONObject, ok: (JSONObject) -> Boolean): JSONArray? {
        var best: JSONArray? = null
        fun walk(v: Any?, depth: Int) {
            if (depth > 5) return
            when (v) {
                is JSONObject -> v.keys().forEach { k -> walk(v.opt(k), depth + 1) }
                is JSONArray -> {
                    if (v.length() > 0) {
                        val sample = (0 until minOf(v.length(), 3)).mapNotNull { v.optJSONObject(it) }
                        if (sample.isNotEmpty() && sample.any(ok) && (best == null || v.length() > best!!.length())) {
                            best = v
                        }
                    }
                    for (i in 0 until minOf(v.length(), 2)) walk(v.opt(i), depth + 1)
                }
            }
        }
        walk(root, 0)
        return best
    }

    private fun comics(root: JSONObject): List<Comic> {
        val best = findArray(root) { o -> s(o, "name", "title", "comic_name").isNotBlank() } ?: return emptyList()
        val out = ArrayList<Comic>(best.length())
        for (i in 0 until best.length()) {
            val o = best.optJSONObject(i) ?: continue
            val nm = s(o, "name", "title", "comic_name")
            val cid = s(o, "id", "comic_id", "comicId", "cid")
            if (nm.isBlank() || cid.isBlank()) continue
            out.add(
                Comic(
                    name = nm,
                    cover = s(o, "cover", "cover_url", "img", "image", "thumb", "pic"),
                    author = s(o, "author", "author_name", "authors"),
                    intro = s(o, "intro", "description", "brief", "desc", "summary"),
                    latest = s(o, "last_chapter", "lastChapter", "new_chapter", "latest"),
                    url = "$base/comic/$cid",
                    sourceId = id,
                    sourceName = name,
                    status = s(o, "status", "state", "serial_status"),
                    pathWord = cid,
                )
            )
        }
        return out
    }
}

/* ─────────────────────── 源表 + 门面 ─────────────────────── */

object ComicRepo {

    val sources: List<ComicSource> = listOf(
        Dm5FamilySource(
            id = "dm5",
            name = "动漫屋",
            base = "https://m.dm5.com",
            searchPath = "/search?title={kw}",
            homePath = "/manhua-list/",
            altBases = listOf("https://www.dm5.com"),
        ),
        Dm5FamilySource(
            id = "manhuaren",
            name = "漫画人",
            base = "https://www.manhuaren.com",
            searchPath = "/search/?keywords={kw}",
            homePath = "/manhua-new/",
            altBases = listOf("https://m.manhuaren.com"),
        ),
        Dm5FamilySource(
            id = "mangabz",
            name = "漫画BZ",
            base = "https://www.mangabz.com",
            searchPath = "/search?title={kw}",
            homePath = "/manga-list/",
            altBases = listOf("https://m.mangabz.com"),
            listStyle = "bz",
        ),
        CopymangaSource(),
    )

    fun source(id: String): ComicSource =
        sources.firstOrNull { it.id == id } ?: sources.first()

    fun byIdOrNull(id: String): ComicSource? = sources.firstOrNull { it.id == id }

    val pool = Executors.newFixedThreadPool(4)
    private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markFailed(sourceId: String, durationMs: Long = 60 * 1000L) {
        failedUntil[sourceId] = System.currentTimeMillis() + durationMs
    }

    fun isBlacklisted(sourceId: String): Boolean {
        val until = failedUntil[sourceId] ?: return false
        if (System.currentTimeMillis() > until) {
            failedUntil.remove(sourceId)
            return false
        }
        return true
    }

    /** 热门：按源并发取首页 */
    fun home(sourceId: String): List<Comic> = source(sourceId).home()

    /**
     * 多源搜索：每个源并发跑，返回每个源各自的结果和状态。
     * 单个源超时/失败不影响其它源，单源最多等 8 秒。
     */
    fun searchAll(keyword: String, ids: List<String>): List<ComicHit> {
        val picked = sources.filter { it.id in ids && !isBlacklisted(it.id) }.ifEmpty { sources }
        val jobs = picked.map { src ->
            val t0 = System.currentTimeMillis()
            pool.submit(Callable {
                runCatching { src.search(keyword) }
                    .fold(
                        onSuccess = { ComicHit(src.id, src.name, it, true, System.currentTimeMillis() - t0) },
                        onFailure = {
                            val msg = it.message ?: "失败"
                            if (msg.contains("502") || msg.contains("504") || msg.contains("拒绝")) {
                                markFailed(src.id, 60_000L)
                            }
                            ComicHit(
                                src.id, src.name, emptyList(), false,
                                System.currentTimeMillis() - t0,
                                msg,
                            )
                        },
                    )
            })
        }
        return jobs.map { f ->
            runCatching { f.get(8, TimeUnit.SECONDS) }.getOrElse {
                val i = jobs.indexOf(f)
                ComicHit(picked[i].id, picked[i].name, emptyList(), false, 8000, "超时 (8s)")
            }
        }.sortedByDescending { it.list.size }
    }

    /** 把多个源的结果合并去重（同名取先到的，标注来源） */
    fun merge(hits: List<ComicHit>): List<Comic> {
        val out = ArrayList<Comic>()
        val seen = HashSet<String>()
        for (h in hits) {
            for (c in h.list) {
                val key = c.name.replace(Regex("[\\s·:：\\-—_()（）\\[\\]]"), "").lowercase()
                if (key.isBlank() || !seen.add(key)) continue
                out.add(c)
            }
        }
        return out
    }

    fun detail(comic: Comic): Comic = source(comic.sourceId).detail(comic)

    fun chapters(comic: Comic): List<ComicChapter> = source(comic.sourceId).chapters(comic)

    /** 这一话的图地址列表：留几话在内存里，来回翻话不用重新请求 */
    private val pagesCache = object : LinkedHashMap<String, ComicPages>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ComicPages>?): Boolean = size > 8
    }

    private val prefetchPool = Executors.newFixedThreadPool(2)

    fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val key = comic.sourceId + "|" + comic.url + "|" + chapter.url
        synchronized(pagesCache) { pagesCache[key] }?.let { return it }
        val got = source(comic.sourceId).pages(comic, chapter)
        synchronized(pagesCache) { pagesCache[key] = got }
        return got
    }

    /** 后台把下一话的图地址先拿回来，翻页时就少等一次往返 */
    fun prefetchPages(comic: Comic, chapter: ComicChapter?) {
        chapter ?: return
        val key = comic.sourceId + "|" + comic.url + "|" + chapter.url
        synchronized(pagesCache) { if (pagesCache.containsKey(key)) return }
        runCatching { prefetchPool.execute { runCatching { pages(comic, chapter) } } }
    }

    fun imageHeaders(comic: Comic? = null): Map<String, String> =
        if (comic == null) sources.first().headers() else source(comic.sourceId).headers()
}
