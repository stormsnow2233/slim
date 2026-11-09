package com.storm.shijuan.data

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻小说文库（Wenku8）官方整本直下与解包引擎：
 *  - 绕过源站网页端的 Cloudflare 5 秒盾与地区限制（如日本 IP / 机房 IP 拦截）
 *  - 直接通过 dl.wenku8.com 官方打包通道极速拉取整本所有分卷与章节
 *  - 自动解包切分为独立章节并注入两级正文缓存（NovelContentCache）
 *  - 支持全卷目录生成与单章瞬时秒开
 */
object Wenku8Direct {

    private const val TAG = "Wenku8Direct"

    // 内存中保存 aid -> (章节名 -> 正文) 映射，确保即使 URL 形式不同也能按名称秒级匹配
    private val contentByNameMap = ConcurrentHashMap<String, String>()
    private val contentByUrlMap = ConcurrentHashMap<String, String>()

    fun extractAid(url: String): Int? {
        val m = Regex("(?:/book/|/novel/\\d+/|id=|wenku8://)(\\d+)").find(url)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    fun directUrl(aid: Int): String {
        return "https://dl.wenku8.com/down.php?type=utf8&node=1&id=$aid"
    }

    /**
     * 拉取文库官方全本并解包为目录（Toc）与正文缓存
     */
    fun fetchAndUnpack(aid: Int): Result<Toc> {
        val url = directUrl(aid)
        Log.i(TAG, "Fetching direct wenku8 package for aid=$aid from $url")
        val bytes = Net.getBytes(url, referer = "https://www.wenku8.net/")
            ?: return Result.failure(Exception("轻小说文库直连通道请求失败"))

        if (bytes.size < 1000) {
            return Result.failure(Exception("文库返回内容过短或异常"))
        }

        val text = Exporter.decodeNovelBytes(bytes)
        if (text.length < 500 || text.contains("<html", ignoreCase = true) || text.contains("<title>Just a moment", ignoreCase = true)) {
            return Result.failure(Exception("文库直连未返回合法小说文本"))
        }

        val pairs = parseTxt(aid, text)
        if (pairs.isEmpty()) {
            return Result.failure(Exception("未能从文库文本中解析出章节列表"))
        }

        for ((ch, content) in pairs) {
            contentByUrlMap[ch.url] = content
            val nameKey = "${aid}_${normalizeName(ch.name)}"
            contentByNameMap[nameKey] = content
            NovelContentCache.put("wenku8", ch.url, content)
        }

        val chapters = pairs.map { it.first }
        Log.i(TAG, "Successfully unpacked aid=$aid, total chapters: ${chapters.size}")
        return Result.success(Toc(chapters = chapters))
    }

    /**
     * 获取某一章的正文（优先查内存、次查 NovelContentCache，若未解包则触发整本直下）
     */
    fun getChapterContent(aid: Int, chapter: Chapter): String? {
        // 1. 按具体 URL 查询
        contentByUrlMap[chapter.url]?.let { return it }
        NovelContentCache.get("wenku8", chapter.url)?.let { return it }

        // 2. 按 aid + 章节名查询
        val nameKey = "${aid}_${normalizeName(chapter.name)}"
        contentByNameMap[nameKey]?.let { return it }

        // 3. 内存与缓存均未命中 → 触发整本拉取解包
        val unpackRes = fetchAndUnpack(aid)
        if (unpackRes.isSuccess) {
            contentByUrlMap[chapter.url]?.let { return it }
            contentByNameMap[nameKey]?.let { return it }
            // 按索引相近或匹配章节
            val found = unpackRes.getOrNull()?.chapters?.firstOrNull {
                normalizeName(it.name) == normalizeName(chapter.name) || it.name.contains(chapter.name) || chapter.name.contains(it.name)
            }
            if (found != null) {
                val matched = contentByUrlMap[found.url]
                if (matched != null) {
                    // 同步缓存到请求的 chapter.url 下
                    NovelContentCache.put("wenku8", chapter.url, matched)
                    return matched
                }
            }
        }
        return null
    }

    private fun normalizeName(name: String): String {
        return name.replace(Regex("[\\s\\t\\r\\n　]+"), "").trim()
    }

    fun parseTxt(aid: Int, text: String): List<Pair<Chapter, String>> {
        val lines = text.split("\r\n", "\n")
        val chapters = mutableListOf<Pair<Chapter, String>>()

        val chRegex = Regex("^(第[一二三四五六七八九十百千万\\d]+[卷部篇集]?\\s*第?[一二三四五六七八九十百千万\\d]+[章节回话].*|第[一二三四五六七八九十百千万\\d]+[卷部篇集].*|序[曲章]?|尾声|终章|后记|插图|插画|番外.*)$")
        val volRegex = Regex("^(第[一二三四五六七八九十百千万\\d]+[卷部篇集])")

        var curTitle = ""
        var curVol = ""
        val curLines = mutableListOf<String>()
        var chapterIndex = 0

        fun flush() {
            if (curTitle.isNotBlank()) {
                val chContent = curLines.joinToString("\n").trim()
                val chUrl = "wenku8://$aid/chapter_$chapterIndex"
                val chapter = Chapter(
                    name = curTitle,
                    url = chUrl,
                    index = chapterIndex,
                    volume = curVol
                )
                chapters.add(chapter to chContent)
                curLines.clear()
                chapterIndex++
            }
        }

        for (i in lines.indices) {
            val line = lines[i]
            val s = line.trim()
            // 过滤文库头部横幅和小说名
            if (i < 6 && (s.contains("WenKu8", ignoreCase = true) || (s.startsWith("<") && s.endsWith(">")))) {
                continue
            }
            if (s.isNotEmpty() && s.length <= 45 && chRegex.matches(s)) {
                flush()
                curTitle = s
                val m = volRegex.find(s)
                if (m != null) {
                    curVol = m.groupValues[1]
                }
            } else {
                if (curTitle.isNotBlank()) {
                    curLines.add(line)
                }
            }
        }
        flush()

        return chapters
    }
}
