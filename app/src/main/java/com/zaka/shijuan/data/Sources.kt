package com.zaka.shijuan.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

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
    val keyCharset: String = "utf-8",      // 关键词编码：GBK 老站必须用 gbk，否则搜不到
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
    // 目录页地址模板：{id} = 书号，{book} = 原书页。留空表示目录就在书页里
    val page: String = "",
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
    val enabledByDefault: Boolean = false,
) {
    val bases: List<String> get() = (listOf(base) + mirrors).filter { it.isNotBlank() }.distinct()
}

object Sources {

    /** 内置源 */
    val ALL: List<Source> = listOf(

        // ─────────── 已实测可用（默认启用） ───────────
        Source(
            id = "biqugese", name = "笔趣书阁", group = "笔趣阁系",
            base = "https://www.biqugese.com", charset = "utf-8", status = "verified",
            enabledByDefault = true,
            note = "【推荐】搜索/目录/正文全通（实测），更新快，首选推荐",
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
            charset = "gbk", status = "local",
            note = "【备用】源站网关超时 504",
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
            enabledByDefault = true,
            note = "【推荐】繁体轻小说库。搜索/正文可用，更新稳定",
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
            note = "【备用】海外/部分地区 403 拦截",
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
            toc = TocRule(list = "#list-chapterAll a, .module-row-text, .module-volume-list a", name = "@text", url = "@href"),
            content = ContentRule(body = "#mlfy_main_text@html || .article-content@html"),
        ),

        Source(
            id = "ttkan", name = "天天看小说", group = "综合",
            base = "https://cn.ttkan.co", charset = "utf-8", status = "local",
            note = "【备用】源站 502 Bad Gateway",
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
            base = "https://www.sudugu.cc", charset = "utf-8", status = "local",
            note = "【备用】域名已搬至 sudugu.cc，国内老书冷门书",
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
            note = "【备用】源站返回空壳页",
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
            note = "【备用】源站 SSL 证书失效",
            search = SearchRule(url = "/search/?searchkey={key}", auto = true),
            toc = TocRule(list = "", name = "@text", url = "@href"),
            content = ContentRule(body = ""),
        ),

        Source(
            id = "deqixs", name = "得奇小说网", group = "综合",
            base = "https://www.deqixs.com", charset = "utf-8", status = "local",
            note = "【备用】源站直接掐断连接",
            search = SearchRule(url = "/tag/?key={key}", auto = true),
            content = ContentRule(body = ""),
        ),

        // ─────────── 正版源 ───────────
        Source(
            id = "qidian", name = "起点中文网", group = "正版",
            base = "https://www.qidian.com", mirrors = listOf("https://m.qidian.com"),
            charset = "utf-8", status = "local",
            note = "【备用】起点反爬 202 拦截",
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


        // ─────────── 轻小说（国内直连） ───────────
        Source(
            id = "linovelib", name = "哔哩轻小说", group = "轻小说",
            base = "https://www.linovelib.com", mirrors = listOf("https://www.bilinovel.com"),
            charset = "utf-8", status = "local",
            note = "【备用】源站改版，搜索路径 404",
            search = SearchRule(
                url = "/search.php?searchkey={key}",
                list = ".book-img-text li, .bookbox, .book-item",
                fields = mapOf(
                    "name" to "h4 a@text || h3 a@text || a@title",
                    "bookUrl" to "h4 a@href || h3 a@href || a@href",
                    "cover" to "img@data-src || img@src",
                    "author" to ".book-author@text || .author@text",
                    "intro" to "p.intro@text || .book-intro@text",
                ),
                auto = true,
            ),
            detail = DetailRule(
                name = "h1@text",
                author = ".book-author@text || .author@text",
                cover = ".book-img img@src || .book-layout img@src",
                intro = ".book-intro@text || #intro@text || .book-dec@text",
            ),
            toc = TocRule(
                list = "#vol_1 a, ul.catalogul a, .catalog a, .chapter-list a, .catalog ul li a",
                name = "@text", url = "@href",
            ),
            content = ContentRule(body = "#acontent@html || .read-content@html || #content@html"),
        ),

        Source(
            id = "wenku8", name = "轻小说文库", group = "轻小说",
            base = "https://www.wenku8.net", charset = "utf-8", status = "local",
            note = "【备用】海外/部分地区 403 拦截",
            search = SearchRule(
                url = "/modules/article/search.php?searchtype=articlename&searchkey={key}",
                keyCharset = "gbk",
                list = "table.css tr, table.grid tr, .grid tr",
                fields = mapOf(
                    "name" to "td:nth-child(1) a@text || td:nth-child(2) a@text",
                    "bookUrl" to "td:nth-child(1) a@href || td:nth-child(2) a@href",
                    "author" to "td:nth-child(3)@text",
                    "latest" to "td:nth-child(2) a@text || td:nth-child(4) a@text",
                ),
                auto = true,
            ),
            detail = DetailRule(name = "h1@text || #title@text", author = "#info a@text || .author@text", cover = "#fmimg img@src || .cover img@src", intro = "#intro@text || .intro@text"),
            toc = TocRule(list = "#content table td a, .css table td a, .chapter_list a", name = "@text", url = "@href"),
            content = ContentRule(body = "#content@html || #acontent@html"),
        ),

        Source(
            id = "sfacg", name = "SF轻小说", group = "轻小说",
            base = "https://m.sfacg.com", charset = "utf-8", status = "verified",
            enabledByDefault = true,
            note = "【推荐】正版轻小说官方接口，检索快，目录正文结构清晰",
            apiKind = "sfacg",
            search = SearchRule(url = "/API/HTML5.ashx?op=search&keyword={key}", auto = false),
            detail = DetailRule(
                name = ".book_newtitle@text",
                author = ".book_info3@text##\\s*/[\\s\\S]*##",
                cover = ".book_info img@src",
                intro = ".book_bk_qs1@text",
            ),
            // 书页 /b/{id}/ 没有章节列表，目录另有一页 /i/{id}/
            toc = TocRule(list = "ul.mulu_list a", name = "@text", url = "@href", page = "https://m.sfacg.com/i/{id}/"),
            content = ContentRule(body = ".Content_Frame > div@html || .yuedu > div@html || #content@html"),
        ),

        Source(
            id = "trxs", name = "同人小说", group = "轻小说",
            base = "https://www.trxs.cc", charset = "utf-8", status = "local",
            note = "【备用】源站改版，搜索路径 404",
            search = SearchRule(
                url = "/modules/article/search.php?searchkey={key}", auto = true,
                list = ".bookbox, .novel-item, .grid tr",
                fields = mapOf(
                    "name" to "h4 a@text || td:nth-child(1) a@text || a@title",
                    "bookUrl" to "h4 a@href || td:nth-child(1) a@href || a@href",
                    "author" to ".author@text || td:nth-child(3)@text",
                    "cover" to "img@src || img@data-src",
                ),
            ),
            detail = DetailRule(name = "h1@text", author = ".author@text || #info a@text", cover = "#fmimg img@src", intro = "#intro@text || .intro@text"),
            toc = TocRule(list = "#list dl dd a, .chapter-list a, #content table td a", name = "@text", url = "@href"),
            content = ContentRule(body = "#content@html || #acontent@html"),
        ),

        // ─────────── 综合补充 ───────────
        Source(
            id = "ptwxz", name = "飘天文学", group = "综合",
            base = "https://www.ptwxz.com", charset = "gbk", status = "local",
            enabledByDefault = true,
            note = "【推荐】老牌 GBK 完本站，书库大",
            search = SearchRule(
                url = "/modules/article/search.php?searchkey={key}", auto = true,
                list = ".grid tr, .bookbox, .novel-list li",
                fields = mapOf(
                    "name" to "td:nth-child(1) a@text || h4 a@text || a@title",
                    "bookUrl" to "td:nth-child(1) a@href || h4 a@href || a@href",
                    "author" to "td:nth-child(3)@text || .author@text",
                ),
            ),
            detail = DetailRule(name = "h1@text", author = "#info a@text || .author@text", cover = "#fmimg img@src", intro = "#intro@text || .intro@text"),
            toc = TocRule(list = "#list dl dd a, #content table td a, .chapter-list a", name = "@text", url = "@href"),
            content = ContentRule(body = "#content@html || #acontent@html"),
        ),

        Source(
            id = "ddxs", name = "顶点小说", group = "综合",
            base = "https://www.ddxs.com", charset = "gbk", status = "local",
            note = "【备用】海外/部分地区 403 拦截",
            search = SearchRule(
                url = "/modules/article/search.php?searchkey={key}", auto = true,
                list = ".grid tr, .bookbox, .novel-list li",
                fields = mapOf(
                    "name" to "td:nth-child(1) a@text || h4 a@text || a@title",
                    "bookUrl" to "td:nth-child(1) a@href || h4 a@href || a@href",
                    "author" to "td:nth-child(3)@text || .author@text",
                ),
            ),
            detail = DetailRule(name = "h1@text", author = "#info a@text || .author@text", cover = "#fmimg img@src", intro = "#intro@text || .intro@text"),
            toc = TocRule(list = "#list dl dd a, #content table td a, .chapter-list a", name = "@text", url = "@href"),
            content = ContentRule(body = "#content@html || #acontent@html"),
        ),

        Source(
            id = "xbiquge", name = "新笔趣阁", group = "笔趣阁系",
            base = "https://www.xbiquge.la", charset = "utf-8", status = "local",
            note = "【备用】源站 SSL 证书失效",
            search = SearchRule(
                url = "/modules/article/search.php?searchkey={key}", auto = true,
                list = ".grid tr, .bookbox, .novel-list li",
                fields = mapOf(
                    "name" to "td:nth-child(1) a@text || h4 a@text || a@title",
                    "bookUrl" to "td:nth-child(1) a@href || h4 a@href || a@href",
                    "author" to "td:nth-child(3)@text || .author@text",
                ),
            ),
            detail = DetailRule(name = "h1@text", author = "#info a@text || .author@text", cover = "#fmimg img@src", intro = "#intro@text || .intro@text"),
            toc = TocRule(list = "#list dl dd a, #content table td a, .chapter-list a", name = "@text", url = "@href"),
            content = ContentRule(body = "#content@html || #acontent@html"),
        ),

        // ─────────── 电子书 ───────────
        Source(
            id = "zlib", name = "Z-Library", group = "电子书",
            base = "https://z-lib.gd", mirrors = listOf("https://z-library.sk", "https://1lib.sk"),
            charset = "utf-8", status = "api", apiKind = "zlib",
            note = "【备用】全球最大的电子书库（EPUB/PDF），走官方 App 接口检索",
        ),
    )

    fun byId(id: String): Source? = all.firstOrNull { it.id == id }

    // ─────────── 自定义源（用户自己加的，运行时注册） ───────────

    /** 用户添加的源。改了之后 ++version，界面会跟着刷新 */
    @Volatile
    private var customs: List<Source> = emptyList()

    /** Compose 里读它，自定义源增删改都能自动重组 */
    var customVersion by mutableIntStateOf(0)
        private set

    val custom: List<Source> get() = customs

    /** 内置 + 自定义，搜索、详情、阅读全都走这个 */
    val all: List<Source> get() = if (customs.isEmpty()) ALL else ALL + customs

    fun installCustom(list: List<Source>) {
        customs = list
        customVersion++
    }

    /** 默认启用的源 id */
    val defaultEnabled: Set<String> = ALL.filter { it.enabledByDefault }.map { it.id }.toSet()
}
