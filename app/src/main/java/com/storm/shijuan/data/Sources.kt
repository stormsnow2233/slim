package com.storm.shijuan.data

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

    /** 内置官方书源（AES-128 加密存储于 BuiltinSourceVault，运行时安全解密） */
    val ALL: List<Source> = BuiltinSourceVault.load()

    val LOCAL = Source(
        id = "local",
        name = "本地导入",
        group = "本地",
        base = "file://",
        status = "verified",
        enabledByDefault = true,
    )

    fun byId(id: String): Source? = if (id == "local") LOCAL else all.firstOrNull { it.id == id }

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
