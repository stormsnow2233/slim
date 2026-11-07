package com.zaka.shijuan.data

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class Wenku8Entry(
    val aid: String,
    val title: String,
    val author: String,
    val latestVol: String,
)

object Wenku8LocalIndex {
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var entries: List<Wenku8Entry>? = null
    private val lock = Any()

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            // 异步预热索引，避免搜索时卡顿
            Thread {
                runCatching { load(appContext!!) }
            }.start()
        }
    }

    /** 从 assets 中解压加载 4,300+ 本文库全量索引（仅 113KB，解压耗时 <15ms） */
    fun load(context: Context? = appContext): List<Wenku8Entry> {
        entries?.let { return it }
        synchronized(lock) {
            entries?.let { return it }
            val list = ArrayList<Wenku8Entry>(4500)
            // 优先尝试 Android Assets；若 context 为空则回退尝试本地 File（兼顾单元测试/桌面环境）
            val inputStream = runCatching { (context ?: appContext)?.assets?.open("wenku8_index.tsv.gz") }.getOrNull()
                ?: listOf(
                    java.io.File("app/src/main/assets/wenku8_index.tsv.gz"),
                    java.io.File("src/main/assets/wenku8_index.tsv.gz"),
                    java.io.File("assets/wenku8_index.tsv.gz"),
                    java.io.File("wenku8_index.tsv.gz")
                ).firstOrNull { it.exists() }?.inputStream()

            if (inputStream != null) {
                runCatching {
                    inputStream.use { rawIn ->
                        GZIPInputStream(rawIn).use { gzIn ->
                            BufferedReader(InputStreamReader(gzIn, Charsets.UTF_8)).use { reader ->
                                var line = reader.readLine()
                                while (line != null) {
                                    val parts = line.split('\t')
                                    if (parts.size >= 2) {
                                        val aid = parts[0].trim()
                                        val title = parts[1].trim()
                                        val author = parts.getOrNull(2)?.trim().orEmpty()
                                        val vol = parts.getOrNull(3)?.trim().orEmpty()
                                        if (aid.isNotEmpty() && title.isNotEmpty()) {
                                            list.add(Wenku8Entry(aid, title, author, vol))
                                        }
                                    }
                                    line = reader.readLine()
                                }
                            }
                        }
                    }
                }
            }
            entries = list
            return list
        }
    }

    /** 毫秒级匹配书名与作者，生成可直接在 Wenku8 阅读的标准 Book 列表 */
    fun search(key: String, context: Context? = null): List<Book> {
        val q = key.trim()
        if (q.isEmpty()) return emptyList()
        val all = load(context ?: appContext)
        val qLower = q.lowercase()
        val hits = all.filter {
            it.title.lowercase().contains(qLower) || (it.author.isNotEmpty() && it.author.lowercase().contains(qLower))
        }
        return hits.take(60).map { entry ->
            val aidLong = entry.aid.toLongOrNull() ?: 0L
            val gid = aidLong / 1000L
            val coverUrl = "https://img.wenku8.com/image/$gid/${entry.aid}/${entry.aid}s.jpg"
            val bookUrl = "https://www.wenku8.cc/book/${entry.aid}.htm"
            Book(
                name = entry.title,
                author = entry.author,
                cover = coverUrl,
                intro = if (entry.latestVol.isNotEmpty()) "最新：${entry.latestVol}" else "全本收录·日本轻小说",
                latest = entry.latestVol,
                url = bookUrl,
                sourceId = "wenku8",
                sourceName = "轻小说文库",
            )
        }
    }
}
