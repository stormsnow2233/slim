package com.zaka.shijuan.data

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap

/**
 * 小说正文两级缓存引擎：
 *  - L1 内存 LRU：保留最近 50 章正文，支撑极速翻页与瞬时回看
 *  - L2 磁盘缓存：独立文件存储，默认 24 小时 TTL，最大容量 150MB（超额自动按 LRU 淘汰至 120MB）
 *  - 统一安全 Key：sourceId + "_" + SHA-256(normalizedChapterUrl)
 *  - 严格有效性校验：错误页、Cloudflare 质询、403、VIP 付费拦截与转码伪正文严禁入库
 */
object NovelContentCache {

    const val MAX_MEMORY_CHAPTERS = 50
    const val MAX_DISK_BYTES = 150L * 1024 * 1024   // 150 MB
    const val TARGET_DISK_BYTES = 120L * 1024 * 1024 // 超限后清理至 120 MB
    const val DEFAULT_TTL_MS = 24L * 3600 * 1000L    // 24 小时 TTL

    @Volatile
    private var diskCacheDir: File? = null

    // L1: 内存 LRU（50 章）
    private val memoryLru = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_MEMORY_CHAPTERS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_MEMORY_CHAPTERS
            }
        }
    )

    fun init(context: Context) {
        if (diskCacheDir == null) {
            val dir = File(context.cacheDir, "novel_chapters_v2")
            if (!dir.exists()) dir.mkdirs()
            diskCacheDir = dir
        }
    }

    /** 供单元测试注入临时缓存目录 */
    fun setCacheDirForTest(dir: File) {
        if (!dir.exists()) dir.mkdirs()
        diskCacheDir = dir
        memoryLru.clear()
    }

    /** 规范化章节 URL：去除波动性请求参数、时间戳，转换为小写 host + path */
    fun normalizeChapterUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        return runCatching {
            val uri = java.net.URI(trimmed)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty()
            val filteredQuery = uri.query?.split("&")
                ?.filterNot { p ->
                    val k = p.substringBefore('=').lowercase()
                    k == "t" || k == "_" || k == "timestamp" || k == "rnd" || k == "random"
                }
                ?.sorted()
                ?.joinToString("&")
            if (filteredQuery.isNullOrBlank()) "$host$path" else "$host$path?$filteredQuery"
        }.getOrDefault(trimmed.lowercase())
    }

    private fun sha256Hex(str: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(str.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 统一缓存 Key：sourceId + "_" + SHA-256(normalizedChapterUrl) */
    fun buildKey(sourceId: String, chapterUrl: String): String {
        val norm = normalizeChapterUrl(chapterUrl)
        val hash = sha256Hex(norm)
        val sid = sourceId.trim().ifBlank { "unknown" }
        return "${sid}_$hash"
    }

    /** 严格校验正文合法性：过滤空、过短、防盗报错、Cloudflare 质询与登录拦截页 */
    fun isValidContent(text: String): Boolean {
        if (text.isBlank() || text.trim().length < 20) return false
        if (BookRepo.isTranscodeOrPiracyError(text)) return false
        val lower = text.lowercase()
        if (lower.contains("just a moment") || lower.contains("cf-browser-verification") || lower.contains("cloudflare")) {
            return false
        }
        if (lower.contains("403 forbidden") || lower.contains("error 1020") || lower.contains("access denied")) {
            return false
        }
        if (text.contains("付费章节") || text.contains("VIP章节") || text.contains("请登录后阅读") || text.contains("本章需订阅")) {
            return false
        }
        return true
    }

    /** 获取正文：先查 L1 内存，未命中查 L2 磁盘（校验 TTL） */
    fun get(sourceId: String, chapterUrl: String, maxAgeMs: Long = DEFAULT_TTL_MS): String? {
        val key = buildKey(sourceId, chapterUrl)
        // 1. L1 内存命中
        memoryLru[key]?.let { return it }

        // 2. L2 磁盘检索
        val dir = diskCacheDir ?: return null
        val file = File(dir, "$key.txt")
        if (!file.exists() || !file.isFile) return null

        val now = System.currentTimeMillis()
        val fileAge = now - file.lastModified()
        if (maxAgeMs > 0 && fileAge > maxAgeMs) {
            // 过期失效
            file.delete()
            return null
        }

        val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return null
        if (!isValidContent(text)) {
            file.delete()
            return null
        }

        // 回填 L1 内存并刷新磁盘访问时间
        memoryLru[key] = text
        file.setLastModified(now)
        return text
    }

    /** 保存正文：写入 L1 内存与 L2 磁盘，并触发容量治理 */
    fun put(sourceId: String, chapterUrl: String, text: String) {
        if (!isValidContent(text)) return
        val key = buildKey(sourceId, chapterUrl)
        memoryLru[key] = text

        val dir = diskCacheDir ?: return
        runCatching {
            val file = File(dir, "$key.txt")
            file.writeText(text, StandardCharsets.UTF_8)
            file.setLastModified(System.currentTimeMillis())
            trimDiskUsageIfNeeded(dir)
        }
    }

    fun remove(sourceId: String, chapterUrl: String) {
        val key = buildKey(sourceId, chapterUrl)
        memoryLru.remove(key)
        val dir = diskCacheDir ?: return
        File(dir, "$key.txt").delete()
    }

    /** 清空正文缓存（内存与磁盘） */
    fun clearAll() {
        memoryLru.clear()
        val dir = diskCacheDir ?: return
        dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { it.delete() }
    }

    /** 磁盘缓存容量自动淘汰：超过 150MB 时淘汰最旧文件至 120MB */
    private fun trimDiskUsageIfNeeded(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
        var currentBytes = files.sumOf { it.length() }
        if (currentBytes <= MAX_DISK_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            val len = f.length()
            if (f.delete()) {
                currentBytes -= len
                if (currentBytes <= TARGET_DISK_BYTES) break
            }
        }
    }

    /** 统计当前磁盘缓存总字节数 */
    fun totalDiskBytes(): Long {
        val dir = diskCacheDir ?: return 0L
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sumOf { it.length() } ?: 0L
    }

    /** 统计当前磁盘缓存文件数 */
    fun totalDiskFiles(): Int {
        val dir = diskCacheDir ?: return 0
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.size ?: 0
    }
}
