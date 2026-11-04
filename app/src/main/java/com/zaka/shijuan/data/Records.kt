package com.zaka.shijuan.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 阅读记忆：不管这本书在不在书架，都记住读到哪。
 * 书架删掉了，记忆还在（重新搜索进来能接着看）。
 */
data class ReadingRecord(
    val book: Book,
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    val progress: Float = 0f,
    val total: Int = 0,
    val lastReadAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("book", book.toJson())
        put("chapterIndex", chapterIndex)
        put("chapterName", chapterName)
        put("progress", progress.toDouble())
        put("total", total)
        put("lastReadAt", lastReadAt)
    }

    companion object {
        fun from(o: JSONObject) = ReadingRecord(
            book = Book.from(o.optJSONObject("book") ?: JSONObject()),
            chapterIndex = o.optInt("chapterIndex"),
            chapterName = o.optString("chapterName"),
            progress = o.optDouble("progress", 0.0).toFloat(),
            total = o.optInt("total"),
            lastReadAt = o.optLong("lastReadAt"),
        )

        fun listToJson(items: List<ReadingRecord>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFrom(s: String?): List<ReadingRecord> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).map { from(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** 漫画记忆 */
data class ComicRecord(
    val comic: Comic,
    val chapterIndex: Int = 0,
    val chapterName: String = "",
    val lastReadAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("comic", comic.toJson())
        put("chapterIndex", chapterIndex)
        put("chapterName", chapterName)
        put("lastReadAt", lastReadAt)
    }

    companion object {
        fun from(o: JSONObject) = ComicRecord(
            comic = Comic.from(o.optJSONObject("comic") ?: JSONObject()),
            chapterIndex = o.optInt("chapterIndex"),
            chapterName = o.optString("chapterName"),
            lastReadAt = o.optLong("lastReadAt"),
        )

        fun listToJson(items: List<ComicRecord>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFrom(s: String?): List<ComicRecord> {
            if (s.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).map { from(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}
