package com.zaka.shijuan.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/** 本地存储：书架、进度、设置、搜索历史、书源开关 */
class Store(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("shijuan", Context.MODE_PRIVATE)

    // ── 书架 ──
    fun shelf(): List<ShelfItem> = ShelfItem.listFrom(sp.getString("shelf", null))

    fun saveShelf(items: List<ShelfItem>) {
        sp.edit().putString("shelf", ShelfItem.listToJson(items)).apply()
    }

    fun inShelf(book: Book): Boolean =
        shelf().any { it.book.url == book.url && it.book.sourceId == book.sourceId }

    fun addToShelf(book: Book): List<ShelfItem> {
        val list = shelf().toMutableList()
        if (list.none { it.book.url == book.url && it.book.sourceId == book.sourceId }) {
            list.add(0, ShelfItem(book = book, addedAt = System.currentTimeMillis()))
            saveShelf(list)
        }
        return list
    }

    fun removeFromShelf(bookUrl: String, sourceId: String): List<ShelfItem> {
        val list = shelf().filterNot { it.book.url == bookUrl && it.book.sourceId == sourceId }
        saveShelf(list)
        return list
    }

    fun updateProgress(
        book: Book, chapterIndex: Int, chapterName: String,
        progress: Float, total: Int,
    ): List<ShelfItem> {
        // 不管在不在书架，先把阅读记忆存下来
        putRecord(book, chapterIndex, chapterName, progress, total)
        val list = shelf().toMutableList()
        val idx = list.indexOfFirst { it.book.url == book.url && it.book.sourceId == book.sourceId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                chapterIndex = chapterIndex,
                chapterName = chapterName,
                progress = progress,
                lastReadAt = System.currentTimeMillis(),
                totalChapters = if (total > 0) total else list[idx].totalChapters,
                book = if (list[idx].book.cover.isBlank() && book.cover.isNotBlank()) book else list[idx].book,
            )
            saveShelf(list)
        }
        return list
    }

    // ── 阅读记忆（小说） ──
    fun records(): List<ReadingRecord> = ReadingRecord.listFrom(sp.getString("records", null))

    fun recordOf(book: Book): ReadingRecord? =
        records().firstOrNull { it.book.url == book.url && it.book.sourceId == book.sourceId }

    fun putRecord(
        book: Book, chapterIndex: Int, chapterName: String,
        progress: Float, total: Int,
    ) {
        val list = records().toMutableList()
        val idx = list.indexOfFirst { it.book.url == book.url && it.book.sourceId == book.sourceId }
        val old = if (idx >= 0) list[idx] else null
        val rec = ReadingRecord(
            book = if (old != null && old.book.cover.isNotBlank()) old.book else book,
            chapterIndex = chapterIndex,
            chapterName = chapterName,
            progress = progress,
            total = if (total > 0) total else old?.total ?: 0,
            lastReadAt = System.currentTimeMillis(),
        )
        if (idx >= 0) list[idx] = rec else list.add(0, rec)
        val sorted = list.sortedByDescending { it.lastReadAt }.take(60)
        sp.edit().putString("records", ReadingRecord.listToJson(sorted)).apply()
    }

    fun removeRecord(book: Book) {
        val list = records().filterNot { it.book.url == book.url && it.book.sourceId == book.sourceId }
        sp.edit().putString("records", ReadingRecord.listToJson(list)).apply()
    }

    /** 漫画记忆的版本号：改动后让界面自动刷新 */
    var comicTick by mutableIntStateOf(0)
        private set

    // ── 漫画书架 ──
    fun comicShelf(): List<Comic> {
        val s = sp.getString("comic_shelf_v1", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Comic.from(o) } }
        }.getOrDefault(emptyList())
    }

    fun saveComicShelf(items: List<Comic>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        sp.edit().putString("comic_shelf_v1", arr.toString()).apply()
    }

    fun inComicShelf(comic: Comic): Boolean =
        comicShelf().any { it.url == comic.url && it.sourceId == comic.sourceId }

    fun addComicToShelf(comic: Comic): List<Comic> {
        val list = comicShelf().toMutableList()
        if (list.none { it.url == comic.url && it.sourceId == comic.sourceId }) list.add(0, comic)
        saveComicShelf(list)
        return list
    }

    fun removeComicFromShelf(comic: Comic): List<Comic> {
        val list = comicShelf().filterNot { it.url == comic.url && it.sourceId == comic.sourceId }
        saveComicShelf(list)
        return list
    }

    // ── 漫画阅读设置 ──
    /** scroll = 上下滚动；page = 左右翻页（章内） */
    fun comicMode(): String = sp.getString("comic_mode", "page") ?: "page"

    fun setComicMode(mode: String) {
        sp.edit().putString("comic_mode", mode).apply()
    }

    /** 是否允许双指缩放 / 双击放大 */
    fun comicZoom(): Boolean = sp.getBoolean("comic_zoom", true)

    fun setComicZoom(on: Boolean) {
        sp.edit().putBoolean("comic_zoom", on).apply()
    }

    // ── 漫画缓存任务（下载进度持久化，App 重开还在） ──
    fun loadDownloadTasks(): String? = sp.getString("comic_downloads_v1", null)

    fun saveDownloadTasks(json: String) {
        sp.edit().putString("comic_downloads_v1", json).apply()
    }

    // ── 漫画记忆 ──
    fun comicRecords(): List<ComicRecord> = ComicRecord.listFrom(sp.getString("comic_records", null))

    fun comicRecordOf(comic: Comic): ComicRecord? =
        comicRecords().firstOrNull { it.comic.url == comic.url }

    fun putComicRecord(comic: Comic, chapterIndex: Int, chapterName: String) {
        val list = comicRecords().toMutableList()
        val idx = list.indexOfFirst { it.comic.url == comic.url }
        val rec = ComicRecord(comic, chapterIndex, chapterName, System.currentTimeMillis())
        if (idx >= 0) list[idx] = rec else list.add(0, rec)
        val sorted = list.sortedByDescending { it.lastReadAt }.take(40)
        sp.edit().putString("comic_records", ComicRecord.listToJson(sorted)).apply()
        comicTick++
    }

    // ── 章节目录缓存（避免每次进详情都重新抓） ──
    fun saveToc(bookUrl: String, chapters: List<Chapter>) {
        val arr = JSONArray()
        chapters.forEach {
            arr.put(JSONArray().apply { put(it.name); put(it.url) })
        }
        sp.edit().putString("toc_${bookUrl.hashCode()}", arr.toString()).apply()
    }

    fun loadToc(bookUrl: String): List<Chapter> {
        val s = sp.getString("toc_${bookUrl.hashCode()}", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val a = arr.getJSONArray(it)
                Chapter(a.getString(0), a.getString(1), it)
            }
        }.getOrDefault(emptyList())
    }

    // ── 正文缓存（离线看过的章节） ──
    fun saveContent(url: String, text: String) {
        sp.edit().putString("c_${url.hashCode()}", text).apply()
    }

    fun loadContent(url: String): String? = sp.getString("c_${url.hashCode()}", null)

    // ── 搜索历史 ──
    fun history(): MutableList<String> {
        val s = sp.getString("history", null) ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun pushHistory(key: String) {
        if (key.isBlank()) return
        val h = history()
        h.remove(key)
        h.add(0, key)
        while (h.size > 20) h.removeAt(h.size - 1)
        sp.edit().putString("history", JSONArray(h).toString()).apply()
    }

    fun clearHistory() = sp.edit().remove("history").apply()

    // ── 书源开关 ──
    fun enabledSources(): Set<String> {
        val s = sp.getString("enabled_sources", null) ?: return Sources.defaultEnabled
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(Sources.defaultEnabled)
    }

    fun setSourceEnabled(id: String, on: Boolean): Set<String> {
        val cur = enabledSources().toMutableSet()
        if (on) cur.add(id) else cur.remove(id)
        sp.edit().putString("enabled_sources", JSONArray(cur.toList()).toString()).apply()
        return cur
    }

    // ── 阅读设置 ──
    fun readerPrefs(): ReaderPrefs = ReaderPrefs(
        fontSize = sp.getFloat("font_size", 19f),
        lineHeight = sp.getFloat("line_height", 1.75f),
        themeIndex = sp.getInt("theme", 0),
        pageMode = sp.getInt("page_mode", 1),
        indent = sp.getBoolean("indent", true),
    )

    fun saveReaderPrefs(p: ReaderPrefs) {
        sp.edit()
            .putFloat("font_size", p.fontSize)
            .putFloat("line_height", p.lineHeight)
            .putInt("theme", p.themeIndex)
            .putInt("page_mode", p.pageMode)
            .putBoolean("indent", p.indent)
            .apply()
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }
}
