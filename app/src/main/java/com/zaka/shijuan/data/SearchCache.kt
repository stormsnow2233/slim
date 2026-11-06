package com.zaka.shijuan.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 搜索结果与负缓存 (Negative Cache) 引擎：
 *  - 正常有结果缓存：5 分钟 TTL（300,000 ms）
 *  - 正常空结果负缓存：60 秒 TTL（60,000 ms），防短时间内重复无效扫网
 *  - 网络报错/403/异常：不缓存，避免瞬时故障扩散
 */
object SearchCache {

    const val POSITIVE_TTL_MS = 5 * 60 * 1000L  // 5 分钟
    const val NEGATIVE_TTL_MS = 60 * 1000L       // 60 秒

    data class Entry(
        val result: SourceResult,
        val expireAt: Long,
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() > expireAt
    }

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun keyOf(sourceId: String, keyword: String): String {
        return "$sourceId:${keyword.trim().lowercase()}"
    }

    /** 检索缓存。过期或未命中返回 null */
    fun get(sourceId: String, keyword: String): SourceResult? {
        val key = keyOf(sourceId, keyword)
        val entry = cache[key] ?: return null
        if (entry.isExpired) {
            cache.remove(key, entry)
            return null
        }
        return entry.result
    }

    /** 写入成功结果缓存（5 分钟） */
    fun putSuccess(sourceId: String, keyword: String, result: SourceResult) {
        if (!result.ok || result.books.isEmpty()) return
        val key = keyOf(sourceId, keyword)
        val expireAt = System.currentTimeMillis() + POSITIVE_TTL_MS
        cache[key] = Entry(result, expireAt)
    }

    /** 写入正常空结果负缓存（60 秒） */
    fun putNegative(sourceId: String, keyword: String, result: SourceResult) {
        if (!result.ok || result.books.isNotEmpty()) return
        val key = keyOf(sourceId, keyword)
        val expireAt = System.currentTimeMillis() + NEGATIVE_TTL_MS
        cache[key] = Entry(result, expireAt)
    }

    /** 智能写入：自动区分正缓存、负缓存或放弃缓存（错误） */
    fun put(sourceId: String, keyword: String, result: SourceResult) {
        if (!result.ok) return // 报错/超时/403 不缓存
        if (result.books.isNotEmpty()) {
            putSuccess(sourceId, keyword, result)
        } else {
            putNegative(sourceId, keyword, result)
        }
    }

    fun invalidate(sourceId: String, keyword: String) {
        cache.remove(keyOf(sourceId, keyword))
    }

    fun clear() {
        cache.clear()
    }
}
