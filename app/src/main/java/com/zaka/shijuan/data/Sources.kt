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

data class SourceRequestProfile(
    val userAgent: String = "",
    val accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    val referer: String = "",
    val cookie: String = "",
    val deviceType: String = "AUTO", // AUTO / DESKTOP / MOBILE
    val extraHeaders: Map<String, String> = emptyMap(),
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
    // 目录页地址模板：{id} = 书号，{idk} = 书号/1000，{book} = 原书页。留空表示目录就在书页里
    val page: String = "",
    // 从书籍详情页提取目录页链接的选择器（如 "a:contains(小说目录)@href"），留空表示不单独提取
    val link: String = "",
    // 章节名正则：命中的章节直接不进目录。用于站点把插图页/人设页当成章节列出来的情况
    // （这些章节正文只有图片，阅读器只显示文字，点进去必然空白）
    val skip: String = "",
    // 目录按「卷」分块时的卷标题选择器（SF：div.mulu 写着「人设」「第一卷 …」）
    val volume: String = "",
    // 卷名正则：命中整卷跳过。SF 的「人设」「书友画廊」整卷都是图片，收进来只会让人点开一片空白
    val skipVolume: String = "",
    // 目录下一页选择器（如 "a:contains(下一页)@href" 或 "a.next@href"）
    val nextPage: String = "",
    // 目录最多抓取页数（防环保护，默认 50 页）
    val maxPages: Int = 50,
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
    val probeKeyword: String = "",  // 专属探针关键词（留空时按分类默认：轻小说为"魔王"，其他为"凡人修仙传"）
    val probeUrl: String = "",      // 专属探针 URL
    val probeMode: String = "SEARCH", // "SEARCH" / "DIRECT_URL" / "HOMEPAGE"
    val userAgent: String = "",     // 源级独立 UA
    val renderMode: String = "HTTP", // "HTTP" / "WEBVIEW" / "AUTO"
    val requestProfile: SourceRequestProfile? = null,
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

        // ─────────── 国内网络直连（规则来自维护中的书源合集） ───────────
        Source(
            id = "sudugu", name = "速读谷", group = "综合",
            base = "https://www.sudugu.cc", charset = "utf-8", status = "local",
            note = "【推荐】老书冷门书全，搜索会直接跳到书页（已适配），正文干净",
            enabledByDefault = true,
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
            // 列表第一项常是「第1章」的重名分隔项，正文是空的，从第二项开始才是真章节
            toc = TocRule(list = "#list a", name = "@text", url = "@href"),
            content = ContentRule(body = ".con@html"),
        ),

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

        // ─────────── 正版源 ───────────
        Source(
            id = "17k", name = "17K小说网", group = "正版",
            base = "https://www.17k.com",
            charset = "utf-8", status = "verified",
            note = "【正版】17K 站内书，前若干章免费（后面的章节源站要购买）。目录走 /list/{id}.html",
            enabledByDefault = true,
            search = SearchRule(
                // 站内搜索是独立域名 search.17k.com，参数名就是 c.q
                url = "https://search.17k.com/search.xhtml?c.q={key}",
                list = "div.textlist",
                fields = mapOf(
                    "name" to ".textmiddle dt a@text",
                    "bookUrl" to ".textmiddle dt a@href",
                    "cover" to ".textleft img@src",
                    "author" to ".textmiddle .ls a@text",
                    "intro" to ".textmiddle li p@text",
                ),
            ),
            detail = DetailRule(
                name = "h1@text", author = "div.author a@text",
                cover = "#bookCover img@src", intro = ".intro@text",
            ),
            toc = TocRule(list = "a[href*=/chapter/]", name = "@text", url = "@href", page = "/list/{id}.html"),
            content = ContentRule(body = ".readArea div.p@html || .readArea@html"),
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
            base = "https://www.wenku8.cc",
            mirrors = listOf("https://www.wenku8.net", "https://www.wenku8.com", "https://wap.wenku8.cc"),
            charset = "gbk", status = "verified",
            enabledByDefault = true,
            note = "双载点直连 + 官方整本打包，收录日本轻小说与插图",
            search = SearchRule(
                url = "/modules/article/search.php?searchkey={key}&searchtype=articlename",
                method = "GET",
                extraUrls = listOf("/so.php"),
                body = "searchtype=articlename&searchkey={key}&charset=gbk",
                keyCharset = "gbk",
                list = "div[style*='width:373px'], div[style*='width: 373px'], #content table td > div, table td > div[style*='width'], table.grid tr, .grid tr",
                fields = mapOf(
                    "name" to "div:nth-of-type(2) b a@title || div:nth-of-type(2) a@title || b a@title || b a@text || td:nth-child(1) a@text || td:nth-child(2) a@text",
                    "bookUrl" to "div:nth-of-type(2) b a@href || div:nth-of-type(2) a@href || b a@href || div a[href*=/book/]@href || td:nth-child(1) a@href || td:nth-child(2) a@href",
                    "author" to "div:nth-of-type(2) p:contains(作)@text || p:contains(作)@text || td:nth-child(3)@text",
                    "cover" to "div:nth-of-type(1) img@src || img@src",
                    "latest" to "div:nth-of-type(2) p:contains(日)@text || p:contains(日)@text || td:nth-child(2) a@text || td:nth-child(4) a@text",
                    "intro" to "div:nth-of-type(2) p:contains(简)@text || div:nth-of-type(2) p:contains(介)@text || p:contains(简)@text",
                ),
                auto = true,
            ),
            detail = DetailRule(
                name = "h1@text || #title@text || title@text",
                author = "#info a@text || .author@text || td:contains(小说作者)@text",
                cover = "img[src*=image]@src || #fmimg img@src || .cover img@src",
                intro = "#intro@text || .intro@text || td:contains(内容简介)@text"
            ),
            toc = TocRule(
                page = "/novel/{idk}/{id}/index.htm",
                link = "a:contains(小说目录)@href",
                list = "table.css td.ccss a, table.css td a, td.ccss a, .chapter_list a",
                name = "@text",
                url = "@href",
                volume = "td.vcss",
            ),
            content = ContentRule(body = "div#content@html || #content@html || #acontent@html"),
            requestProfile = SourceRequestProfile(
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                referer = "https://www.wenku8.cc/",
                accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                deviceType = "DESKTOP",
                extraHeaders = mapOf("Accept-Language" to "zh-CN,zh;q=0.9"),
            ),
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
            toc = TocRule(
                list = "ul.mulu_list a", name = "@text", url = "@href",
                page = "https://m.sfacg.com/i/{id}/",
                volume = "div.mulu",
                skipVolume = "(人设|设定集|书友画廊|画廊|插画|插图|图集|彩图|彩页|线稿|草稿|相关|首发)",
                skip = "(插图|插画|图集|人设|设定集|彩图|彩页|线稿|草稿|图片版|漫画版|改命链接|链接|公告|群号|通知|下线说明|版权|免责|免责声明|文&图|首发|新书首发|投票|求票|收藏|求收藏|感言|加更|活动说明|试看)",
            ),
            content = ContentRule(body = "div.yuedu.Content_Frame > div@html || .Content_Frame > div@html || .yuedu [style*=\"text-indent\"]@html || [style*=\"text-indent\"]@html || .yuedu@html || #content@html"),
        ),

        Source(
            id = "faloo", name = "飞卢小说", group = "轻小说",
            base = "https://b.faloo.com", charset = "gbk", status = "verified",
            enabledByDefault = true,
            note = "【推荐】二次元/同人轻小说与原创小说，检索极快，更新活跃",
            search = SearchRule(
                url = "https://b.faloo.com/l/0/1.html?t=1&k={key}",
                list = ".TwoBox02",
                fields = mapOf(
                    "name" to "h1 a@title || a@title || h1 a@text",
                    "bookUrl" to "h1 a@href || a@href",
                    "cover" to "img@data-original || img@src",
                    "author" to ".TwoBox02_09 a@text || a[href*='t=2']@text",
                    "intro" to ".TwoBox02_06@text",
                    "latest" to ".TwoBox02_05@text",
                ),
                keyCharset = "gbk",
                auto = false,
            ),
            detail = DetailRule(
                name = "h1@text",
                author = ".TwoBox02_09 a@text || a[href*='t=2']@text || .author@text",
                cover = "img.TYImg@src || #fmimg img@src",
                intro = ".TwoBox02_06@text || #intro@text",
            ),
            toc = TocRule(list = "a[href*='_']", name = "@text", url = "@href"),
            content = ContentRule(body = "div.noveContent@html || .noveContent@html || #content@html"),
        ),

        // ─────────── 综合补充 ───────────
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
