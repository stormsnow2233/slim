package com.zaka.shijuan.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 统一域名级令牌桶限流器 (Token Bucket Rate Limiter)：
 *  - 替代零散的 Thread.sleep 与硬编码等待
 *  - 文库8：5000ms 间隔保护（0.2 tokens/s，容量 1.0）
 *  - SF轻小说：800ms 间隔保护（1.25 tokens/s，容量 2.0）
 *  - 默认常规站点：150ms 间隔平滑控制（6.66 tokens/s，容量 5.0，支持微量突发）
 *  - 支持 Deadline 感知等待与主动中断
 */
class DomainRateLimiter {

    private class Bucket(
        val capacity: Double,
        val refillTokensPerSecond: Double,
    ) {
        private var tokens: Double = capacity
        private var lastRefillMs: Long = System.currentTimeMillis()

        @Synchronized
        fun acquire(maxWaitMs: Long): Boolean {
            val now = System.currentTimeMillis()
            val elapsedSec = (now - lastRefillMs).coerceAtLeast(0L) / 1000.0
            lastRefillMs = now
            tokens = (tokens + elapsedSec * refillTokensPerSecond).coerceAtMost(capacity)

            if (tokens >= 1.0) {
                tokens -= 1.0
                return true
            }

            if (maxWaitMs <= 0L) return false

            val needed = 1.0 - tokens
            val waitMs = (needed / refillTokensPerSecond * 1000.0).toLong()
            if (waitMs > maxWaitMs) {
                // 超出调用方时间预算，快速放弃，不进行无谓的等待
                return false
            }

            try {
                if (waitMs > 0) Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }

            tokens = 0.0
            lastRefillMs = System.currentTimeMillis()
            return true
        }

        @Synchronized
        fun availableTokens(): Double {
            val now = System.currentTimeMillis()
            val elapsedSec = (now - lastRefillMs).coerceAtLeast(0L) / 1000.0
            return (tokens + elapsedSec * refillTokensPerSecond).coerceAtMost(capacity)
        }
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** 特定域名的限速配置 (capacity to refillRate) */
    private val hostConfigs = ConcurrentHashMap<String, Pair<Double, Double>>().apply {
        put("www.wenku8.cc", 1.0 to 0.2)
        put("www.wenku8.net", 1.0 to 0.2)
        put("www.wenku8.com", 1.0 to 0.2)
        put("wenku8.cc", 1.0 to 0.2)
        put("wenku8.net", 1.0 to 0.2)
        put("wenku8.com", 1.0 to 0.2)
        put("wap.wenku8.cc", 1.0 to 0.2)
        put("m.sfacg.com", 2.0 to 1.25)
        put("sfacg.com", 2.0 to 1.25)
    }

    private fun cleanHost(rawHost: String): String {
        return rawHost.removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
            .lowercase()
    }

    /**
     * 申请在目标域名发包的令牌许可。
     * @param host 域名或主机名
     * @param maxWaitMs 最大允许等待毫秒数（根据请求剩余预算动态传入）
     * @return 是否获取到执行许可
     */
    fun acquire(host: String, maxWaitMs: Long = 10_000L): Boolean {
        val ch = cleanHost(host)
        if (ch.isBlank()) return true
        val (cap, rate) = hostConfigs[ch] ?: (5.0 to 6.66)
        val bucket = buckets.getOrPut(ch) { Bucket(cap, rate) }
        return bucket.acquire(maxWaitMs)
    }

    /** 动态注册或覆盖特定域名的限速参数 */
    fun configureHost(host: String, minIntervalMs: Long, burstCapacity: Double = 1.0) {
        val ch = cleanHost(host)
        val rate = if (minIntervalMs > 0) 1000.0 / minIntervalMs else 10.0
        hostConfigs[ch] = burstCapacity to rate
        buckets[ch] = Bucket(burstCapacity, rate)
    }

    fun clear() {
        buckets.clear()
    }
}
