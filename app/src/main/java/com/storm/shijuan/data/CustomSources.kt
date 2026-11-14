package com.storm.shijuan.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义书源：用户自己加的源存在本地，跟内置源一样参与搜索。
 *
 * 存储格式（一个源）：
 * {
 *   "name": "某某小说网", "base": "https://www.xxx.com", "charset": "utf-8",
 *   "group": "我的书源",
 *   "search": {"url": "/search.php?searchkey={key}", "method": "GET", "body": "",
 *              "list": ".bookbox", "fields": {"name":"a@text","bookUrl":"a@href"},
 *              "keyCharset": "utf-8"},
 *   "detail": {"name":"h1@text","author":"","cover":"","intro":""},
 *   "toc": {"list":"#list a","name":"@text","url":"@href","page":""},
 *   "content": {"body":"#content@html"},
 *   "enabled": true
 * }
 *
 * 也认「阅读」App 的书源格式（bookSourceName / bookSourceUrl / ruleSearch ...），
 * 选择器做尽力而为的转换，转不出来的字段留空交给自动识别兜底。
 */
object CustomSources {

    private const val KEY = "custom_sources_v1"
    private var sp: android.content.SharedPreferences? = null
    private var storeRef: Store? = null

    fun init(context: Context, store: Store) {
        storeRef = store
        val prefs = context.getSharedPreferences("shijuan", Context.MODE_PRIVATE)
        sp = prefs
        val raw = prefs.getString(KEY, null)
        val list = if (raw.isNullOrBlank()) emptyList() else runCatching { parseAll(raw) }.getOrDefault(emptyList())
        Sources.installCustom(list)
        // 这里不碰开关：新增的源由 upsert 负责默认打开，
        // 用户手动关掉的源重启后要保持关着
    }

    fun all(): List<Source> = Sources.custom

    fun save(list: List<Source>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp?.edit()?.putString(KEY, arr.toString())?.apply()
        Sources.installCustom(list)
    }

    /** 新增或更新（按 id） */
    fun upsert(src: Source): List<Source> {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == src.id }
        val isNew = i < 0
        if (isNew) list.add(src) else list[i] = src
        save(list)
        // 新加的源默认开着，编辑已有的不动用户的开关
        if (isNew) storeRef?.setSourceEnabled(src.id, true)
        return list
    }

    fun remove(id: String): List<Source> {
        val list = all().filterNot { it.id == id }
        save(list)
        storeRef?.setSourceEnabled(id, false)
        return list
    }

    fun removeAll(ids: Set<String>): List<Source> {
        if (ids.isEmpty()) return all()
        val list = all().filterNot { it.id in ids }
        save(list)
        ids.forEach { storeRef?.setSourceEnabled(it, false) }
        return list
    }

    fun newId(base: String): String {
        val host = runCatching { java.net.URI(base).host.orEmpty() }.getOrDefault("")
        val slug = host.ifBlank { base }.replace(Regex("[^a-zA-Z0-9]"), "").take(14)
        var id = "custom_$slug"
        var n = 2
        while (Sources.all.any { it.id == id }) { id = "custom_${slug}_$n"; n++ }
        return id
    }

    // ─────────── 序列化 ───────────

    fun toJson(s: Source): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("base", s.base)
        if (s.mirrors.isNotEmpty()) {
            put("mirrors", JSONArray(s.mirrors))
        }
        put("charset", s.charset)
        put("group", s.group)
        put("status", s.status)
        put("note", s.note)
        if (s.apiKind.isNotBlank()) put("apiKind", s.apiKind)
        if (s.enabledByDefault) put("enabledByDefault", s.enabledByDefault)
        if (s.probeKeyword.isNotBlank()) put("probeKeyword", s.probeKeyword)
        if (s.probeUrl.isNotBlank()) put("probeUrl", s.probeUrl)
        if (s.probeMode.isNotBlank() && s.probeMode != "SEARCH") put("probeMode", s.probeMode)
        if (s.userAgent.isNotBlank()) put("userAgent", s.userAgent)
        if (s.renderMode != "HTTP") put("renderMode", s.renderMode)

        s.search?.let { r ->
            put("search", JSONObject().apply {
                put("url", r.url)
                put("method", r.method)
                put("body", r.body)
                put("list", r.list)
                put("auto", r.auto)
                put("keyCharset", r.keyCharset)
                if (r.extraUrls.isNotEmpty()) put("extraUrls", JSONArray(r.extraUrls))
                put("fields", JSONObject().apply { r.fields.forEach { (k, v) -> put(k, v) } })
            })
        }
        s.detail?.let { d ->
            put("detail", JSONObject().apply {
                put("name", d.name); put("author", d.author)
                put("cover", d.cover); put("intro", d.intro)
            })
        }
        s.toc?.let { t ->
            put("toc", JSONObject().apply {
                put("list", t.list); put("name", t.name)
                put("url", t.url); put("page", t.page)
                if (t.link.isNotBlank()) put("link", t.link)
                if (t.skip.isNotBlank()) put("skip", t.skip)
                if (t.volume.isNotBlank()) put("volume", t.volume)
                if (t.skipVolume.isNotBlank()) put("skipVolume", t.skipVolume)
                if (t.nextPage.isNotBlank()) put("nextPage", t.nextPage)
                if (t.maxPages != 50) put("maxPages", t.maxPages)
            })
        }
        s.content?.let { c ->
            put("content", JSONObject().apply { put("body", c.body); put("next", c.next) })
        }
    }

    /** 导出给人看 / 分享的格式：缩进过的 JSON */
    fun pretty(s: Source): String = runCatching { toJson(s).toString(2) }.getOrDefault("{}")

    fun prettyAll(): String {
        val arr = JSONArray()
        all().forEach { arr.put(toJson(it)) }
        return runCatching { arr.toString(2) }.getOrDefault("[]")
    }

    fun fromJson(o: JSONObject): Source? {
        val base = o.optString("base")
            .ifBlank { o.optString("bookSourceUrl") }
            .ifBlank { o.optString("sourceUrl") }
            .ifBlank { o.optString("url") }
            .trim()
        if (base.isBlank()) return null
        return build(base, o)
    }

    data class ParsedSearchUrl(
        val url: String,
        val method: String,
        val body: String,
        val charset: String = "utf-8",
    )

    private fun build(rawBase: String, o: JSONObject): Source? {
        val base = normalizeBase(rawBase) ?: return null
        val name = o.optString("name")
            .ifBlank { o.optString("bookSourceName") }
            .ifBlank { o.optString("sourceName") }
            .ifBlank { o.optString("title") }
            .ifBlank { runCatching { java.net.URI(base).host }.getOrDefault("自定义源") }
            .trim()
        if (base.contains("bilibili.com") || name.contains("请勿删除") || name.contains("关注B站")) return null

        val group = o.optString("group")
            .ifBlank { o.optString("bookSourceGroup") }
            .ifBlank { o.optString("sourceGroup") }
            .ifBlank { "我的书源" }
            .trim()

        val mirrors = buildList {
            val arr = o.optJSONArray("mirrors")
            if (arr != null) {
                for (idx in 0 until arr.length()) {
                    val m = arr.optString(idx).trim()
                    if (m.isNotBlank()) add(m)
                }
            }
        }

        val rawSearchUrl = o.optString("searchUrl")
            .ifBlank { o.optJSONObject("ruleSearch")?.optString("searchUrl").orEmpty() }
            .trim()
        val isJsSearch = rawSearchUrl.startsWith("@js:") || rawSearchUrl.contains("java.ajax") || rawSearchUrl.contains("java.connect")

        val searchO = o.optJSONObject("search")
        val ruleSearch = o.optJSONObject("ruleSearch")
        val parsedSearch = if (!isJsSearch && rawSearchUrl.isNotBlank()) parseSearchUrl(rawSearchUrl) else null
        val search = when {
            searchO != null -> SearchRule(
                url = searchO.optString("url"),
                method = searchO.optString("method", "GET"),
                body = searchO.optString("body"),
                list = searchO.optString("list"),
                fields = fieldsOf(searchO.optJSONObject("fields")),
                auto = searchO.optBoolean("auto", searchO.optString("list").isBlank()),
                extraUrls = buildList {
                    val arr = searchO.optJSONArray("extraUrls")
                    if (arr != null) {
                        for (idx in 0 until arr.length()) {
                            val u = arr.optString(idx).trim()
                            if (u.isNotBlank()) add(u)
                        }
                    }
                },
                keyCharset = searchO.optString("keyCharset", "utf-8"),
            )
            isJsSearch -> {
                val bookList = ruleSearch?.optString("bookList").orEmpty()
                SearchRule(
                    url = "/search?searchkey={key}",
                    method = "GET",
                    body = "",
                    list = readCss(bookList),
                    fields = mapOf(
                        "name" to readCss(ruleSearch?.optString("name").orEmpty()),
                        "bookUrl" to readCss(ruleSearch?.optString("bookUrl").orEmpty()),
                        "cover" to readCss(ruleSearch?.optString("coverUrl").orEmpty()),
                        "author" to readCss(ruleSearch?.optString("author").orEmpty()),
                        "intro" to readCss(ruleSearch?.optString("intro").orEmpty()),
                        "latest" to readCss(ruleSearch?.optString("lastChapter").orEmpty()),
                    ).filterValues { it.isNotBlank() },
                    auto = true,
                    keyCharset = "utf-8",
                )
            }
            ruleSearch != null || parsedSearch != null -> {
                val p = parsedSearch ?: parseSearchUrl(ruleSearch?.optString("searchUrl").orEmpty())
                val bookList = ruleSearch?.optString("bookList")
                    ?.ifBlank { o.optString("ruleArticles") }
                    ?: o.optString("ruleArticles")
                SearchRule(
                    url = p.url.ifBlank { "/search.php?searchkey={key}" },
                    method = p.method,
                    body = p.body,
                    list = readCss(bookList),
                    fields = mapOf(
                        "name" to readCss(ruleSearch?.optString("name").orEmpty()).ifBlank { readCss(o.optString("ruleTitle")) },
                        "bookUrl" to readCss(ruleSearch?.optString("bookUrl").orEmpty()).ifBlank { readCss(o.optString("ruleLink")) },
                        "cover" to readCss(ruleSearch?.optString("coverUrl").orEmpty()).ifBlank { readCss(o.optString("ruleImage")) },
                        "author" to readCss(ruleSearch?.optString("author").orEmpty()).ifBlank { readCss(o.optString("rulePubDate")) },
                        "intro" to readCss(ruleSearch?.optString("intro").orEmpty()),
                        "latest" to readCss(ruleSearch?.optString("lastChapter").orEmpty()),
                    ).filterValues { it.isNotBlank() },
                    auto = readCss(bookList).isBlank(),
                    keyCharset = p.charset.ifBlank { "utf-8" },
                )
            }
            else -> null
        }

        val detailO = o.optJSONObject("detail")
        val ruleBook = o.optJSONObject("ruleBookInfo")
        val detail = when {
            detailO != null -> DetailRule(
                detailO.optString("name"), detailO.optString("author"),
                detailO.optString("cover"), detailO.optString("intro"),
            )
            ruleBook != null -> DetailRule(
                name = readCss(ruleBook.optString("name")),
                author = readCss(ruleBook.optString("author")),
                cover = readCss(ruleBook.optString("coverUrl")),
                intro = readCss(ruleBook.optString("intro")),
            )
            else -> null
        }

        val tocO = o.optJSONObject("toc")
        val ruleToc = o.optJSONObject("ruleToc")
        val toc = when {
            tocO != null -> TocRule(
                list = tocO.optString("list"),
                name = tocO.optString("name", "@text"),
                url = tocO.optString("url", "@href"),
                page = tocO.optString("page"),
                link = tocO.optString("link"),
                skip = tocO.optString("skip"),
                volume = tocO.optString("volume"),
                skipVolume = tocO.optString("skipVolume"),
                nextPage = tocO.optString("nextPage"),
                maxPages = tocO.optInt("maxPages", 50).coerceIn(1, 100),
            )
            ruleToc != null -> TocRule(
                list = readCss(ruleToc.optString("chapterList")),
                name = readCss(ruleToc.optString("chapterName")).ifBlank { "@text" },
                url = readCss(ruleToc.optString("chapterUrl")).ifBlank { "@href" },
                nextPage = readCss(ruleToc.optString("nextTocUrl")),
            )
            o.has("ruleArticles") -> TocRule(
                list = readCss(o.optString("ruleArticles")),
                name = readCss(o.optString("ruleTitle")).ifBlank { "@text" },
                url = readCss(o.optString("ruleLink")).ifBlank { "@href" },
                nextPage = readCss(o.optString("ruleNextPage")),
            )
            else -> null
        }

        val contentO = o.optJSONObject("content")
        val ruleContentStr = when (val rc = o.opt("ruleContent")) {
            is JSONObject -> rc.optString("content")
            is String -> rc
            else -> ""
        }
        val content = when {
            contentO != null -> ContentRule(contentO.optString("body"), contentO.optString("next"))
            ruleContentStr.isNotBlank() -> {
                val cleaned = readCss(ruleContentStr)
                ContentRule(cleaned.ifBlank { "#content@html" })
            }
            else -> ContentRule("#content@html")
        }

        val charset = o.optString("charset").ifBlank {
            parsedSearch?.charset?.ifBlank { "utf-8" } ?: "utf-8"
        }
        return Source(
            id = o.optString("id").ifBlank { newId(base) },
            name = name,
            group = group,
            base = base,
            mirrors = mirrors,
            charset = charset,
            status = o.optString("status").ifBlank { "custom" },
            note = o.optString("note").ifBlank { "自己添加的源 · 点进详情可编辑规则" },
            search = search,
            detail = detail,
            toc = toc,
            content = content,
            apiKind = o.optString("apiKind"),
            enabledByDefault = o.optBoolean("enabledByDefault", false),
            probeKeyword = o.optString("probeKeyword"),
            probeUrl = o.optString("probeUrl"),
            probeMode = o.optString("probeMode", "SEARCH"),
            userAgent = o.optString("userAgent"),
            renderMode = o.optString("renderMode", "HTTP"),
        )
    }

    /** 订阅更新结果 */
    data class SubscriptionResult(
        val success: Boolean,
        val message: String,
        val importedCount: Int = 0,
        val sources: List<Source> = emptyList(),
    )

    /**
     * 在线书源订阅拉取与导入：
     *  - 从订阅 URL 拉取 JSON 书源列表
     *  - 校验格式结构与字段合法性（严禁非法脚本与恶意 payload）
     *  - 更新失败回滚机制：若网络失败或格式解析错误，回滚原配置并返回错误原因
     */
    fun importSubscription(url: String): SubscriptionResult {
        val trimmed = url.trim()
        if (trimmed.isBlank() || (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true))) {
            return SubscriptionResult(false, "无效的订阅地址：必须以 http:// 或 https:// 开头")
        }

        val backup = all()
        return try {
            val resp = Net.get(trimmed, charset = "utf-8", fast = true)
            if (!resp.ok) {
                return SubscriptionResult(false, "拉取订阅失败：${resp.error}")
            }
            val body = resp.body.trim()
            if (body.isBlank() || (!body.startsWith("[") && !body.startsWith("{"))) {
                return SubscriptionResult(false, "订阅内容格式错误：非标准 JSON")
            }

            // 规则与格式校验
            val parsed = parseAll(body)
            if (parsed.isEmpty()) {
                return SubscriptionResult(false, "订阅解析失败：未发现有效书源定义")
            }

            // 规则合规校验：必须有合法 base URL 与名称，不能含有 javascript: 伪协议
            for (s in parsed) {
                if (s.base.contains("javascript:", true) || s.name.contains("<script", true)) {
                    return SubscriptionResult(false, "书源规则安全校验未通过：检测到潜在脚本注入风险")
                }
            }

            // 合并与保存
            val currentMap = backup.associateBy { it.id }.toMutableMap()
            for (newSource in parsed) {
                currentMap[newSource.id] = newSource
                storeRef?.setSourceEnabled(newSource.id, true)
            }
            save(currentMap.values.toList())
            SubscriptionResult(true, "成功导入 ${parsed.size} 个书源", parsed.size, parsed)
        } catch (e: Exception) {
            save(backup)
            SubscriptionResult(false, "订阅更新失败，已回滚：${e.message}")
        }
    }

    private fun fieldsOf(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        return out
    }

    private fun normalizeBase(raw: String): String? {
        var b = raw.trim().trimEnd('/')
        if (b.isBlank()) return null
        if (!b.startsWith("http", true)) b = "https://$b"
        if (!b.startsWith("http://", true) && !b.startsWith("https://", true)) return null
        return b
    }

    /** 从字符串（单个对象 / 数组 / {"sources":[...]}）解析出全部源 */
    fun parseAll(raw: String): List<Source> {
        val text = raw.trim().trimStart('\uFEFF')
        val out = mutableListOf<Source>()
        runCatching {
            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                (0 until arr.length()).forEach { i ->
                    arr.optJSONObject(i)?.let { o -> fromJson(o)?.let { out += it } }
                }
            } else {
                val o = JSONObject(text)
                val inner = o.optJSONArray("sources")
                if (inner != null) {
                    (0 until inner.length()).forEach { i ->
                        inner.optJSONObject(i)?.let { s -> fromJson(s)?.let { out += it } }
                    }
                } else fromJson(o)?.let { out += it }
            }
        }
        return out
    }

    // ─────────── 自动探测搜索地址 ───────────

    /**
     * 打开站点首页，从搜索框所在的 form 里把搜索地址抠出来。
     * 用户不用懂 /search.php?searchkey={key} 这种路径，点一下就能填上。
     */
    fun probeSearch(base: String, charset: String = "utf-8"): SearchRule? {
        val home = Net.get(base, charset, base + "/", useCache = true)
        if (!home.ok) return null
        val doc = Rules.parse(home.body)
        for (f in doc.select("form")) {
            val inputs = f.select("input")
            val kw = inputs.firstOrNull { el ->
                val n = (el.attr("name") + " " + el.attr("id")).lowercase()
                n.contains("key") || n.contains("search") || n.contains("word") ||
                    n.trim() == "q" || n.contains("query")
            } ?: continue
            val field = kw.attr("name").ifBlank { kw.attr("id") }.trim()
            if (field.isBlank()) continue
            var action = f.attr("action").trim().ifBlank { "/" }
            if (action.startsWith("http", true)) {
                runCatching {
                    val u = java.net.URI(action)
                    action = (u.rawPath.ifBlank { "/" }) + (u.rawQuery?.let { "?$it" } ?: "")
                }
            }
            if (!action.startsWith("/")) action = "/$action"
            val isPost = f.attr("method").equals("post", true)
            return if (isPost) {
                val body = inputs.filter { it.attr("name").isNotBlank() }
                    .joinToString("&") { el ->
                        val n = el.attr("name").trim()
                        if (n.equals(field, true)) "$n={key}" else "$n=${el.attr("value")}"
                    }
                SearchRule(url = action, method = "POST", body = body, auto = true)
            } else {
                val sep = if (action.contains("?")) "&" else "?"
                SearchRule(url = "$action$sep$field={key}", method = "GET", auto = true)
            }
        }
        return null
    }

    // ─────────── 「阅读」格式的选择器转换 ───────────
    // 阅读语法：class.bookbox@tag.a@text    → .bookbox a@text
    // 已经是 CSS 的（含 . # > 空格）原样保留

    fun parseSearchUrl(raw: String): ParsedSearchUrl {
        var s = raw.trim()
        if (s.startsWith("{{") && s.contains("}}")) {
            s = s.substringAfter("}}").trim()
        }
        var method = "GET"
        var body = ""
        var charset = "utf-8"
        if (s.contains(",{") || s.contains(", {")) {
            val idx = if (s.contains(",{")) s.indexOf(",{") else s.indexOf(", {")
            val urlPart = s.substring(0, idx).trim()
            val jsonPart = s.substring(idx + 1).trim()
            val json = runCatching { JSONObject(jsonPart) }.getOrNull()
            if (json != null) {
                method = json.optString("method", "GET").uppercase()
                body = json.optString("body").replace("{{key}}", "{key}").replace("{key}", "{key}")
                charset = json.optString("charset").ifBlank { "utf-8" }
            }
            s = urlPart
        } else {
            s = s.substringBefore("##").trim()
        }
        s = s.replace("{{key}}", "{key}").replace("{{page}}", "1")
        if (!s.startsWith("http://") && !s.startsWith("https://") && !s.startsWith("/")) s = "/$s"
        return ParsedSearchUrl(s, method, body, charset)
    }

    /**
     * 把阅读的选择器语法翻成 CSS + @字段。
     * 认不出的部分就丢掉，宁可让自动识别兜底，也不要给一条错规则把页面解析歪。
     */
    fun readCss(raw: String): String {
        var r0 = raw.trim()
        if (r0.isBlank()) return ""
        if (r0.startsWith("@js:") || r0.startsWith("<js>")) return ""
        if (r0.contains("<js>")) r0 = r0.substringBefore("<js>").trim()
        if (r0.contains("@js:")) r0 = r0.substringBefore("@js:").trim()
        if (r0.startsWith("@css:")) r0 = r0.removePrefix("@css:").trim()

        var regexPart = ""
        if (r0.contains("##")) {
            val idx = r0.indexOf("##")
            regexPart = r0.substring(idx)
            r0 = r0.substring(0, idx).trim()
        }

        // 多规则取第一条能用的
        val r = r0.split("||").firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (r.isBlank()) return ""
        val parts = r.split("@").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val sels = mutableListOf<String>()
        var field = "text"
        var explicitField = false
        parts.forEachIndexed { idx, p ->
            val isLast = idx == parts.lastIndex
            when {
                p == "text" || p == "ownText" -> { field = "text"; explicitField = true }
                p == "html" || p == "all" -> { field = "html"; explicitField = true }
                p == "href" || p == "src" || p == "content" || p == "data-src" || p == "data-original" || p == "title" || p == "onclick" -> {
                    field = p; explicitField = true
                }
                p.startsWith("class.") -> sels += "." + p.removePrefix("class.")
                p.startsWith("id.") -> sels += "#" + p.removePrefix("id.")
                p.startsWith("tag.") -> sels += p.removePrefix("tag.")
                p == "children" || p.startsWith("index.") || p.startsWith("text.") -> Unit
                p.contains("@") -> Unit
                isLast && !p.contains(".") && !p.contains("#") && !p.contains(" ") && p != "a" -> {
                    field = p; explicitField = true
                }
                else -> sels += p
            }
        }
        if (sels.isEmpty() && !explicitField) return ""
        val css = sels.joinToString(" ") + if (explicitField) "@$field" else ""
        return css + (if (explicitField) regexPart else "")
    }

    /** 一键导入 JSON 书源文本（支持单个对象、数组、阅读3.0合集等） */
    fun importJsonText(raw: String): Pair<Int, String> {
        val parsed = parseAll(raw)
        if (parsed.isEmpty()) {
            return Pair(0, "未识别到有效书源，请检查 JSON 格式")
        }
        val current = all().associateBy { it.id }.toMutableMap()
        for (s in parsed) {
            current[s.id] = s
            storeRef?.setSourceEnabled(s.id, true)
        }
        save(current.values.toList())
        return Pair(parsed.size, "成功导入 ${parsed.size} 个书源并已自动启用")
    }

    /** 从本地 Uri 读取并导入 JSON 文件 */
    fun importJsonUri(context: Context, uri: android.net.Uri): Pair<Int, String> {
        return runCatching {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Pair(0, "无法打开所选文件")
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            importJsonText(text)
        }.getOrElse {
            Pair(0, "读取或导入文件失败：${it.message}")
        }
    }
}
