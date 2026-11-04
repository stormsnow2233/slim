package com.zaka.shijuan.data

/**
 * 书源定义。
 * 规则语法（简化版「阅读」规则）：
 *   ".bookbox a@href"   选择器@字段（text / html / texts / 任意属性名）
 *   ".a@text || .b@text"      左失败用右
 *   ".a@text##正则##替换"      文本后处理
 *   ""（空）                   整页当作根
 */
data class SearchRule(
    val url: String,                       // 相对路径，{key} 会被替换
    val method: String = "GET",            // GET / POST
    val body: String = "",                 // POST 体，{key} 替换
    val list: String = "",
    val fields: Map<String, String> = emptyMap(),
    val auto: Boolean = false,             // 规则认不出时启用自动识别
    val extraUrls: List<String> = emptyList(),
)

data class DetailRule(
    val name: String = "",
    val author: String = "",
    val cover: String = "",
    val intro: String = "",
)

data class TocRule(
    val list: String = "",
    val name: String = "@text",
    val url: String = "@href",
)

data class ContentRule(
    val body: String = "",
    val next: String = "",
)

data class Source(
    val id: String,
    val name: String,
    val group: String,
    val base: String,
    val mirrors: List<String> = emptyList(),
    val charset: String = "utf-8",
    val status: String = "local",   // verified 已实测 / local 国内网络 / link 跳转 / api 接口
    val note: String = "",
    val search: SearchRule? = null,
    val detail: DetailRule? = null,
    val toc: TocRule? = null,
    val content: ContentRule? = null,
    val apiKind: String = "",       // "zlib"
    val enabledByDefault: Boolean = true,
) {
    val bases: List<String> get() = (listOf(base) + mirrors).filter { it.isNotBlank() }.distinct()
}

object Sources {

    val ALL: List<Source> = listOf(

        // ─────────── 已实测可用 ───────────
        Source(
            id = "biqugese", name = "笔趣书阁", group = "笔趣阁系",
            base = "https://www.biqugese.com", charset = "utf-8", status = "verified",
            note = "搜索、目录、正文全通（实测），更新快，优先用这个",
            search = SearchRule(
                url = "/search/result.html?searchkey={key}",
                list = "ul.librarylist > li",
                fields = mapOf(
                    "name" to "a.novelname@text",
                    "bookUrl" to "a.novelname@href",
                    "cover" to ".pt-ll-l img@src",
                    "author" to ".info a[href*=\"/author/\"]@text",
                    "intro" to "p.intro@text",
                    "latest" to "p.last a@text",
                ),
            ),
            detail = DetailRule(
                name = "h1@text", author = ".novelinfo a[href*=\"/author/\"]@text",
                cover = ".novelinfo-r img@src", intro = ".intro@text",
            ),
            toc = TocRule(list = "ul.dirlist li a", name = "@text", url = "@href"),
            content = ContentRule(body = ".readbox .content@html"),
        ),

        Source(
            id = "biquge5200", name = "笔趣阁 5200", group = "笔趣阁系",
            base = "https://www.bqg9.xyz", mirrors = listOf("https://www.biquge5200.cc"),
            charset = "gbk", status = "verified",
            note = "老牌站，书库大，编码 GBK（已内置解码）。实测搜索/目录/正文全通",
            search = SearchRule(
                url = "/modules/article/search.php?searchkey={key}",
                list = "table.grid tr",
                fields = mapOf(
                    "name" to "td:nth-child(1) a@text",
                    "bookUrl" to "td:nth-child(1) a@href",
                    "author" to "td:nth-child(3)@text",
                    "latest" to "td:nth-child(2) a@text",
                ),
            ),
            detail = DetailRule(name = "h1@text", cover = "#fmimg img@src", intro = "#intro@text"),
            toc = TocRule(list = "#list dl dd a", name = "@text", url = "@href"),
            content = ContentRule(body = "#content@html"),
        ),

        Source(
            id = "novel8", name = "无限轻小说", group = "轻小说",
            base = "https://www.8novel.com", charset = "utf-8", status = "verified",
            note = "繁体轻小说库。搜索可用；章节要在站内网页看，遇到打不开就用「浏览器打开」",
            search = SearchRule(
                url = "/search/?key={key}",
                list = ".card .col-4",
                fields = mapOf(
                    "name" to "a.text-blue@text",
                    "bookUrl" to "a.text-blue@href",
                    "cover" to "img@src",
                ),
            ),
            detail = DetailRule(
                name = "meta[property=\"og:novel:book_name\"]@content",
                author = "meta[property=\"og:novel:author\"]@content",
                cover = "meta[property=\"og:image\"]@content",
                intro = "meta[property=\"og:description\"]@content",
            ),
            toc = TocRule(list = "a[href*=\"/read/\"]", name = "@text", url = "@href"),
            content = ContentRule(body = "#text@html || #content .text@html"),
        ),

        // ─────────── 国内网络直连（规则来自维护中的书源合集） ───────────
        Source(
            id = "qianbi", name = "铅笔小说", group = "综合",
            base = "https://www.23qb.com", charset = "utf-8", status = "local",
            note = "国内直连，轻小说与网文都全，排版干净",
            search = SearchRule(
                url = "/search.html?searchkey={key}",
                list = ".module-search-item",
                fields = mapOf(
                    "name" to "a@title",
                    "bookUrl" to "a@href",
                    "cover" to "img@data-src",
                    "intro" to ".novel-info-item@text",
                ),
                auto = true,
            ),
            detail = DetailRule(
                name = "h1@text || .book-name@text",
                author = ".author a@text",
                cover = ".book-img img@src || .cover img@src",
                intro = ".book-dec@text || .intro@text",
            ),
            toc = TocRule(list = ".module-row-text", name = "span@text || @text", url = "@href"),
            content = ContentRule(body = ".article-content@html"),
        ),

        Source(
            id = "ttkan", name = "天天看小说", group = "综合",
            base = "https://cn.ttkan.co", charset = "utf-8", status = "local",
            note = "繁体站，玄幻修真类很全，章节更新快",
            search = SearchRule(
                url = "/novel/search?q={key}",
                list = ".novel_cell",
                fields = mapOf(
                    "name" to "h3@text",
                    "bookUrl" to "a@href",
                    "cover" to "amp-img@src",
                    "author" to "li:nth-child(2)@text",
                    "intro" to "li:nth-child(3)@text",
                ),
                auto = true,
            ),
            detail = DetailRule(name = "h1@text", author = ".author@text", intro = ".description@text"),
            toc = TocRule(list = ".full_chapters a", name = "@text", url = "@href"),
            content = ContentRule(body = ".content p@texts || .content@html"),
        ),

        Source(
            id = "sudugu", name = "速读谷", group = "综合",
            base = "https://www.sudugu.co", charset = "utf-8", status = "local",
            note = "国内直连，老书冷门书比较多",
            search = SearchRule(
                url = "/modules/article/search.php", method = "POST",
                body = "searchkey={key}&action=login&searchtype=all&submit=",
                list = ".bookbox",
                fields = mapOf(
                    "name" to "a@text",
                    "bookUrl" to "a@href",
                    "author" to ".author@text",
                    "intro" to ".update@text",
                    "latest" to ".cat a@text",
                ),
                auto = true,
            ),
            detail = DetailRule(name = "h1@text", author = ".author@text", intro = ".intro@text"),
            toc = TocRule(list = "#list a", name = "@text", url = "@href"),
            content = ContentRule(body = ".con@html"),
        ),

        Source(
            id = "shoujix", name = "手机小说", group = "综合",
            base = "https://www.shoujix.com", charset = "gbk", status = "local",
            note = "GBK 老站，书库偏完本",
            search = SearchRule(
                url = "/modules/article/search.php", method = "POST",
                body = "searchkey={key}",
                list = ".novel-item",
                fields = mapOf(
                    "name" to "img@alt",
                    "bookUrl" to "a@href",
                    "cover" to "img@src",
                    "author" to ".novel-author@text",
                    "intro" to ".novel-intro@text",
                ),
                auto = true,
            ),
            detail = DetailRule(name = "h1@text", author = ".author@text", intro = ".intro@text"),
            toc = TocRule(list = "#lbks dd a || #list dd a", name = "@text", url = "@href"),
            content = ContentRule(body = "#zjny@html"),
        ),

        Source(
            id = "kuaishu", name = "快书网", group = "综合",
            base = "https://www.kuaishu5.com", charset = "utf-8", status = "local",
            note = "国内直连，走通用识别，冷门书命中率高",
            search = SearchRule(url = "/search/?searchkey={key}", auto = true),
            toc = TocRule(list = "", name = "@text", url = "@href"),
            content = ContentRule(body = ""),
        ),

        Source(
            id = "deqixs", name = "得奇小说网", group = "综合",
            base = "https://www.deqixs.com", charset = "utf-8", status = "local",
            note = "国内直连，分类标签检索，走通用识别",
            search = SearchRule(url = "/tag/?key={key}", auto = true),
            content = ContentRule(body = ""),
        ),

        // ─────────── 正版源 ───────────
        Source(
            id = "qidian", name = "起点中文网", group = "正版",
            base = "https://www.qidian.com", mirrors = listOf("https://m.qidian.com"),
            charset = "utf-8", status = "local",
            note = "正版源头。免费章节可读，正文有自定义字体加密（个别字会显示异常）；VIP 章节需购买",
            search = SearchRule(
                url = "/so/{key}.html", auto = true,
                list = "ul.book-img-text > li, .book-mid-info",
                fields = mapOf(
                    "name" to ".book-mid-info h2 a@text",
                    "bookUrl" to ".book-mid-info h2 a@href",
                    "cover" to ".book-img-box img@src",
                    "author" to ".author a.name@text",
                    "intro" to ".intro@text",
                ),
            ),
            detail = DetailRule(name = "h1@text", author = ".author a@text", cover = ".book-img img@src", intro = ".book-intro@text"),
            toc = TocRule(list = ".catalog-content-wrap a[href*=\"/chapter\"], .volume-wrap a[href*=\"/chapter\"]", name = "@text", url = "@href"),
            content = ContentRule(body = ".read-content@html || .content@html"),
        ),

        // ─────────── 电子书 ───────────
        Source(
            id = "zlib", name = "Z-Library", group = "电子书",
            base = "https://z-lib.gd", mirrors = listOf("https://z-library.sk", "https://1lib.sk"),
            charset = "utf-8", status = "api", apiKind = "zlib",
            note = "全球最大的电子书库（EPUB/PDF），走官方 App 接口检索，点开可看详情与下载页",
        ),
    )

    fun byId(id: String): Source? = ALL.find { it.id == id }

    /** 默认启用的源 id */
    val defaultEnabled: Set<String> = ALL.filter { it.enabledByDefault }.map { it.id }.toSet()
}
