package com.zaka.shijuan.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 规则解析引擎（移植自原网页版的核心逻辑，改用 Jsoup 实现）
 * 语法：
 *   selector@text / @html / @texts / @own / @任意属性
 *   a || b                左失败用右
 *   selector@text##正则##替换
 *   空规则 = 根元素自身
 */
object Rules {

    private fun pick(root: Element, selector: String): Element? {
        if (selector.isBlank()) return root
        return runCatching { root.selectFirst(selector) }.getOrNull()
    }

    private fun pickAll(root: Element, selector: String): List<Element> {
        if (selector.isBlank()) return listOf(root)
        return runCatching { root.select(selector).toList() }.getOrDefault(emptyList())
    }

    private val REGEX_SPEC = Regex("^#+(.+?)#+(.*)$", RegexOption.DOT_MATCHES_ALL)

    private fun applyRegex(s: String, spec: String): String {
        val m = REGEX_SPEC.find(spec) ?: return s
        return runCatching { s.replace(Regex(m.groupValues[1]), m.groupValues[2]) }.getOrDefault(s)
    }

    private fun readValue(el: Element?, field: String): String {
        if (el == null) return ""
        return when (field) {
            "text" -> el.text().replace(Regex("\\s+"), " ").trim()
            "html" -> el.html()
            "outer" -> el.outerHtml()
            "own" -> {
                val own = el.ownText().trim()
                if (own.isNotEmpty()) own else el.text().trim()
            }
            "texts" -> el.children().joinToString("\n") { it.text().trim() }
                .ifBlank { el.text().trim() }
            else -> el.attr(field).trim()
        }
    }

    /** 按一条规则取值 */
    fun extract(root: Element, rule: String, baseUrl: String): String {
        if (rule.isBlank()) return ""
        for (branch in rule.split("||")) {
            var expr = branch.trim()
            if (expr.isEmpty()) continue

            var regex = ""
            val hashIdx = expr.indexOf('#', 1)
            if (hashIdx > 0) {
                regex = expr.substring(hashIdx)
                expr = expr.substring(0, hashIdx)
            }

            val at = expr.lastIndexOf('@')
            val selector: String
            val field: String
            if (at >= 0) {
                selector = expr.substring(0, at).trim()
                field = expr.substring(at + 1).trim().ifBlank { "text" }
            } else {
                selector = expr
                field = "text"
            }

            val el = pick(root, selector)
            var v = readValue(el, field)
            if (v.isNotEmpty()) {
                if (regex.isNotEmpty()) v = applyRegex(v, regex)
                if (field == "href" || field == "src" || field == "data-src" || field == "content") {
                    v = absUrl(baseUrl, v)
                }
                if (v.isNotEmpty() && v != baseUrl) return v
            }
        }
        return ""
    }

    /** 按规则取列表 */
    fun extractList(
        root: Element,
        listSel: String,
        fields: Map<String, String>,
        baseUrl: String,
    ): List<Map<String, String>> {
        val items = pickAll(root, listSel)
        val out = ArrayList<Map<String, String>>()
        for (item in items) {
            val row = HashMap<String, String>()
            var any = false
            for ((k, rule) in fields) {
                val v = extract(item, rule, baseUrl)
                if (v.isNotEmpty()) any = true
                row[k] = v
            }
            if (any) out.add(row)
        }
        return out
    }

    fun absUrl(base: String, url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:" + url
        if (url.startsWith("data:")) return url
        return runCatching {
            val b = java.net.URI(base)
            b.resolve(url).toString()
        }.getOrDefault(url)
    }

    fun parse(html: String): Document = Jsoup.parse(html)

    // ─────────── 自动识别（不知道站点结构时兜底） ───────────

    private val BOOK_HREF = Regex("/(?:book|novel|read|txt|info|html)?/?\\d+(?:[_-]\\d+)?(?:\\.html?|/)?$|/\\d+[_-]\\d+/?$|/book/\\d+/")

    private fun normPattern(href: String): String =
        href.replace(Regex("https?://[^/]+"), "").replace(Regex("\\d+"), "N")

    private fun linkText(a: Element): String =
        (a.attr("title").ifBlank { a.text() }).replace(Regex("\\s+"), " ").trim()

    fun autoList(doc: Document, baseUrl: String, chapterMode: Boolean = false): List<Book> {
        val groups = LinkedHashMap<String, MutableList<Triple<Element, String, String>>>()

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript")) continue
            val text = linkText(a)
            if (text.isEmpty() || text.length > 60) continue
            val looks = if (chapterMode) {
                Regex("\\d{4,}").containsMatchIn(href) || Regex("第.{1,8}[章节回]").containsMatchIn(text)
            } else {
                BOOK_HREF.containsMatchIn(href) || Regex("\\d{2,}").containsMatchIn(href)
            }
            if (!looks) continue
            val key = normPattern(href)
            groups.getOrPut(key) { ArrayList() }.add(Triple(a, href, text))
        }

        var best: List<Triple<Element, String, String>> = emptyList()
        for (arr in groups.values) if (arr.size > best.size) best = arr
        if (best.size < 2) return emptyList()

        return best.mapNotNull { (a, href, text) ->
            val abs = absUrl(baseUrl, href)
            var box: Element? = a.parent()
            var i = 0
            while (i < 3 && box != null) {
                if (box.selectFirst("img, .author, [class*=author]") != null) break
                box = box.parent(); i++
            }
            val img = box?.selectFirst("img")
            val cover = img?.let {
                absUrl(baseUrl, it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("data-original") })
            } ?: ""
            var author = ""
            if (box != null) {
                val t = box.text().replace(Regex("\\s+"), " ")
                Regex("作\\s*者[:：]\\s*([^\\s|/]{1,20})").find(t)?.let { author = it.groupValues[1] }
            }
            val intro = box?.selectFirst("p, dd")?.text()?.replace(Regex("\\s+"), " ")?.take(200) ?: ""
            if (text.isEmpty()) null else Book(name = text, cover = cover, url = abs, author = author, intro = intro)
        }
    }

    /** 自动认出正文容器 */
    fun autoContent(doc: Document): String {
        var best: Element? = null
        var bestScore = 0
        for (el in doc.select("div, article, section, td")) {
            if (el.selectFirst("div, article, section") != null) continue
            val text = el.text().replace(Regex("\\s+"), "")
            if (text.length < 300) continue
            val linkLen = el.select("a").sumOf { it.text().length }
            val pCount = el.select("p").size
            val score = text.length - linkLen * 6 + pCount * 40
            if (score > bestScore) { bestScore = score; best = el }
        }
        val b = best ?: return ""
        val ps = b.select("p")
        if (ps.size >= 3) return ps.joinToString("") { "<p>${it.html()}</p>" }
        return b.html().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "</p><p>")
    }

    fun cleanText(html: String): String {
        val d = Jsoup.parse("<div>$html</div>")
        return d.text()
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private val AD = Regex(
        "(最新网址|本站网址|请记住本站|一秒记住|手机用户请浏览|本章未完|点击下一页|加入书签|" +
            "www\\.[a-z0-9]+\\.(com|net|cc|la|info|org)|m\\.[a-z0-9]+\\.(com|net|cc))",
        RegexOption.IGNORE_CASE
    )

    /** 去掉正文里混进来的广告行 */
    fun stripAds(html: String): String {
        val d = Jsoup.parse("<div id=\"__wrap\">$html</div>")
        val wrap = d.getElementById("__wrap") ?: return html
        for (el in wrap.select("p, div, span")) {
            if (el.selectFirst("p, div") == null) {
                val t = el.text().trim()
                if (t.isNotEmpty() && t.length < 70 && AD.containsMatchIn(t)) el.remove()
            }
        }
        return wrap.html()
    }

    /** 把正文 HTML 规范成段落文本（保留段落换行） */
    fun htmlToParagraphs(html: String): String {
        val d = Jsoup.parse("<div id=\"__c\">$html</div>")
        val c = d.getElementById("__c") ?: return cleanText(html)
        c.select("script, style, iframe").remove()

        val sb = StringBuilder()
        val blocks = setOf("p", "div", "br", "h1", "h2", "h3", "li", "tr", "section", "article", "dd")

        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is org.jsoup.nodes.TextNode -> sb.append(node.text())
                is Element -> {
                    val tag = node.tagName().lowercase()
                    val isBlock = tag in blocks
                    if (isBlock) sb.append('\n')
                    node.childNodes().forEach { walk(it) }
                    if (isBlock) sb.append('\n')
                }
            }
        }
        c.childNodes().forEach { walk(it) }

        return sb.toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]{2,}"), " ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
