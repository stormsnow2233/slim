package com.zaka.shijuan.data

import android.content.Context
import android.content.SharedPreferences
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
