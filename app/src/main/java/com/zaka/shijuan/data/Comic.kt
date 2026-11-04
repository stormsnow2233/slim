package com.zaka.shijuan.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

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
    /** 搜索路径模板，{kw} 会被替换 */
    private val searchPath: String,
    /** 首页 / 排行页；为空就用站点首页 */
    private val homePath: String = "",
) : ComicSource {

    private val origin: String = base

    override fun home(): List<Comic> {
        val paths = listOfNotNull(homePath.ifBlank { null }, "/")
        for (p in paths) {
            val r = Net.get(base + p, referer = "$origin/", useCache = true)
            if (!r.ok) continue
            val out = parseList(r.body)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val r = Net.get(base + searchPath.replace("{kw}", kw), referer = "$origin/", useCache = true)
        if (!r.ok) throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        return parseList(r.body)
    }

    override fun detail(comic: Comic): Comic {
        val r = Net.get(comic.url, referer = "$origin/", useCache = true)
        if (!r.ok) return comic
        val s = r.body
        val name = TITLE.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val cover = COVER.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val author = AUTHOR.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val intro = INTRO.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val latest = LATEST.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        val status = STATUS.find(s)?.groupValues?.get(1)?.trim().orEmpty()
        return comic.copy(
            name = name.ifBlank { comic.name },
            cover = abs(cover).ifBlank { comic.cover },
            author = author.ifBlank { comic.author },
            intro = intro.ifBlank { comic.intro },
            latest = latest.ifBlank { comic.latest },
            status = status.ifBlank { comic.status },
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val r = Net.get(comic.url, referer = "$origin/", useCache = true)
        if (!r.ok) throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        val list = CHAP_BLOCK.findAll(r.body).mapNotNull { m ->
            val href = m.groupValues[1]
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            // 只认目录项：class 里有 chapteritem，或内部带详情标题节点
            val isItem = attrs.contains("chapteritem") || inner.contains("detail-list-2-info")
            if (!isItem) return@mapNotNull null
            // 站点的 title 属性常常是「试看版」这种备注，正文文字才是「第123话」，优先用正文
            val innerText = stripTags(inner).trim()
            val name = innerText.ifBlank {
                TITLE_ATTR.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
            }
            ComicChapter(
                name = name.ifBlank { href.trim('/') },
                url = abs(href),
                index = 0,
                sourceId = id,
            )
        }.distinctBy { it.url }.toList().reversed()
        return list.mapIndexed { i, c -> c.copy(index = i) }
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val r = Net.get(chapter.url, referer = "$origin/", useCache = true)
        if (!r.ok) throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        val imgs = JsPacker.imagesFromHtml(r.body)
        if (imgs.isEmpty()) throw IllegalStateException("这一话没解出图片，可能要先在浏览器里看")
        return ComicPages(chapter.name, imgs, "$origin/", headers())
    }

    override fun headers(): Map<String, String> = mapOf(
        "Referer" to "$origin/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
    )

    // ── 解析 ──

    private fun parseList(html: String): List<Comic> {
        val out = ArrayList<Comic>(40)
        val seen = HashSet<String>()
        for (m in BOOK.findAll(html)) {
            val path = m.groupValues[1]
            val title = m.groupValues[2].trim()
            val cover = m.groupValues[3].trim()
            if (path in seen) continue
            if (title.isBlank() && cover.isBlank()) continue
            seen.add(path)
            out.add(
                Comic(
                    name = title.ifBlank { path.trim('/').removePrefix("manhua-") },
                    cover = abs(cover),
                    url = abs(path),
                    sourceId = id,
                    sourceName = name,
                )
            )
        }
        return out
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

    private val BOOK = Regex(
        """<a href="(/manhua-[^"]+/)"(?:\s+title="([^"]*)")?[^>]*>\s*""" +
            """(?:<li>[\s\S]{0,400}?)?<img[^>]*src="([^"]+\.(?:jpg|jpeg|png|webp))"""",
        RegexOption.IGNORE_CASE,
    )

    private val CHAP_BLOCK = Regex(
        """<a\s+href="(/m\d+/)"([^>]*)>([\s\S]{0,900}?)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_ATTR = Regex("""title="([^"]*)"""")

    private val TITLE = Regex("""detail-main-info-title">([^<]*)<""")
    private val COVER = Regex("""detail-main-cover"><img[^>]*src="([^"]+)"""")
    private val AUTHOR = Regex("""detail-main-info-author">\s*作者[：:]\s*<a[^>]*>([^<]*)<""")
    private val INTRO = Regex("""class="detail-desc"\s*>([^<]*)<""")
    private val LATEST = Regex("""detail-list-title-2"[^>]*>([^<]*)<""")
    private val STATUS = Regex("""detail-list-title-1">([^<]*)<""")
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

    private val hosts = listOf(
        "https://api.copymanga.site",
        "https://api.copymanga.tv",
        "https://api.mangacopy.com",
    )

    private fun api(path: String, cache: Boolean = false): JSONObject? {
        for (h in hosts) {
            val r = Net.get(
                h + path,
                referer = "https://www.copymanga.site/",
                headers = mapOf(
                    "region" to "1",
                    "platform" to "1",
                    "version" to "2.2.0",
                    "webp" to "1",
                ),
                useCache = cache,
            )
            if (r.ok) {
                val o = runCatching { JSONObject(r.body) }.getOrNull() ?: continue
                if (o.optInt("code") == 200) return o.optJSONObject("results") ?: o
            }
        }
        return null
    }

    override fun home(): List<Comic> = runCatching {
        val o = api("/api/v3/comics?ordering=-popular&limit=36&offset=0&platform=1", cache = true)
            ?: return emptyList()
        val arr = o.optJSONArray("list") ?: JSONArray()
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
        "Referer" to "https://www.copymanga.site/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
    )
}

/* ─────────────────────── 栗子漫画 ─────────────────────── */

/**
 * 栗子漫画（Flutter 版）。
 *
 * 接口是从 APK 里挖出来的：官方把接口主机写在一个加密配置里，
 * 解开后是 ai.qsmm.fun，路径前缀 /app/api/。
 *   /app/api/home/data            首页
 *   /app/api/rank/list            榜单
 *   /app/api/search/full?q=       搜索
 *   /app/api/v2/detail/{id}       详情
 *   /app/api/chapter/v3/{id}      章节 + 图片
 *
 * 它没有公开文档，返回结构按字段名做了兼容解析（name/title、cover/img 都认），
 * 所以万一官方改字段，这里还能兜住大部分。
 */
class LiziSource : ComicSource {
    override val id = "lizi"
    override val name = "栗子漫画"

    private val base = "http://ai.qsmm.fun"

    private fun get(path: String): JSONObject? {
        val r = Net.get(
            base + path,
            headers = mapOf("Accept" to "application/json"),
            useCache = true,
        )
        if (!r.ok) return null
        return runCatching { JSONObject(r.body) }.getOrNull()
    }

    override fun home(): List<Comic> {
        val o = get("/app/api/rank/list") ?: get("/app/api/home/data") ?: return emptyList()
        return comics(o).take(60)
    }

    override fun search(keyword: String): List<Comic> {
        val enc = URLEncoder.encode(keyword, "UTF-8")
        val o = get("/app/api/search/full?q=$enc")
            ?: throw IllegalStateException("栗子漫画连不上，换个源试试")
        return comics(o)
    }

    override fun detail(comic: Comic): Comic {
        val o = get("/app/api/v2/detail/${comic.pathWord}") ?: return comic
        val c = comics(o).firstOrNull() ?: return comic
        return comic.copy(
            name = c.name.ifBlank { comic.name },
            cover = c.cover.ifBlank { comic.cover },
            author = c.author.ifBlank { comic.author },
            intro = c.intro.ifBlank { comic.intro },
            status = c.status.ifBlank { comic.status },
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val o = get("/app/api/chapter/v3/${comic.pathWord}")
            ?: throw IllegalStateException("栗子漫画章节拉不到")
        val arr = arrays(o)
            .filter { it.length() > 0 && it.optJSONObject(0)?.let { o1 -> nameOf(o1).isNotBlank() } == true }
            .maxByOrNull { it.length() }
            ?: throw IllegalStateException("没解析到话数，接口可能改了")
        val out = (0 until arr.length()).mapNotNull { i ->
            val it = arr.optJSONObject(i) ?: return@mapNotNull null
            val cid = idOf(it)
            val nm = nameOf(it)
            if (nm.isBlank()) return@mapNotNull null
            ComicChapter(nm, cid.ifBlank { i.toString() }, i, id)
        }
        return out
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        // 官方没文档，这几种路径形状都试一遍，哪条能出图用哪条
        val tries = listOf(
            "/app/api/chapter/v3/${comic.pathWord}?chapter_id=${chapter.url}",
            "/app/api/chapter/v3/${comic.pathWord}/${chapter.url}",
            "/app/api/chapter/v3/${chapter.url}",
            "/app/api/chapter/v3/${comic.pathWord}",
        )
        for (p in tries) {
            val o = get(p) ?: continue
            val imgs = imageList(o)
            if (imgs.isNotEmpty()) return ComicPages(chapter.name, imgs, "$base/", headers())
        }
        throw IllegalStateException("这一话没解出图片，可能要在官方 App 里看")
    }

    override fun headers(): Map<String, String> = mapOf(
        "Referer" to "$base/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
    )

    // ── 兼容解析：不认识的字段名也尽量认出来 ──

    private fun arrays(node: Any?): List<JSONArray> {
        val out = ArrayList<JSONArray>()
        fun walk(v: Any?, depth: Int) {
            if (depth > 6) return
            when (v) {
                is JSONObject -> v.keys().forEach { k -> walk(v.opt(k), depth + 1) }
                is JSONArray -> {
                    out.add(v)
                    for (i in 0 until minOf(v.length(), 3)) walk(v.opt(i), depth + 1)
                }
            }
        }
        walk(node, 0)
        return out
    }

    private fun s(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun idOf(o: JSONObject): String = s(o, "id", "comic_id", "comicId", "cid", "book_id")
    private fun nameOf(o: JSONObject): String = s(o, "name", "title", "comic_name", "comicName", "book_name")
    private fun coverOf(o: JSONObject): String =
        s(o, "cover", "cover_url", "coverUrl", "img", "image", "thumb", "pic", "pic_url")

    private fun comics(root: JSONObject): List<Comic> {
        val best = arrays(root)
            .filter { a -> a.length() > 0 && (0 until minOf(a.length(), 3)).any { nameOf(a.optJSONObject(it) ?: JSONObject()).isNotBlank() } }
            .maxByOrNull { it.length() } ?: return emptyList()
        val out = ArrayList<Comic>(best.length())
        for (i in 0 until best.length()) {
            val o = best.optJSONObject(i) ?: continue
            val nm = nameOf(o)
            val cid = idOf(o)
            if (nm.isBlank()) continue
            out.add(
                Comic(
                    name = nm,
                    cover = coverOf(o),
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

    private fun imageList(root: JSONObject): List<String> {
        val out = ArrayList<String>()
        // 先找「数组里是字符串」的，再找「数组里是对象且带图地址」的
        for (a in arrays(root)) {
            if (a.length() == 0) continue
            val first = a.opt(0)
            if (first is String && first.startsWith("http")) {
                for (i in 0 until a.length()) a.optString(i).takeIf { it.startsWith("http") }?.let { out.add(it) }
            } else if (first is JSONObject) {
                val urls = (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    s(o, "url", "img", "image", "src", "path", "pic", "picture").takeIf { it.startsWith("http") }
                }
                if (urls.size > out.size) { out.clear(); out.addAll(urls) }
            }
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
            homePath = "/manhua-rank/",
        ),
        Dm5FamilySource(
            id = "manhuaren",
            name = "漫画人",
            base = "https://www.manhuaren.com",
            searchPath = "/search/?keywords={kw}",
            homePath = "", // 首页推荐位就是榜单
        ),
        CopymangaSource(),
        LiziSource(),
    )

    fun source(id: String): ComicSource =
        sources.firstOrNull { it.id == id } ?: sources.first()

    fun byIdOrNull(id: String): ComicSource? = sources.firstOrNull { it.id == id }

    /** 热门：按源并发取首页 */
    fun home(sourceId: String): List<Comic> = source(sourceId).home()

    /**
     * 多源搜索：每个源并发跑，返回每个源各自的结果和状态。
     * 单个源超时/失败不影响其它源。
     */
    fun searchAll(keyword: String, ids: List<String>): List<ComicHit> {
        val picked = sources.filter { it.id in ids }.ifEmpty { sources }
        return picked.map { src ->
            val t0 = System.currentTimeMillis()
            runCatching { src.search(keyword) }
                .fold(
                    onSuccess = { ComicHit(src.id, src.name, it, true, System.currentTimeMillis() - t0) },
                    onFailure = {
                        ComicHit(
                            src.id, src.name, emptyList(), false,
                            System.currentTimeMillis() - t0,
                            it.message ?: "失败",
                        )
                    },
                )
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

    fun pages(comic: Comic, chapter: ComicChapter): ComicPages =
        source(comic.sourceId).pages(comic, chapter)

    fun imageHeaders(comic: Comic? = null): Map<String, String> =
        if (comic == null) sources.first().headers() else source(comic.sourceId).headers()
}
