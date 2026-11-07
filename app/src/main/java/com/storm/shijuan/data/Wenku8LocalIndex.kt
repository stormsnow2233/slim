package com.storm.shijuan.data

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

    /** 从 assets 中加载 4,300+ 本文库全量索引（仅 113KB，解压耗时 <15ms） */
    fun load(context: Context? = appContext): List<Wenku8Entry> {
        entries?.let { return it }
        synchronized(lock) {
            entries?.let { return it }
            val list = ArrayList<Wenku8Entry>(4500)
            val ctx = context ?: appContext

            // 优先尝试 Android Assets；AAPT 打包可能去掉 .gz 后缀变成 wenku8_index.tsv，也可能保留
            val assetNames = listOf("wenku8_index.tsv", "wenku8_index.tsv.gz")
            var inputStream: java.io.InputStream? = null
            if (ctx != null) {
                for (name in assetNames) {
                    val stream = runCatching { ctx.assets.open(name) }.getOrNull()
                    if (stream != null) {
                        inputStream = stream
                        break
                    }
                }
            }
            if (inputStream == null) {
                // 本地测试/桌面环境回退尝试本地 File
                val candidateFiles = listOf(
                    "app/src/main/assets/wenku8_index.tsv",
                    "app/src/main/assets/wenku8_index.tsv.gz",
                    "src/main/assets/wenku8_index.tsv",
                    "src/main/assets/wenku8_index.tsv.gz",
                    "assets/wenku8_index.tsv",
                    "assets/wenku8_index.tsv.gz",
                    "wenku8_index.tsv",
                    "wenku8_index.tsv.gz",
                )
                inputStream = candidateFiles.map { java.io.File(it) }.firstOrNull { it.exists() }?.inputStream()
            }

            if (inputStream != null) {
                runCatching {
                    val buffered = java.io.BufferedInputStream(inputStream)
                    buffered.mark(2)
                    val b0 = buffered.read()
                    val b1 = buffered.read()
                    buffered.reset()
                    val isGzip = (b0 == 0x1f && b1 == 0x8b)
                    val rawReader: java.io.Reader = if (isGzip) {
                        InputStreamReader(GZIPInputStream(buffered), Charsets.UTF_8)
                    } else {
                        InputStreamReader(buffered, Charsets.UTF_8)
                    }
                    BufferedReader(rawReader).use { reader ->
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
                }.onFailure {
                    android.util.Log.e("Wenku8LocalIndex", "Failed to load index", it)
                }
            }
            entries = list
            return list
        }
    }

    /** 毫秒级匹配书名与作者，融合别名扩展与模糊相关度智能排序，生成可直接在 Wenku8 阅读的标准 Book 列表 */
    fun search(key: String, context: Context? = null): List<Book> {
        val q = key.trim()
        if (q.isEmpty()) return emptyList()
        val all = load(context ?: appContext)

        // 借助别名库与智能模糊打分计算相关度
        val scoredList = all.mapNotNull { entry ->
            val score = NovelAliasHelper.matchScore(entry.title, entry.author, q)
            if (score >= 0.52) {
                entry to score
            } else {
                null
            }
        }.sortedByDescending { it.second }

        return scoredList.take(60).map { (entry, _) ->
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
