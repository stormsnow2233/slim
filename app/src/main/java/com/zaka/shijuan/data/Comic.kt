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
 * 它的接口主机藏在一份加密配置里（AES-256-ECB，密钥在官方包里），配置会换域名，
 * 旧域名随时可能被下线。这里把已出现过的两台主机轮着探，哪台通就记下来。
 *
 * 接口全部要签名，不带一定 403：
 *   lzsign = md5(signPrefix + path + t)，t 十位秒时间戳，signPrefix 是官方包的协议常量。
 *   /app/api/home/data 和 /app/api/configv2 还要补 packname、appsign256。
 *
 *   /app/api/home/data          首页（多区块）
 *   /app/api/rank/list          榜单
 *   /app/api/search/full?q=     搜索
 *   /app/api/v2/detail/{id}     详情 + 话数（每话带 cover）
 *   /app/api/chapter/v3/{id}    官方图片顺序（要带 chapter_id）
 *
 * 图片：话数里的 cover 形如 https://图床/目录/1.jpg，同一目录下 1..N.jpg 就是这一话的全部页，
 * 所以没有官方 pics 也能靠这个规律把整话拿全。
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
