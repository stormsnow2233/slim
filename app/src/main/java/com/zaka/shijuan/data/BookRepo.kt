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
                    if (body.isEmpty()) body["searchkey"] = key
                    Net.post(full, body, src.charset, base + "/")
                } else {
                    Net.get(full, src.charset, base + "/")
                }

                if (!resp.ok) { lastErr = resp.error; continue }

                val books = parseSearchPage(src, resp.body, resp.via.ifBlank { full }, rule)
                if (books.isNotEmpty()) {
                    ActiveBases[src.id] = base
                    return SourceResult(src.id, src.name, true, books, "", System.currentTimeMillis() - t0)
                }
                lastErr = "页面打开了但没认出书籍列表"
            }
        }
        return SourceResult(src.id, src.name, false, emptyList(), lastErr, System.currentTimeMillis() - t0)
    }

    private fun parseSearchPage(src: Source, html: String, pageUrl: String, rule: SearchRule): List<Book> {
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
        if (books.isEmpty() && rule.auto) {
            books = Rules.autoList(doc, pageUrl).map {
                it.copy(sourceId = src.id, sourceName = src.name)
            }
        }
        return books.take(60)
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
     * 两者原本是同一张页面各抓一次，现在先打一枪填缓存，再并行解析，
     * 打开书籍的等待时间从「两次往返」压到「一次往返」。
     */
    fun getDetailAndToc(src: Source, book: Book): Pair<Book, Result<Toc>> {
        val base = ActiveBases[src.id] ?: src.base
        Net.get(book.url, src.charset, base + "/", useCache = true)

        val detRef = java.util.concurrent.atomic.AtomicReference(book)
        val tocRef = java.util.concurrent.atomic.AtomicReference<Result<Toc>>(
            Result.failure(Exception("未执行"))
        )
        val a = Thread { detRef.set(runCatching { getDetail(src, book) }.getOrDefault(book)) }
        val b = Thread { tocRef.set(runCatching { getToc(src, book) }.getOrElse { Result.failure(it) }) }
        a.isDaemon = true; b.isDaemon = true
        a.start(); b.start()
        runCatching { a.join(18000) }
        runCatching { b.join(18000) }
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

    /** 目录 */
    fun getToc(src: Source, book: Book): Result<Toc> {
        val base = ActiveBases[src.id] ?: src.base
        val resp = Net.get(book.url, src.charset, base + "/", useCache = true)
        if (!resp.ok) return Result.failure(Exception(resp.error))
        val doc = Rules.parse(resp.body)
        val t = src.toc
        var list: List<Chapter> = emptyList()

        if (t != null && t.list.isNotBlank()) {
            val anchors = runCatching { doc.select(t.list) }.getOrDefault(emptyList())
            list = anchors.mapIndexedNotNull { idx, a ->
                val name = if (t.name == "@text" || t.name.isBlank()) a.text().replace(Regex("\\s+"), " ").trim()
                else Rules.extract(a, t.name, book.url)
                val url = if (t.url == "@href" || t.url.isBlank()) Rules.absUrl(book.url, a.attr("href"))
                else Rules.extract(a, t.url, book.url)
                if (name.isEmpty() || url.isEmpty()) null else Chapter(name, url, idx)
            }
        }
        if (list.size < 2) {
            val auto = Rules.autoList(doc, book.url, chapterMode = true)
            if (auto.size >= 2) list = auto.mapIndexed { i, b -> Chapter(b.name, b.url, i) }
        }
        // 去重 + 去导航
        val nav = setOf("上一页", "下一页", "首页", "尾页", "目录", "返回", "加入书签", "上一章", "下一章")
        val seen = HashSet<String>()
        list = list.filter { c ->
            if (c.name in nav) return@filter false
            if (!seen.add(c.url)) return@filter false
            true
        }
        if (list.isEmpty()) return Result.failure(Exception("没认出章节目录，可能这站改版了"))

        var cover = book.cover
        var intro = book.intro
        var author = book.author
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.let {
            if (cover.isBlank()) cover = Rules.absUrl(book.url, it)
        }
        src.detail?.let { d ->
            if (d.intro.isNotBlank() && intro.isBlank())
                Rules.extract(doc.body(), d.intro, book.url).ifBlank { null }?.let { intro = it }
            if (d.author.isNotBlank() && author.isBlank())
                Rules.extract(doc.body(), d.author, book.url).ifBlank { null }?.let { author = it }
        }
        return Result.success(Toc(list, cover, intro, author))
    }

    /** 正文 */
    fun getChapter(src: Source, chapter: Chapter): Result<Content> {
        val base = ActiveBases[src.id] ?: src.base
        val resp = Net.get(chapter.url, src.charset, base + "/")
        if (!resp.ok) return Result.failure(Exception(resp.error))
        val doc = Rules.parse(resp.body)
        val c = src.content

        var html = ""
        if (c != null && c.body.isNotBlank()) {
            for (br in c.body.split("||")) {
                val parts = br.trim().split("@")
                val sel = parts.getOrNull(0)?.trim().orEmpty()
                val field = parts.getOrNull(1)?.trim() ?: "text"
                val el = runCatching { if (sel.isBlank()) doc.body() else doc.selectFirst(sel) }.getOrNull()
                if (el != null) {
                    val text = if (field == "html") el.html() else el.text()
                    if (text.replace(Regex("\\s"), "").length > 100) {
                        html = if (field == "html") text else "<p>" + text.replace("\n", "</p><p>") + "</p>"
                        break
                    }
                }
            }
        }
        if (html.isBlank()) html = Rules.autoContent(doc)
        if (html.isBlank()) return Result.failure(Exception("正文没取到（可能这章要登录或要付费）"))

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
                val r2 = Net.get(nextUrl, src.charset, base + "/")
                if (!r2.ok) break
                val d2 = Rules.parse(r2.body)
                val more = Rules.autoContent(d2)
                if (more.isBlank()) break
                html += more
                curDoc = d2; curUrl = nextUrl
            }
        }

        val cleaned = Rules.stripAds(html)
        val text = Rules.htmlToParagraphs(cleaned)
        val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { chapter.name }
        if (text.replace(Regex("\\s"), "").length < 50)
            return Result.failure(Exception("正文太短，可能被站点拦截了"))
        return Result.success(Content(title, text, cleaned))
    }

    /** 书源体检 */
    fun health(src: Source): SourceResult {
        val key = "斗破苍穹"
        return searchSource(src, key)
    }

    /** 并发多源搜索，边出结果边回调 */
    fun searchAll(
        sources: List<Source>,
        key: String,
        onUpdate: (SourceResult) -> Unit,
    ) {
        if (sources.isEmpty()) return
        val latch = java.util.concurrent.CountDownLatch(sources.size)
        for (src in sources) {
            pool.execute {
                val r = runCatching { searchSource(src, key) }
                    .getOrElse { SourceResult(src.id, src.name, false, error = Net.friendlyError(it)) }
                try { onUpdate(r) } finally { latch.countDown() }
            }
        }
        runCatching { latch.await(20, java.util.concurrent.TimeUnit.SECONDS) }
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
