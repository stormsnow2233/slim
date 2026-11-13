package com.storm.shijuan.data

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻小说文库（Wenku8）官方整本直下与解包引擎：
 *  - 绕过源站网页端的 Cloudflare 5 秒盾与地区限制（如日本 IP / 机房 IP 拦截）
 *  - 支持官方多节点智能容灾回退（node=1, node=2, node=3）与自定义 Cloudflare Worker / CDN 反代
 *  - 本地磁盘长效持久化解包缓存（一次解包，全卷永久秒开）
 *  - 自动解包切分为独立章节并注入两级正文缓存（NovelContentCache）
 *  - 支持全卷目录生成与单章瞬时秒开
 */
object Wenku8Direct {

    private const val TAG = "Wenku8Direct"

    private var cacheDir: File? = null
    private var store: Store? = null

    fun init(context: Context, st: Store? = null) {
        cacheDir = context.cacheDir
        if (st != null) store = st
    }

    // 内存中保存 aid -> (章节名 -> 正文) 映射，确保即使 URL 形式不同也能按名称秒级匹配
    private val contentByNameMap = ConcurrentHashMap<String, String>()
    private val contentByUrlMap = ConcurrentHashMap<String, String>()

    fun extractAid(url: String): Int? {
        val m = Regex("(?:/book/|/novel/\\d+/|id=|wenku8://)(\\d+)").find(url)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    fun directUrl(aid: Int, node: Int = 1): String {
        return "https://dl.wenku8.com/down.php?type=utf8&node=$node&id=$aid"
    }

    private fun getLocalCacheFile(aid: Int): File {
        val baseDir = cacheDir ?: File(".")
        val dir = File(baseDir, "wenku8_books")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "wenku8_raw_$aid.txt")
    }

    /**
     * 拉取文库官方全本并解包为目录（Toc）与正文缓存。
     * 策略：
     * 1. 优先读取磁盘长效持久化缓存（0ms 秒开）
     * 2. 若无本地文件，优先尝试用户自定义 CDN / Worker 反代
     * 3. 智能回退官方多节点（node=1, 2, 3）
     * 4. 成功后写回磁盘持久化
     */
    fun fetchAndUnpack(aid: Int): Result<Toc> {
        val localFile = getLocalCacheFile(aid)
        if (localFile.exists() && localFile.length() > 1000) {
            val cachedResult = runCatching {
                val cachedText = localFile.readText(Charsets.UTF_8)
                if (cachedText.length > 500 && !cachedText.contains("<html", ignoreCase = true)) {
                    val pairs = parseTxt(aid, cachedText)
                    if (pairs.isNotEmpty()) {
                        fillCache(aid, pairs)
                        val chapters = pairs.map { it.first }
                        Log.i(TAG, "Loaded aid=$aid from local disk cache, total chapters: ${chapters.size}")
                        Toc(chapters = chapters)
                    } else null
                } else null
            }.getOrNull()
            if (cachedResult != null) {
                return Result.success(cachedResult)
            }
        }

        // 构造候选下载地址列表
        val urls = mutableListOf<String>()
        val customProxy = store?.wenku8ProxyUrl().orEmpty()
        if (customProxy.isNotBlank()) {
            val proxyBase = customProxy.trimEnd('/')
            if (proxyBase.contains("{id}")) {
                urls.add(proxyBase.replace("{id}", aid.toString()))
            } else if (proxyBase.contains("down.php")) {
                urls.add("$proxyBase?type=utf8&node=1&id=$aid")
            } else {
                urls.add("$proxyBase/down.php?type=utf8&node=1&id=$aid")
            }
        }
        // 官方 3 个下载节点
        for (node in 1..3) {
            urls.add(directUrl(aid, node))
        }

        var lastError: Exception? = null
        for (url in urls) {
            Log.i(TAG, "Fetching direct wenku8 package for aid=$aid from $url")
            val bytes = Net.getBytes(url, referer = "https://www.wenku8.net/")
            if (bytes == null || bytes.size < 1000) {
                lastError = Exception("请求失败或返回内容过短: $url")
                continue
            }

            val text = Exporter.decodeNovelBytes(bytes)
            if (text.length < 500 || text.contains("<html", ignoreCase = true) || text.contains("<title>Just a moment", ignoreCase = true)) {
                lastError = Exception("文库直连未返回合法小说文本: $url")
                continue
            }

            val pairs = parseTxt(aid, text)
            if (pairs.isEmpty()) {
                lastError = Exception("未能从文库文本中解析出章节列表: $url")
                continue
            }

            // 持久化到本地磁盘缓存
            runCatching {
                localFile.writeText(text, Charsets.UTF_8)
            }

            fillCache(aid, pairs)
            val chapters = pairs.map { it.first }
            Log.i(TAG, "Successfully unpacked aid=$aid from $url, total chapters: ${chapters.size}")
            return Result.success(Toc(chapters = chapters))
        }

        return Result.failure(lastError ?: Exception("轻小说文库所有下载节点均不可用"))
    }

    private fun fillCache(aid: Int, pairs: List<Pair<Chapter, String>>) {
        for ((ch, content) in pairs) {
            contentByUrlMap[ch.url] = content
            val nameKey = "${aid}_${normalizeName(ch.name)}"
            contentByNameMap[nameKey] = content
            NovelContentCache.put("wenku8", ch.url, content)
        }
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

        // 3. 内存与缓存均未命中 → 触发整本拉取解包（优先走本地持久化磁盘）
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

        val chRegex = Regex(
            "^(?:" +
                "(?:第\\s*[一二三四五六七八九十百千万\\d]+\\s*[卷部篇集]\\s*)?第\\s*[一二三四五六七八九十百千万\\d]+\\s*[章节回话].*" +
                "|第\\s*[一二三四五六七八九十百千万\\d]+\\s*[卷部篇集].*" +
                "|序[曲章]?.*|尾声.*|终章.*|后记.*|插图.*|插画.*|番外.*|引子.*|幕间.*" +
                ")$"
        )
        val volRegex = Regex("^(第[一二三四五六七八九十百千万\\d]+[卷部篇集])")

        var curTitle = ""
        var curVol = ""
        val curLines = mutableListOf<String>()
        var chapterIndex = 0

        fun flush() {
            if (curTitle.isNotBlank()) {
                val chContent = curLines.joinToString("\n").trim()
                if (chContent.isBlank() && volRegex.matches(curTitle) && !curTitle.contains("章") && !curTitle.contains("话")) {
                    curLines.clear()
                    return
                }
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
