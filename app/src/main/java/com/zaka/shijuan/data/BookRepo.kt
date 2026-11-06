package com.zaka.shijuan.data

import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/** 单次搜索某书源的结果 */
data class SourceResult(
    val sourceId: String,
    val sourceName: String,
    val ok: Boolean,
    val books: List<Book> = emptyList(),
    val error: String = "",
    val ms: Long = 0,
) {
    val count: Int get() = books.size
}

object BookRepo {

    private val pool = Executors.newFixedThreadPool(20)

    /** 正文预取：提前把下一章拉回来，翻页时几乎不用等 */
    private val prefetchPool = Executors.newFixedThreadPool(3)

    private fun enc(s: String, charset: String = "UTF-8"): String =
        runCatching { URLEncoder.encode(s, charset) }.getOrDefault(URLEncoder.encode(s, "UTF-8"))

    private fun fill(tpl: String, key: String, charset: String = "utf-8"): String =
        tpl.replace("{key}", enc(key, if (charset.contains("gb")) "GBK" else "UTF-8"))

    /** 搜索单个书源 —— 依次尝试镜像与备用路径 */
    fun searchSource(src: Source, key: String): SourceResult {
        val t0 = System.currentTimeMillis()
        if (src.apiKind == "zlib") {
            val r = Zlib.search(key)
            return SourceResult(
                src.id, src.name, r.first.isNotEmpty(),
                r.first, r.second, System.currentTimeMillis() - t0,
            )
        }
        if (src.apiKind == "sfacg") return searchSfacg(src, key)
        val rule = src.search ?: return SourceResult(src.id, src.name, false, error = "该源未配置搜索规则")

        // GBK 站点（笔趣阁系、飘天、顶点等）的搜索参数必须用 GBK 编码，否则中文搜不到
        val keyCharset = if (rule.keyCharset.equals("utf-8", true) && src.charset.contains("gb")) "gbk"
        else rule.keyCharset

        var lastErr = "连接不上"
        // 已知能通的域名排最前，省掉镜像探测那几秒
        val known = ActiveBases[src.id]
        val ordered = if (known != null && src.bases.contains(known))
            listOf(known) + src.bases.filter { it != known } else src.bases
        for (base in ordered) {
            val urls = buildList {
                add(fill(rule.url, key, keyCharset))
                rule.extraUrls.forEach { add(fill(it, key, keyCharset)) }
            }
            for (path in urls) {
                val full = if (path.startsWith("http")) path else base + path
                val resp = if (rule.method.equals("POST", true)) {
                    val body = HashMap<String, String>()
                    fill(rule.body, key, keyCharset).split("&").forEach {
                        val kv = it.split("=", limit = 2)
                        if (kv.size == 2) body[kv[0]] = kv[1]
                    }
                    if (body.isEmpty()) body["searchkey"] = enc(key, keyCharset)
                    Net.post(full, body, src.charset, base + "/", encoded = true, fast = true)
                } else {
                    Net.get(full, src.charset, base + "/", fast = true)
                }

                if (!resp.ok) { lastErr = resp.error; continue }

                // 搜一次就被 302 到书页的站点（速读谷），书页本身就是唯一结果。
                // 必须先判这个：那种页面上有一堆章节链接，auto 兜底会把章节目录当成「搜索列表」。
                if (resp.via.isNotBlank() && resp.via != full) {
                    singleBookFromPage(src, resp, full, key)?.let { one ->
                        ActiveBases[src.id] = base
                        return SourceResult(src.id, src.name, true, listOf(one), "", System.currentTimeMillis() - t0)
                    }
                }
                val books = parseSearchPage(src, resp.body, resp.via.ifBlank { full }, rule, key)
                if (books.isNotEmpty()) {
                    ActiveBases[src.id] = base
                    return SourceResult(src.id, src.name, true, books, "", System.currentTimeMillis() - t0)
                }
                // 失败要分清楚：站点上真没这本书 ≠ 站点改版解析不出来。
                // 以前两种情况都甩一句「没认出书籍列表」，看着就像源死了。
                val rows = Rules.countList(Rules.parse(resp.body).body(), rule.list)
                val noEcho = key.isNotBlank() && !resp.body.contains(key, ignoreCase = true)
                lastErr = when {
                    // 结果页上连搜索词都没有，说明站点把请求甩回了首页/导航页，根本没有搜索结果
                    noEcho -> "站点搜索不可用（返回的是导航/首页，不是结果页）"
                    rows > 0 -> "站点改版，列表 ${rows} 项但取不到书名"
                    looksLikeNoResult(resp.body) -> "这个站没有这本书"
                    else -> "没搜到这本书"
                }
            }
        }
        return SourceResult(src.id, src.name, false, emptyList(), lastErr, System.currentTimeMillis() - t0)
    }

    /**
     * SF 轻小说的搜索是前端 ajax 发出去的，HTML 里根本没有结果；
     * 直接打它的搜索接口拿 JSON。书页统一用手机站 /b/{id}/，目录和正文也都走手机站。
     */
    private fun searchSfacg(src: Source, key: String): SourceResult {
        val t0 = System.currentTimeMillis()
        var lastErr = "连接不上"
        val known = ActiveBases[src.id]
        val ordered = if (known != null && src.bases.contains(known))
            listOf(known) + src.bases.filter { it != known } else src.bases
        for (base in ordered) {
            val url = base + "/API/HTML5.ashx?op=search&keyword=" + enc(key)
            val resp = Net.get(url, "utf-8", base + "/search.html", fast = true)
            if (!resp.ok) { lastErr = resp.error; continue }
            val books = runCatching {
                val json = JSONObject(resp.body.trimStart('\uFEFF'))
                val arr = json.optJSONArray("Novels") ?: return@runCatching emptyList<Book>()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("NovelID").trim()
                    val name = o.optString("NovelName").trim()
                    if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
                    Book(
                        name = name,
                        author = o.optString("AuthorName").trim(),
                        cover = sfCover(o.optString("NovelCover").trim()),
                        url = base + "/b/" + id + "/",
                        sourceId = src.id,
                        sourceName = src.name,
                        kind = o.optString("TypeName").trim(),
                    )
                }
            }.getOrElse { emptyList() }
            if (books.isNotEmpty()) {
                ActiveBases[src.id] = base
                return SourceResult(src.id, src.name, true, books, "", System.currentTimeMillis() - t0)
            }
            lastErr = "接口没返回书目"
        }
        return SourceResult(src.id, src.name, false, emptyList(), lastErr, System.currentTimeMillis() - t0)
    }

    private fun sfCover(path: String): String = when {
        path.isEmpty() || path == "null" -> ""
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:" + path
        else -> "https://rs.sfacg.com/web/novel/images/NovelCover/Big/" + path
    }

    /** 把目录页模板里的 {id}/{book} 换成真值 */
    private fun fillTocPage(tpl: String, bookUrl: String): String {
        val id = Regex("/(?:b|Novel|book|info)/(\\d+)").find(bookUrl)?.groupValues?.get(1)
            ?: Regex("(\\d{3,})").find(bookUrl)?.groupValues?.get(1).orEmpty()
        return tpl.replace("{book}", bookUrl).replace("{id}", id)
    }

    /** 页面里明确写了「没有结果」这类话，就说明是站点上没这本书，不是我们解析坏了 */
    private fun looksLikeNoResult(html: String): Boolean {
        if (html.length > 200_000) return false
        val heads = html.substring(0, minOf(html.length, 20_000))
        return listOf(
            "没有找到", "未找到", "无搜索结果", "没有搜索结果", "暂无结果", "抱歉",
            "没有相关", "找不到相关", "no result", "0 条结果", "0条结果",
        ).any { heads.contains(it, ignoreCase = true) }
    }

    private fun parseSearchPage(src: Source, html: String, pageUrl: String, rule: SearchRule, key: String = ""): List<Book> {
        val doc = Rules.parse(html)
        var rows: List<Map<String, String>> = emptyList()
        if (rule.list.isNotBlank() && rule.fields.isNotEmpty()) {
            rows = Rules.extractList(doc.body(), rule.list, rule.fields, pageUrl)
        }
        var books = rows.mapNotNull { row ->
            val name = row["name"].orEmpty().trim()
            val url = row["bookUrl"].orEmpty().trim()
            if (name.isEmpty() || url.isEmpty()) null
            else Book(
                name = name,
                author = row["author"].orEmpty().trim(),
                cover = row["cover"].orEmpty().trim(),
                intro = row["intro"].orEmpty().trim(),
                latest = row["latest"].orEmpty().trim(),
                url = url,
                sourceId = src.id,
                sourceName = src.name,
                kind = row["kind"].orEmpty(),
                wordCount = row["wordCount"].orEmpty(),
            )
        }
        // auto 兜底最容易翻车：站点没有搜索结果时，页面上的导航/推荐位也会被当成「书籍列表」，
        // 于是列表里全是书名不对、点进去是伪目录的条目。要求页面里真出现了搜索词才采纳。
        val echoKey = key.isBlank() || html.contains(key, ignoreCase = true)
        if (books.isEmpty() && rule.auto && echoKey) {
            books = Rules.autoList(doc, pageUrl).map {
                it.copy(sourceId = src.id, sourceName = src.name)
            }
        }
        return books.take(60)
    }

    /** 一次 meta 取值：property 和 name 两种写法都认 */
    private fun metaText(doc: org.jsoup.nodes.Document, key: String): String {
        val el = doc.selectFirst("meta[property=\"$key\"]") ?: doc.selectFirst("meta[name=\"$key\"]")
            ?: return ""
        return el.attr("content").replace(Regex("\\s+"), " ").trim()
    }

    /** 一眼像「书页」：og:type=book，或者标题里写着「最新章节 / 全文阅读」（速读谷的书页标题就是这样） */
    private fun looksLikeBookPage(doc: org.jsoup.nodes.Document): Boolean {
        if (metaText(doc, "og:type").equals("book", true)) return true
        val title = runCatching { doc.title() }.getOrDefault("")
        return title.contains("最新章节") || title.contains("全文阅读") || title.contains("无弹窗")
    }

    /**
     * 有的站拿搜索词一查就直接 302 到书页（速读谷就是这样：搜「凡人修仙传」直接落到 /191/）。
     * 这时把落地的书页本身当成「唯一一条结果」，比丢一句「没搜到这本书」有用得多。
     */
    private fun singleBookFromPage(src: Source, resp: Net.Resp, requestUrl: String, key: String): Book? {
        val landed = resp.via.ifBlank { requestUrl }
        if (landed == requestUrl || landed == src.base || landed == src.base + "/") return null
        val doc = runCatching { Rules.parse(resp.body) }.getOrNull() ?: return null
        if (!looksLikeBookPage(doc)) return null
        val pageText = runCatching { doc.body()?.text().orEmpty() }.getOrDefault("")
        if (key.isNotBlank() && !pageText.contains(key)) return null
        // og:title 常见形态：《书名》作者著-站名；标题常见形态：书名-站名-作者小说-最新章节…
        val raw = metaText(doc, "og:novel:book_name")
            .ifBlank { metaText(doc, "og:title") }
            .ifBlank { metaText(doc, "title") }
            .ifBlank { runCatching { doc.selectFirst("h1")?.text().orEmpty() }.getOrDefault("") }
            .ifBlank { runCatching { doc.title() }.getOrDefault("") }
        val name = (listOf(Regex("^《(.+?)》"), Regex("^(.+?)[-_|｜（(]"))
            .firstNotNullOfOrNull { p -> p.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 } }
            ?: raw).replace(Regex("[《》]"), "").trim()
        if (name.isBlank()) return null
        return Book(
            name = name,
            author = metaText(doc, "og:novel:author").ifBlank { metaText(doc, "author") },
            cover = metaText(doc, "og:image"),
            intro = metaText(doc, "og:description").ifBlank { metaText(doc, "description") }.take(400),
            url = landed,
            sourceId = src.id,
            sourceName = src.name,
        )
    }

    /** 详情页：补作者/封面/简介（失败不致命） */
    fun getDetail(src: Source, book: Book): Book {
        val base = ActiveBases[src.id] ?: src.base
        val resp = Net.get(book.url, src.charset, base + "/", useCache = true)
        if (!resp.ok) return book
        val doc = Rules.parse(resp.body)
        val d = src.detail
        var name = book.name
        var author = book.author
        var cover = book.cover
        var intro = book.intro
        if (d != null) {
            if (d.name.isNotBlank()) Rules.extract(doc.body(), d.name, book.url).ifBlank { null }?.let { name = it }
            if (d.author.isNotBlank()) Rules.extract(doc.body(), d.author, book.url).ifBlank { null }?.let { author = it }
            if (d.cover.isNotBlank()) Rules.extract(doc.body(), d.cover, book.url).ifBlank { null }?.let { cover = it }
            if (d.intro.isNotBlank()) Rules.extract(doc.body(), d.intro, book.url).ifBlank { null }?.let { intro = it }
        }
        if (intro.isBlank()) {
            doc.selectFirst("meta[name=description]")?.attr("content")?.let { intro = it }
        }
        // 通用兜底：og 标签
        if (cover.isBlank()) {
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { cover = Rules.absUrl(book.url, it) }
        }
        return book.copy(name = name, author = author, cover = cover, intro = intro)
    }

    /**
     * 详情 + 目录一次拿全。
     * 两者并行解析，若首选域名失败则尝试镜像。
     */
    fun getDetailAndToc(src: Source, book: Book): Pair<Book, Result<Toc>> {
        val detRef = java.util.concurrent.atomic.AtomicReference(book)
        val tocRef = java.util.concurrent.atomic.AtomicReference<Result<Toc>>(
            Result.failure(Exception("未执行"))
        )
        val a = Thread { detRef.set(runCatching { getDetail(src, book) }.getOrDefault(book)) }
        val b = Thread { tocRef.set(runCatching { getToc(src, book) }.getOrElse { Result.failure(it) }) }
        a.isDaemon = true; b.isDaemon = true
        a.start(); b.start()
        runCatching { a.join(35000) }
        runCatching { b.join(35000) }
        return detRef.get() to tocRef.get()
    }

    /** 后台预取指定章节正文（翻页时直接命中缓存） */
    fun prefetchChapter(src: Source, chapter: Chapter, store: Store) {
        if (store.loadContent(chapter.url) != null) return
        runCatching {
            prefetchPool.execute {
                runCatching { getChapter(src, chapter) }.getOrNull()
                    ?.onSuccess { store.saveContent(chapter.url, it.text) }
            }
        }
    }

    /** 从一张页面上按规则 / 兜底认出章节列表 */
    internal fun parseTocList(doc: org.jsoup.nodes.Document, pageUrl: String, t: TocRule?): List<Chapter> {
        var list: List<Chapter> = emptyList()
        val skipVol = t?.skipVolume?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }

        /** 这个章节属于哪一卷：往上找几层，向前查找卷标题（SF 是 div.mulu，Wenku8 是 table.css 中的 td.vcss） */
        fun volumeOf(el: org.jsoup.nodes.Element): String {
            val sel = t?.volume.orEmpty()
            if (sel.isBlank()) return ""
            var n: org.jsoup.nodes.Element? = el
            var depth = 0
            while (n != null && depth++ < 6) {
                var prev = n.previousElementSibling()
                while (prev != null) {
                    if (runCatching { prev.`is`(sel) }.getOrDefault(false)) {
                        return prev.text().replace(Regex("\\s+"), " ").trim()
                    }
                    val found = runCatching { prev.selectFirst(sel) }.getOrNull()
                    if (found != null) {
                        return found.text().replace(Regex("\\s+"), " ").trim()
                    }
                    prev = prev.previousElementSibling()
                }
                n = n.parent()
            }
            return ""
        }

        if (t != null && t.list.isNotBlank()) {
            for (branch in t.list.split("||")) {
                val sel = branch.trim()
                if (sel.isEmpty()) continue
                val elements = runCatching { doc.select(sel) }.getOrDefault(emptyList())
                if (elements.isEmpty()) continue
                val parsed = elements.mapIndexedNotNull { idx, el ->
                    val vol = volumeOf(el)
                    // 整卷都是插图/人设的话（SF 每本书几乎都有），这一卷的章节一个都不要
                    if (skipVol != null && skipVol.containsMatchIn(vol)) return@mapIndexedNotNull null
                    val target = if (el.tagName().equals("a", true)) el else el.selectFirst("a") ?: el
                    val name = when {
                        t.name.isBlank() || t.name == "@text" || t.name == "text" ->
                            target.text().replace(Regex("\\s+"), " ").trim()
                        else -> Rules.extract(el, t.name, pageUrl).ifBlank {
                            target.text().replace(Regex("\\s+"), " ").trim()
                        }
                    }
                    val url = when {
                        t.url.isBlank() || t.url == "@href" || t.url == "href" -> {
                            val href = target.attr("href").ifBlank { target.attr("data-href") }.trim()
                            Rules.absUrl(pageUrl, href)
                        }
                        else -> Rules.extract(el, t.url, pageUrl).ifBlank {
                            val href = target.attr("href").ifBlank { target.attr("data-href") }.trim()
                            Rules.absUrl(pageUrl, href)
                        }
                    }
                    if (name.isEmpty() || url.isEmpty() || url == pageUrl) null else Chapter(name, url, idx, vol)
                }
                if (parsed.size >= 2) {
                    list = parsed
                    break
                }
            }
        }
        if (list.size < 2) {
            val guess = guessToc(doc, pageUrl)
            if (guess.size >= 2) list = guess
        }
        if (list.size < 2) {
            val auto = Rules.autoList(doc, pageUrl, chapterMode = true)
            if (auto.size >= 2) list = auto.mapIndexed { i, b -> Chapter(b.name, b.url, i) }
        }
        // 去重 + 去导航 + 去读不了的章节（插图页/人设页这类只有图片的章节，规则里用 skip 指定）
        val nav = setOf("上一页", "下一页", "首页", "尾页", "目录", "返回", "加入书签", "上一章", "下一章")
        val skip = t?.skip?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() }
        val seen = HashSet<String>()
        var idx = 0
        return list.mapNotNull { c ->
            if (c.name in nav) return@mapNotNull null
            if (!seen.add(c.url)) return@mapNotNull null
            if (skip != null && skip.containsMatchIn(c.name)) return@mapNotNull null
            c.copy(index = idx++)
        }
    }

    /** 目录 */
    fun getToc(src: Source, book: Book): Result<Toc> {
        val base = ActiveBases[src.id] ?: src.base
        val t = src.toc
        // 有的站书页和目录页是分开的两页（SF 手机站就是），规则里可以单独给目录页模板
        val tocTpl = t?.page.orEmpty()
        val tocUrl = if (tocTpl.isBlank()) book.url
        else Rules.absUrl(base + "/", fillTocPage(tocTpl, book.url))
        var resp = Net.get(tocUrl, src.charset, base + "/", useCache = true)
        // 目录页偶发挂了（限流/换版），回书页再试一次，别直接判死
        if (!resp.ok && tocUrl != book.url) {
            val alt = Net.get(book.url, src.charset, base + "/", useCache = true)
            if (alt.ok) resp = alt
        }
        if (!resp.ok && src.mirrors.isNotEmpty()) {
            val curUri = runCatching { java.net.URI(tocUrl) }.getOrNull()
            if (curUri != null) {
                for (mirror in src.mirrors) {
                    val mirrorUri = runCatching { java.net.URI(mirror) }.getOrNull() ?: continue
                    val altUrl = java.net.URI(mirrorUri.scheme, mirrorUri.authority, curUri.path, curUri.query, curUri.fragment).toString()
                    val r2 = Net.get(altUrl, src.charset, "$mirror/", useCache = true)
                    if (r2.ok) {
                        resp = r2
                        ActiveBases[src.id] = mirror
                        break
                    }
                }
            }
        }
        if (!resp.ok) return Result.failure(Exception(resp.error))
        var doc = Rules.parse(resp.body)
        var pageUrl = resp.via.ifBlank { tocUrl }
        var list = parseTocList(doc, pageUrl, t)

        // 目录页认不出来（页面换版 / 认成推荐书单），换书页再认一遍
        if ((list.size < 2 || !tocLooksReal(book.url, list)) && pageUrl != book.url) {
            val r2 = Net.get(book.url, src.charset, base + "/", useCache = true)
            if (r2.ok) {
                val d2 = Rules.parse(r2.body)
                val p2 = r2.via.ifBlank { book.url }
                val l2 = parseTocList(d2, p2, t)
                if (l2.size > list.size && tocLooksReal(book.url, l2)) {
                    doc = d2; pageUrl = p2; list = l2
                }
            }
        }
        if (list.isEmpty()) {
            // 正文区几乎没字、脚本却一大把：这站的目录是前端渲染出来的，静态抓永远拿不到
            val textLen = runCatching {
                doc.body()?.text()?.replace(Regex("\\s"), "")?.length ?: 0
            }.getOrDefault(0)
            val scripts = runCatching { doc.select("script").size }.getOrDefault(0)
            if (textLen < 400 && scripts >= 3)
                return Result.failure(Exception("这站的目录靠 JS 渲染，App 抓静态页面拿不到（起点这类站点都这样），换别的源搜同名书吧"))
            return Result.failure(Exception("没认出章节目录，可能这站改版了"))
        }
        // 认出来的东西跟书籍页同形（/b/123/ 对 /b/456/）说明抓的是书页上的推荐书单，不是章节
        if (!tocLooksReal(book.url, list))
            return Result.failure(Exception("这页上认不出章节目录（抓到的是书籍列表），可能这站改版了"))
        val tocUrlReal = pageUrl

        var cover = book.cover
        var intro = book.intro
        var author = book.author
        // 目录页和书页分成两页的站点，详情要回书页再拿一次（有页面缓存，一般不额外耗时）
        val detailDoc = if (tocUrlReal == book.url) doc else {
            val r = Net.get(book.url, src.charset, base + "/", useCache = true)
            if (r.ok) Rules.parse(r.body) else doc
        }
        detailDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (cover.isBlank()) cover = Rules.absUrl(book.url, it)
        }
        src.detail?.let { d ->
            if (d.cover.isNotBlank() && cover.isBlank())
                Rules.extract(detailDoc.body(), d.cover, book.url).ifBlank { null }?.let { cover = it }
            if (d.intro.isNotBlank() && intro.isBlank())
                Rules.extract(detailDoc.body(), d.intro, book.url).ifBlank { null }?.let { intro = it }
            if (d.author.isNotBlank() && author.isBlank())
                Rules.extract(detailDoc.body(), d.author, book.url).ifBlank { null }?.let { author = it }
        }
        return Result.success(Toc(list, cover, intro, author))
    }

    /** 软错误页识别（源站 HTTP 200 但内容是报错/空壳） */
    fun isSoftError(body: String): Boolean {
        if (body.isBlank()) return false
        return body.contains("您访问的页面暂时无法访问") || body.contains("页面找不到了") ||
            body.contains("页面不存在") || body.contains("系统繁忙") || body.contains("访问过于频繁") ||
            body.contains("暂时无法访问哦")
    }

    /** 正文 */
    fun getChapter(src: Source, chapter: Chapter): Result<Content> {
        val base = ActiveBases[src.id] ?: src.base
        val isSlow = src.id == "sfacg" || chapter.url.contains("sfacg.com")
        var resp = Net.get(chapter.url, src.charset, base + "/", slow = isSlow)
        if (!resp.ok || isSoftError(resp.body)) {
            // 正文拿失败、或拿到软错误页 → 隔 450ms 重试一次（SF 等源站偶发错误页/排队抖动，重试大多能成功）
            runCatching { Thread.sleep(450) }
            val retry = Net.get(chapter.url, src.charset, base + "/", slow = isSlow)
            if (retry.ok && !isSoftError(retry.body)) {
                resp = retry
            }
        }
        if (!resp.ok && src.mirrors.isNotEmpty()) {
            val curUri = runCatching { java.net.URI(chapter.url) }.getOrNull()
            if (curUri != null) {
                for (mirror in src.mirrors) {
                    val mirrorUri = runCatching { java.net.URI(mirror) }.getOrNull() ?: continue
                    val altUrl = java.net.URI(mirrorUri.scheme, mirrorUri.authority, curUri.path, curUri.query, curUri.fragment).toString()
                    val r2 = Net.get(altUrl, src.charset, "$mirror/", slow = isSlow)
                    if (r2.ok && !isSoftError(r2.body)) {
                        resp = r2
                        ActiveBases[src.id] = mirror
                        break
                    }
                }
            }
        }
        if (!resp.ok) {
            val err = resp.error
            val msg = when {
                err.contains("超时") -> "正文请求超时（源站排队过慢，建议稍后刷新重试）"
                err.contains("403") -> "源站 403 拦截（防爬机制触发，建议换源或稍后再试）"
                err.isNotBlank() -> "网络请求失败：$err"
                else -> "正文请求失败"
            }
            return Result.failure(Exception(msg))
        }

        if (isSoftError(resp.body)) {
            return Result.failure(Exception("源站返回「页面暂时无法访问」（该老书在手机站未开放或章节已下线），换个源搜同名书试试"))
        }

        val doc = Rules.parse(resp.body)
        val c = src.content

        var html = ""
        if (c != null && c.body.isNotBlank()) {
            for (br in c.body.split("||")) {
                var expr = br.trim()
                if (expr.isEmpty()) continue
                var regex = ""
                val hashIdx = expr.indexOf("##")
                if (hashIdx >= 0) {
                    regex = expr.substring(hashIdx)
                    expr = expr.substring(0, hashIdx)
                }
                val at = expr.lastIndexOf('@')
                val sel = if (at >= 0) expr.substring(0, at).trim() else expr
                val field = if (at >= 0) expr.substring(at + 1).trim().ifBlank { "html" } else "html"
                val el = runCatching { if (sel.isBlank()) doc.body() else doc.selectFirst(sel) }.getOrNull()
                if (el != null) {
                    var text = when (field) {
                        "html" -> el.html()
                        "texts" -> el.children().joinToString("\n") { it.text().trim() }.ifBlank { el.text().trim() }
                        else -> el.text()
                    }
                    if (regex.isNotEmpty()) {
                        text = Rules.applyRegex(text, regex)
                    }
                    if (text.replace(Regex("\\s"), "").length > 80) {
                        html = if (field == "html") text else "<p>" + text.replace("\n", "</p><p>") + "</p>"
                        break
                    }
                }
            }
        }
        if (html.isBlank()) html = guessContent(doc)
        // 规则命中的东西太短，多半抓到的是导航/提示条，换成整页里最像正文的那块
        if (html.replace(Regex("\\s"), "").length < 180) {
            val guess = guessContent(doc)
            if (guess.replace(Regex("\\s"), "").length > html.replace(Regex("\\s"), "").length) html = guess
        }
        // 有的站免费章节正常给正文，付费章节只给一段购买提示——这时别报"被拦截"，要报准
        if (html.replace(Regex("\\s"), "").length < 120) {
            val page = doc.text()
            val vipMark = listOf("VIP章节", "vip章节", "购买本章", "订阅本章", "本章需购买", "余额不足", "充值后阅读", "登录后阅读")
                .firstOrNull { page.contains(it) }
            if (vipMark != null)
                return Result.failure(Exception("这章是源站的付费/VIP 章节，得先在源站登录购买才能看，建议换个源搜同名书"))
            if (isErrorPage(doc))
                return Result.failure(Exception("源站返回「暂时无法访问」（该老书在手机站未开放或已下线），建议换个源搜同名书"))
            // 如果正文区里有图片（插图画/彩页/人设），保留交给阅读器以插图卡片形式呈现
        }
        if (html.isBlank()) {
            if (isErrorPage(doc))
                return Result.failure(Exception("源站返回「暂时无法访问」（该老书在手机站未开放或已下线），建议换个源搜同名书"))
            return Result.failure(Exception("正文未解析出内容（可能章节需要登录或该页结构改版）"))
        }

        // 分页拼接
        if (c != null && c.next.isNotBlank()) {
            var curDoc = doc
            var curUrl = chapter.url
            var guard = 0
            while (guard++ < 8) {
                val nextEl = runCatching { curDoc.selectFirst(c.next) }.getOrNull() ?: break
                val href = nextEl.attr("href")
                if (href.isBlank()) break
                val nextUrl = Rules.absUrl(curUrl, href)
                if (!Regex("[_\\-/]\\d+(_\\d+)?\\.html?$").containsMatchIn(nextUrl)) break
                val r2 = Net.get(nextUrl, src.charset, base + "/", slow = isSlow)
                if (!r2.ok) break
                val d2 = Rules.parse(r2.body)
                val more = Rules.autoContent(d2)
                if (more.isBlank()) break
                html += more
                curDoc = d2; curUrl = nextUrl
            }
        }

        val cleaned = Rules.stripAds(html)
        val text = Rules.htmlToParagraphs(cleaned, chapter.url)
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { chapter.name }
        val hasImages = text.contains("<!--image-->")
        val len = text.replace(Regex("\\s"), "").length
        // 一百字以下且没有插图的通常不是正文：要么被拦，要么抓到的是书页/推荐列表那几行字
        if (len < 120 && !hasImages) {
            if (isErrorPage(doc))
                return Result.failure(Exception("源站返回「暂时无法访问」（该老书在手机站未开放或已下线），建议换个源搜同名书"))
            if (len == 0)
                return Result.failure(Exception("源站这一章是空的（有的站把同一章挂了两条，其中一条只有标题），翻到下一章吧"))
            if (doc.select("a[href]").size >= 12)
                return Result.failure(Exception("抓到的这页是书籍页/目录页，不是正文，可能这站改版了"))
            return Result.failure(Exception("正文内容过短（仅 ${len} 字），可能触发源站拦截或防盗提示"))
        }
        return Result.success(Content(title, text, cleaned))
    }

    // ─────────── 目录 / 正文的兜底识别 ───────────
    // 站点改版后规则选择器往往整套失效，这里用「候选清单 + 打分」硬认出目录和正文，
    // 只要页面结构别太离谱，就不用等发新版才能看。

    /** 源站自己吐的出错页（不是我们解析失败），提示要说准，别让人以为软件坏了 */
    private fun isErrorPage(doc: org.jsoup.nodes.Document): Boolean {
        val t = runCatching { doc.title() }.getOrDefault("")
        if (t.contains("出错") || t.contains("无法访问") || t.contains("页面找不到") || t.contains("404")) return true
        val body = runCatching { doc.body()?.text().orEmpty() }.getOrDefault("")
        return isSoftError(body) || body.contains("出错啦")
    }

    /** 地址形态：数字统一抹成 N。/b/785881/ 和 /b/142354/ 是同形，/c/9958959/ 不是 */
    private fun shapeOf(url: String): String = runCatching {
        java.net.URI(url).path.orEmpty().replace(Regex("\\d+"), "N").lowercase()
    }.getOrDefault("")

    /**
     * 目录真不真：章节地址不能和书籍页同形。
     * 兜底识别在书页上跑时，最容易把「猜你喜欢」那几个推荐书链接当章节，
     * 结果目录只有 3、4 条，点进去看到的是别人的简介。
     */
    fun tocLooksReal(bookUrl: String, chapters: List<Chapter>): Boolean {
        if (chapters.isEmpty()) return false
        // 章节名大量重复 = 抓到的是站点导航/栏目菜单（「手机小说」「全部小说」这类），不是章节
        val distinct = chapters.map { it.name }.distinct().size
        if (chapters.size >= 8 && distinct * 3 < chapters.size) return false
        if (chapters.size >= 15) return true
        val bookShape = shapeOf(bookUrl)
        if (bookShape.isBlank()) return true
        val same = chapters.count { shapeOf(it.url) == bookShape }
        return same * 2 < chapters.size
    }

    private val TOC_SELECTORS = listOf(
        "#list dl dd a", "#list dd a", "dl dd a", "#chapterlist a", "#chapter-list a",
        "#list-chapterAll a", ".listmain a", "#catalog a", ".catalog a", "ul.catalogul a",
        "#vol_1 a", ".volume a", ".dirlist a", ".chapter a", ".section-list a",
        ".book_list a", "#booklist a", "#newlist a", "#content table td a",
        ".module-row-text", ".chapter-list a", ".mulu_list a", "a[href*=chapter]",
        "a[href*=/read/]", "dd a[href$='.html']", ".list-chapter a",
    )

    private fun guessToc(doc: org.jsoup.nodes.Document, pageUrl: String): List<Chapter> {
        var best: List<Pair<String, String>> = emptyList()
        var bestScore = 0
        for (sel in TOC_SELECTORS) {
            val anchors = runCatching { doc.select(sel) }.getOrDefault(emptyList())
            if (anchors.size < 2) continue
            val items = anchors.mapNotNull { el ->
                val target = if (el.tagName().equals("a", true)) el else el.selectFirst("a") ?: el
                val name = target.text().replace(Regex("\\s+"), " ").trim()
                val href = target.attr("href").ifBlank { target.attr("data-href") }.trim()
                if (name.isEmpty() || href.isEmpty()) return@mapNotNull null
                if (href.startsWith("#") || href.startsWith("javascript")) return@mapNotNull null
                val abs = Rules.absUrl(pageUrl, href)
                if (!abs.startsWith("http") || abs == pageUrl) return@mapNotNull null
                name to abs
            }.distinctBy { it.second }
            if (items.size < 2) continue
            var score = items.size
            if (sel.contains("chapter") || sel.contains("catalog") || sel.contains("volume") ||
                sel.contains("list") || sel.contains("read") || sel.contains("mulu")
            ) score += 8
            if (items.size > 400) score -= 60      // 整站链接堆，不是目录
            if (score > bestScore) { bestScore = score; best = items }
        }
        return best.mapIndexed { i, (n, u) -> Chapter(n, u, i) }
    }

    private val CONTENT_SELECTORS = listOf(
        "div.yuedu.Content_Frame > div", ".Content_Frame > div", ".yuedu [style*=\"text-indent\"]",
        "[style*=\"text-indent\"]", ".yuedu",
        "#content", "#chaptercontent", "#acontent", "#booktext", "#txtContent",
        "#nr1", "#nr", "#mlfy_main_text", "#zjny", "#contentbox", "#booktxt",
        "#htmlContent", "#chapter-content", "#article-content", "#BookText",
        ".read-content", ".article-content", ".readbox .content", ".showtxt",
        ".yd_text2", ".content", ".text", "article",
    )

    private fun guessContent(doc: org.jsoup.nodes.Document): String {
        var bestHtml = ""
        var bestLen = 0
        for (sel in CONTENT_SELECTORS) {
            val el = runCatching { doc.selectFirst(sel) }.getOrNull() ?: continue
            val len = el.text().replace(Regex("\\s"), "").length
            if (len >= 180 && len > bestLen) { bestLen = len; bestHtml = el.html() }
        }
        if (bestHtml.isNotBlank()) return bestHtml
        return Rules.autoContent(doc)
    }

    /** 书源体检 */
    fun health(src: Source): SourceResult {
        val key = when {
            src.apiKind == "sfacg" -> "魔王"
            src.group.contains("轻小说") -> "邻家天使"
            else -> "凡人修仙传"
        }
        return searchSource(src, key)
    }

    /**
     * 分段体检：搜索 → 目录 → 正文，哪一步断了直接说哪一步断的。
     * 以前只有「搜索通/不通」，看不出后面两步，排查全靠猜。
     */
    fun healthDeep(src: Source): Triple<Boolean, String, String> {
        val s = health(src)
        if (!s.ok || s.books.isEmpty()) return Triple(false, "搜索：${s.error.ifBlank { "没结果" }}", "")
        val book = s.books.first()
        val toc = runCatching { getToc(src, book) }
            .getOrElse { return Triple(true, "目录：${it.message}", "") }
        val list = toc.getOrElse { return Triple(true, "目录：${it.message}", "") }
        if (list.chapters.size < 2) return Triple(true, "目录：只认出 ${list.chapters.size} 章", "")
        // 目录开头常有插图页/人设页/公告，本来就没有正文可抓。只测第一章会把好源误判成
        // 「正文内容过短」（SF 之前就是这么被冤枉的）。第一章不行就补测中间、四分之一处：
        // 中间的章节几乎不可能是插图页，能真实反映这个源到底给不给正文。
        val probes = listOfNotNull(
            list.chapters.firstOrNull(),
            list.chapters.getOrNull(list.chapters.size / 2),
            list.chapters.getOrNull(list.chapters.size / 4),
        ).distinctBy { it.url }
        var lastErr = ""
        var tried = 0
        for (ch in probes) {
            tried++
            val res = runCatching { getChapter(src, ch) }
            val out = res.getOrNull()?.getOrNull()
            if (out == null) {
                val e = res.getOrNull()?.exceptionOrNull() ?: res.exceptionOrNull()
                lastErr = e?.message ?: "正文取不到"
                continue
            }
            val n = out.text.count { !it.isWhitespace() }
            if (n >= 120) {
                val where = if (tried == 1) "" else "（换到第 ${ch.index + 1} 章试的）"
                return Triple(true, "目录 ${list.chapters.size} 章 · 正文 $n 字$where", "")
            }
            lastErr = "正文内容过短（仅 $n 字）"
        }
        return Triple(true, "目录 ${list.chapters.size} 章 · 正文：${lastErr.ifBlank { "抽了 $tried 章都取不到正文" }}", "")
    }

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

    /** 并发多源搜索，边出结果边回调 */
    fun searchAll(
        sources: List<Source>,
        key: String,
        onUpdate: (SourceResult) -> Unit,
    ) {
        val active = sources.filterNot { isBlacklisted(it.id) }.ifEmpty { sources }
        if (active.isEmpty()) return
        val latch = java.util.concurrent.CountDownLatch(active.size)
        for (src in active) {
            pool.execute {
                val t0 = System.currentTimeMillis()
                val r = runCatching {
                    searchSource(src, key)
                }.getOrElse {
                    SourceResult(src.id, src.name, false, error = Net.friendlyError(it), ms = System.currentTimeMillis() - t0)
                }
                // 仅对严重网关故障（502、504、连接拒绝）做 1 分钟轻度临时熔断，常规超时或搜无结果绝不拉黑
                if (!r.ok && (r.error.contains("502") || r.error.contains("504") || r.error.contains("拒绝连接"))) {
                    markFailed(src.id, 60_000L)
                }
                try { onUpdate(r) } finally { latch.countDown() }
            }
        }
        runCatching { latch.await(14, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** 当前每个源实际连通的域名 */
    val ActiveBases = java.util.concurrent.ConcurrentHashMap<String, String>()
}

/** Z-Library 官方 App 接口 */
object Zlib {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    private val bases = listOf("https://z-lib.gd", "https://z-library.sk", "https://1lib.sk")

    /** 返回 (书籍列表, 错误) */
    fun search(key: String): Pair<List<Book>, String> {
        var lastErr = "连不上"
        for (base in bases) {
            val resp = Net.post(
                "$base/eapi/book/search",
                mapOf("message" to key, "page" to "1", "limit" to "20"),
                charset = "utf-8",
                referer = "$base/",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            )
            if (!resp.ok) { lastErr = resp.error; continue }
            val parsed = runCatching { parse(resp.body, base) }.getOrNull()
            if (parsed != null && parsed.first.isNotEmpty()) return parsed
            lastErr = parsed?.second ?: "接口返回异常"
        }
        return emptyList<Book>() to lastErr
    }

    private fun parse(json: String, base: String): Pair<List<Book>, String> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("books") ?: return emptyList<Book>() to "没有结果"
        val out = ArrayList<Book>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val id = o.optString("id")
            val hash = o.optString("hash")
            val cover = o.optString("cover")
            val year = o.optString("year")
            val lang = o.optString("language")
            val publisher = o.optString("publisher")
            val desc = buildString {
                if (publisher.isNotBlank()) append("出版社：$publisher\n")
                if (year.isNotBlank() && year != "0") append("年份：$year\n")
                if (lang.isNotBlank()) append("语言：$lang\n")
                append("格式：").append(
                    buildList {
                        if (o.optString("extension").isNotBlank()) add(o.optString("extension").uppercase())
                    }.joinToString(" / ").ifBlank { "EPUB / PDF" }
                )
            }
            out.add(
                Book(
                    name = title,
                    author = o.optString("author"),
                    cover = cover,
                    intro = desc,
                    url = if (id.isNotBlank() && hash.isNotBlank()) "$base/book/$id/$hash" else "$base/s/$title",
                    sourceId = "zlib",
                    sourceName = "Z-Library",
                    year = year,
                    language = lang,
                    kind = o.optString("extension").uppercase(),
                    external = true,
                    downloadUrl = "$base/book/$id/$hash",
                )
            )
        }
        return out to ""
    }
}
