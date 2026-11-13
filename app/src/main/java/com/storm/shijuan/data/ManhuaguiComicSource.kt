package com.storm.shijuan.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * 漫画柜官方源（Manhuagui）
 * 拥有最全日本漫画（包括《孤独摇滚！》全集正篇、《古见同学》等）。
 * 章节正文图片字典使用 LZString 压缩封装，客户端毫秒级解密。
 */
class ManhuaguiComicSource : ComicSource {
    override val id: String = "manhuagui"
    override val name: String = "漫画柜"

    private val base: String = "https://m.manhuagui.com"
    private val imageServers = listOf("https://i.hamreus.com", "https://cf.hamreus.com")

    private fun fetch(url: String, referer: String = "$base/", fast: Boolean = false): Net.Resp {
        val fullUrl = if (url.startsWith("http")) url else "$base$url"
        return Net.get(
            fullUrl,
            referer = referer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            ),
            useCache = true,
            fast = fast,
        )
    }

    override fun home(): List<Comic> {
        val r = fetch("/update/", fast = true)
        if (!r.ok) return emptyList()
        return parseComics(r.body)
    }

    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val r = fetch("/s/$kw.html", fast = true)
        if (!r.ok) {
            throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        }
        val comics = parseComics(r.body)
        if (comics.isEmpty()) {
            throw IllegalStateException("未在漫画柜搜到与「$keyword」相关的作品")
        }
        return comics
    }

    private fun parseComics(html: String): List<Comic> {
        val list = mutableListOf<Comic>()
        val itemRegex = Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
        val linkRegex = Regex("""<a[^>]+href="(/comic/\d+/?)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

        for (item in itemRegex.findAll(html)) {
            val content = item.groupValues[1]
            val linkMatch = linkRegex.find(content) ?: continue
            val href = linkMatch.groupValues[1]
            val inner = linkMatch.groupValues[2]

            var title = Regex("""<h3[^>]*>([\s\S]*?)</h3>""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1).orEmpty()
            if (title.isBlank()) {
                title = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1).orEmpty()
            }
            if (title.isBlank()) {
                title = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE).find(linkMatch.value)?.groupValues?.get(1).orEmpty()
            }
            title = stripHtml(title).removePrefix("《").removeSuffix("》").trim()
            if (title.isBlank()) continue

            val cover = Regex("""(?:src|data-src)="([^"]+)"\s*(?:alt|class)""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1).orEmpty()

            val author = Regex("""(?:作者|原著)[：:]\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.get(1)?.trim().orEmpty()

            val latest = Regex("""(?:更新至|最新)[：:]\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.get(1)?.trim().orEmpty()

            val cleanUrl = if (href.startsWith("http")) href else "$base$href"
            list.add(
                Comic(
                    name = title,
                    url = cleanUrl,
                    cover = absUrl(cover),
                    author = author,
                    latest = latest,
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

        val name = Regex("""<div[^>]*class="[^"]*book-title[^"]*"[^>]*>\s*<h1>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.let { stripHtml(it) }?.removePrefix("《")?.removeSuffix("》")
            ?: Regex("""<h1[^>]*>([\s\S]*?)</h1>""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.let { stripHtml(it) }
            ?: comic.name

        val cover = Regex("""<p[^>]*class="[^"]*hcover[^"]*"[^>]*>\s*<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)
            ?: Regex("""<img[^>]+src="([^"]+cover[^"]*)"""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
            ?: comic.cover

        val author = Regex("""(?:作者|原著)[：:]\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.trim().orEmpty()

        val intro = Regex("""<div[^>]*id="intro-all"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.let { stripHtml(it) }
            ?: Regex("""<div[^>]*class="[^"]*book-intro[^"]*"[^>]*>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
                .find(s)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()

        val status = if (s.contains("已完结")) "已完结" else if (s.contains("连载中")) "连载中" else comic.status

        return comic.copy(
            name = name,
            cover = absUrl(cover),
            author = author.ifBlank { comic.author },
            intro = intro.ifBlank { comic.intro },
            status = status,
            detailed = true,
        )
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val r = fetch(comic.url)
        if (!r.ok) {
            throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        }
        var s = r.body

        // 检查是否有隐藏分卷 (#__VIEWSTATE)
        val viewState = Regex("""id="__VIEWSTATE"[^>]+value="([^"]+)"""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)
        if (!viewState.isNullOrBlank()) {
            val decoded = runCatching { LZString.decompressFromBase64(viewState) }.getOrNull()
            if (!decoded.isNullOrBlank()) {
                s += "\n" + decoded
            }
        }

        // 提取全量章节 <a href="/comic/32263/917610.html"><span>第99话</span></a>
        val cid = Regex("""/comic/(\d+)""").find(comic.url)?.groupValues?.get(1).orEmpty()
        val chRegex = if (cid.isNotBlank()) {
            Regex("""<a[^>]+href="(/comic/$cid/\d+\.html)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        } else {
            Regex("""<a[^>]+href="(/comic/\d+/\d+\.html)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        }

        val rawList = mutableListOf<ComicChapter>()
        val seen = mutableSetOf<String>()

        for (m in chRegex.findAll(s)) {
            val href = m.groupValues[1]
            val inner = m.groupValues[2]
            var title = stripHtml(inner).trim()
            if (title.isBlank()) {
                title = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)?.trim().orEmpty()
            }
            val absUrl = if (href.startsWith("http")) href else "$base$href"
            if (!seen.add(absUrl)) continue
            rawList.add(
                ComicChapter(
                    name = title.ifBlank { href.substringAfterLast('/').substringBefore('.') },
                    url = absUrl,
                    index = 0,
                    sourceId = id,
                )
            )
        }

        if (rawList.isEmpty()) {
            throw IllegalStateException("未在漫画柜获取到任何章节列表")
        }

        // 漫画柜网页上通常是倒序（最新一话在最前），使用智能规范化引擎自适应校准为 1..N 正序
        return ComicRepo.normalizeChapters(rawList)
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else "$base${chapter.url}"
        val r = fetch(chapterUrl, referer = comic.url.ifBlank { "$base/" })
        if (!r.ok) {
            throw IllegalStateException(Net.friendlyError(IllegalStateException(r.error.ifBlank { "HTTP ${r.code}" })))
        }
        val html = r.body

        // 匹配 window["eval"](function(p,a,c,k,e,d)...{...}('payload', a, c, 'dict'...))
        val packedMatch = Regex("""window\[".*?"\]\(\s*function\(p,a,c,k,e,d\)[\s\S]*?\}\(([\s\S]*?)\)\)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?: Regex("""eval\(\s*function\(p,a,c,k,e,d\)[\s\S]*?\}\(([\s\S]*?)\)\)""", RegexOption.IGNORE_CASE)
                .find(html)
            ?: throw IllegalStateException("未能在章节页解析到漫画数据壳")

        val callArgs = packedMatch.groupValues[1]

        // 提取 'payload', a, c, 'dict'
        val pMatch = Regex("""^'([\s\S]*?)',\s*(\d+),\s*(\d+),\s*(['"][0-9A-Za-z+/=]+['"])""")
            .find(callArgs)
            ?: throw IllegalStateException("漫画加密参数解析失败")

        val payload = pMatch.groupValues[1]
        val a = pMatch.groupValues[2].toInt()
        val c = pMatch.groupValues[3].toInt()
        val b64dict = pMatch.groupValues[4].trim('\'', '"')

        val decompressedDict = LZString.decompressFromBase64(b64dict)
        val dictList = decompressedDict.split('|')

        val unpacked = unpack(payload, a, c, dictList)

        // 解包后为 JS 代码：...({"b":["/3/g/4/2/a.webp",...],"sl":{"e":1773408000,"m":"..."},"path":"/ps3/b/bczlr_hmak/ch099/"})...
        val jsonMatch = Regex("""\{"b":\[[\s\S]*?\][\s\S]*?"path":"[\s\S]*?\}""").find(unpacked)
            ?: Regex("""\{[\s\S]*?"files":\[[\s\S]*?\][\s\S]*?"path":"[\s\S]*?\}""").find(unpacked)
            ?: Regex("""\{[\s\S]*?"path":"[\s\S]*?\}""").find(unpacked)
            ?: throw IllegalStateException("未能从解包代码中提取图片 JSON 配置")

        val jsonStr = jsonMatch.groupValues[0]
        val obj = JSONObject(jsonStr)

        val filesArr = obj.optJSONArray("b") ?: obj.optJSONArray("files") ?: throw IllegalStateException("未能解析到章节图片文件列表")
        val path = obj.optString("path", "")
        val slObj = obj.optJSONObject("sl") ?: obj.optJSONObject("v")
        val paramE = slObj?.opt("e")?.toString() ?: ""
        val paramM = slObj?.opt("m")?.toString() ?: ""

        val query = if (paramE.isNotBlank() && paramM.isNotBlank()) "?e=$paramE&m=$paramM" else ""
        val server = imageServers.first()

        val images = mutableListOf<String>()
        for (i in 0 until filesArr.length()) {
            val f = filesArr.getString(i)
            images.add("$server$path$f$query")
        }

        if (images.isEmpty()) {
            throw IllegalStateException("这一话暂时没有图片，建议去源站查看")
        }

        return ComicPages(
            title = chapter.name,
            images = images,
            referer = chapterUrl,
            headers = mapOf(
                "Referer" to chapterUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        )
    }

    private fun unpack(payload: String, a: Int, c: Int, dict: List<String>): String {
        fun toKey(num: Int): String {
            val sb = StringBuilder()
            var n = num
            do {
                val rem = n % a
                sb.append(if (rem > 35) (rem + 29).toChar() else rem.toString(36))
                n /= a
            } while (n > 0)
            return sb.reverse().toString()
        }

        val map = HashMap<String, String>(c)
        for (i in 0 until c) {
            val w = dict.getOrNull(i).orEmpty()
            if (w.isNotEmpty()) {
                map[toKey(i)] = w
            }
        }

        val tokenRegex = Regex("""\b[0-9a-zA-Z]+\b""")
        return tokenRegex.replace(payload) { m ->
            map[m.value] ?: m.value
        }
    }

    override fun headers(): Map<String, String> = mapOf(
        "Referer" to "$base/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
    )

    private fun absUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$base$url"
        else -> "$base/$url"
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("""<[^>]+>"""), "").replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").trim()
}
