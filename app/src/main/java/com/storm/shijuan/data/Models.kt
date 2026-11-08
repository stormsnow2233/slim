package com.storm.shijuan.data

import org.json.JSONArray
import org.json.JSONObject

/** 搜索结果 / 书籍条目 */
data class Book(
    val name: String = "",
    val author: String = "",
    val cover: String = "",
    val intro: String = "",
    val latest: String = "",
    val url: String = "",
    val sourceId: String = "",
    val sourceName: String = "",
    val kind: String = "",
    val wordCount: String = "",
    val external: Boolean = false,
    val downloadUrl: String = "",
    val year: String = "",
    val language: String = "",
    val altSources: List<Book> = emptyList(),
    val isImported: Boolean = false,
) {
    val isLocalOrImported: Boolean
        get() = isImported || sourceId == "local" || url.startsWith("file://") || url.startsWith("content://")
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("author", author); put("cover", cover)
        put("intro", intro); put("latest", latest); put("url", url)
        put("sourceId", sourceId); put("sourceName", sourceName)
        put("kind", kind); put("wordCount", wordCount)
        put("external", external); put("downloadUrl", downloadUrl)
        put("year", year); put("language", language); put("isImported", isImported)
        if (altSources.isNotEmpty()) {
            val arr = JSONArray()
            altSources.forEach { arr.put(it.copy(altSources = emptyList()).toJson()) }
            put("altSources", arr)
        }
    }

    companion object {
        fun from(o: JSONObject): Book {
            val alts = mutableListOf<Book>()
            val altArr = o.optJSONArray("altSources")
            if (altArr != null) {
                for (i in 0 until altArr.length()) {
                    altArr.optJSONObject(i)?.let { alts.add(from(it)) }
                }
            }
            return Book(
                name = o.optString("name"), author = o.optString("author"),
                cover = o.optString("cover"), intro = o.optString("intro"),
                latest = o.optString("latest"), url = o.optString("url"),
                sourceId = o.optString("sourceId"), sourceName = o.optString("sourceName"),
                kind = o.optString("kind"), wordCount = o.optString("wordCount"),
                external = o.optBoolean("external"), downloadUrl = o.optString("downloadUrl"),
                year = o.optString("year"), language = o.optString("language"),
                altSources = alts,
                isImported = o.optBoolean("isImported", o.optString("sourceId") == "local" || o.optString("url").startsWith("file://") || o.optString("url").startsWith("content://")),
            )
        }
    }
}

/** 章节 */
data class Chapter(val name: String, val url: String, val index: Int = 0, val volume: String = "")

/** 目录结果 */
data class Toc(val chapters: List<Chapter>, val cover: String = "", val intro: String = "", val author: String = "")

/** 正文结果 */
data class Content(val title: String, val text: String, val html: String = "")

/** 单一书源的搜索状态（给搜索页显示进度/错误） */
data class SourceState(
    val sourceId: String,
    val sourceName: String,
    val running: Boolean = false,
    val ok: Boolean = false,
    val count: Int = 0,
    val ms: Long = 0,
    val error: String = "",
)

/** 书架条目 */
data class ShelfItem(
    val book: Book,
    var chapterIndex: Int = 0,
    var chapterName: String = "",
    var progress: Float = 0f,
    var pageIndex: Int = 0,
    var addedAt: Long = 0,
    var lastReadAt: Long = 0,
    var totalChapters: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("book", book.toJson())
        put("chapterIndex", chapterIndex)
        put("chapterName", chapterName)
        put("progress", progress.toDouble())
        put("pageIndex", pageIndex)
        put("addedAt", addedAt)
        put("lastReadAt", lastReadAt)
        put("totalChapters", totalChapters)
    }

    companion object {
        fun from(o: JSONObject) = ShelfItem(
            book = Book.from(o.optJSONObject("book") ?: JSONObject()),
            chapterIndex = o.optInt("chapterIndex"),
            chapterName = o.optString("chapterName"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            pageIndex = o.optInt("pageIndex", 0),
            addedAt = o.optLong("addedAt"),
            lastReadAt = o.optLong("lastReadAt"),
            totalChapters = o.optInt("totalChapters"),
        )

        fun listToJson(items: List<ShelfItem>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFrom(s: String?): List<ShelfItem> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).map { from(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** 阅读设置 */
data class ReaderPrefs(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.75f,
    val themeIndex: Int = 0,
    val pageMode: Int = 1, // 0 上下滚动 1 章内左右翻页
    val indent: Boolean = true,
    val convertSimplified: Boolean = false,
)
