package com.zaka.shijuan.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 本地存储：书架、进度、设置、搜索历史、书源开关 */
class Store(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("shijuan", Context.MODE_PRIVATE)

    init {
        NovelContentCache.init(context)
        Wenku8LocalIndex.init(context)
    }

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
        progress: Float, pageIndex: Int = 0, total: Int,
    ): List<ShelfItem> {
        // 不管在不在书架，先把阅读记忆存下来
        putRecord(book, chapterIndex, chapterName, progress, pageIndex, total)
        saveBookPage(book.url, chapterIndex, pageIndex)
        val list = shelf().toMutableList()
        val idx = list.indexOfFirst { it.book.url == book.url && it.book.sourceId == book.sourceId }
        if (idx >= 0) {
            list[idx] = list[idx].copy(
                chapterIndex = chapterIndex,
                chapterName = chapterName,
                progress = progress,
                pageIndex = pageIndex,
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
        progress: Float, pageIndex: Int = 0, total: Int,
    ) {
        val list = records().toMutableList()
        val idx = list.indexOfFirst { it.book.url == book.url && it.book.sourceId == book.sourceId }
        val old = if (idx >= 0) list[idx] else null
        val rec = ReadingRecord(
            book = if (old != null && old.book.cover.isNotBlank()) old.book else book,
            chapterIndex = chapterIndex,
            chapterName = chapterName,
            progress = progress,
            pageIndex = pageIndex,
            total = if (total > 0) total else old?.total ?: 0,
            lastReadAt = System.currentTimeMillis(),
        )
        if (idx >= 0) list[idx] = rec else list.add(0, rec)
        val sorted = list.sortedByDescending { it.lastReadAt }.take(60)
        sp.edit().putString("records", ReadingRecord.listToJson(sorted)).apply()
    }

    fun saveBookPage(bookUrl: String, chapterIndex: Int, page: Int) {
        sp.edit().putInt("p_${bookUrl.hashCode()}_$chapterIndex", page).apply()
    }

    fun loadBookPage(bookUrl: String, chapterIndex: Int): Int {
        return sp.getInt("p_${bookUrl.hashCode()}_$chapterIndex", 0)
    }

    fun saveBookScroll(bookUrl: String, chapterIndex: Int, scrollY: Int) {
        sp.edit().putInt("s_${bookUrl.hashCode()}_$chapterIndex", scrollY).apply()
    }

    fun loadBookScroll(bookUrl: String, chapterIndex: Int): Int {
        return sp.getInt("s_${bookUrl.hashCode()}_$chapterIndex", 0)
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

    // ── 漫画热门榜单本地缓存（秒开） ──
    fun cachedComicHits(): List<ComicHit> {
        val s = sp.getString("comic_hot_hits_v1", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val sid = o.optString("sourceId")
                val sname = o.optString("sourceName")
                val carr = o.optJSONArray("list") ?: JSONArray()
                val clist = (0 until carr.length()).mapNotNull { j ->
                    carr.optJSONObject(j)?.let { Comic.from(it) }
                }
                if (clist.isEmpty()) null else ComicHit(sid, sname, clist, true, 0)
            }
        }.getOrDefault(emptyList())
    }

    fun saveComicHits(hits: List<ComicHit>) {
        runCatching {
            val arr = JSONArray()
            hits.filter { it.ok && it.list.isNotEmpty() }.forEach { h ->
                val ho = JSONObject()
                ho.put("sourceId", h.sourceId)
                ho.put("sourceName", h.sourceName)
                val carr = JSONArray()
                h.list.take(30).forEach { carr.put(it.toJson()) }
                ho.put("list", carr)
                arr.put(ho)
            }
            sp.edit().putString("comic_hot_hits_v1", arr.toString()).apply()
        }
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

    fun putComicRecord(comic: Comic, chapterIndex: Int, chapterName: String, pageIndex: Int = 0) {
        val list = comicRecords().toMutableList()
        val idx = list.indexOfFirst { it.comic.url == comic.url }
        val rec = ComicRecord(comic, chapterIndex, chapterName, pageIndex, System.currentTimeMillis())
        if (idx >= 0) list[idx] = rec else list.add(0, rec)
        val sorted = list.sortedByDescending { it.lastReadAt }.take(40)
        sp.edit().putString("comic_records", ComicRecord.listToJson(sorted)).apply()
        comicTick++
    }

    // ── 章节目录缓存（避免每次进详情都重新抓） ──
    // 注意：key 带版本号。老版本解析规则有问题时存进来的「假目录」（比如把书页上的
    // 推荐书单当章节）会一直命中缓存，改规则也修不好，只能换 key 让它自然失效。
    private fun tocKey(bookUrl: String) = "toc_v2_${bookUrl.hashCode()}"

    fun saveToc(bookUrl: String, chapters: List<Chapter>) {
        if (!tocSane(bookUrl, chapters)) return
        val arr = JSONArray()
        chapters.forEach {
            arr.put(JSONArray().apply {
                put(it.name)
                put(it.url)
                if (it.volume.isNotBlank()) put(it.volume)
            })
        }
        sp.edit().putString(tocKey(bookUrl), arr.toString()).apply()
        // 章节 URL 单独留一份，删缓存时才知道该删哪些正文（目录丢了也能删干净）
        sp.edit().putString(
            "tocu_${bookUrl.hashCode()}",
            JSONArray(chapters.map { it.url }).toString(),
        ).apply()
    }

    // ── 书籍元数据（缓存管理要显示书名） ──
    fun rememberBook(book: Book) {
        if (book.url.isBlank()) return
        runCatching {
            sp.edit().putString("bm_${book.url.hashCode()}", book.toJson().toString()).apply()
        }
    }

    /** 本地存过东西的书：元数据 + 书架 + 阅读记录，三处合起来 */
    fun cachedNovelBooks(): List<Book> {
        val out = LinkedHashMap<String, Book>()
        sp.all.keys.filter { it.startsWith("bm_") }.forEach { k ->
            val raw = sp.getString(k, null) ?: return@forEach
            runCatching { Book.from(org.json.JSONObject(raw)) }.getOrNull()
                ?.let { if (it.url.isNotBlank()) out[it.url] = it }
        }
        (shelf().map { it.book } + records().map { it.book }).forEach {
            if (it.url.isNotBlank()) out.putIfAbsent(it.url, it)
        }
        return out.values.toList()
    }

    /** 一本书的正文缓存体检：存了几章、缺几章、有几章是空的、大约占多少 */
    fun novelCacheEntry(book: Book): NovelCacheEntry {
        val chs = loadToc(book.url)
        val missing = ArrayList<Int>()
        val broken = ArrayList<Int>()
        var cached = 0
        var bytes = 0L
        chs.forEachIndexed { i, ch ->
            val text = loadContent(ch.url, book.sourceId)
            when {
                text == null -> missing += i
                text.trim().length < 120 -> broken += i
                else -> {
                    cached++
                    bytes += text.length.toLong()
                }
            }
        }
        return NovelCacheEntry(book, chs.size, cached, missing, broken, bytes)
    }

    /** 删掉一本书的全部本地内容：目录、正文、元数据 */
    fun deleteNovelCache(book: Book) {
        val e = sp.edit()
        val urls = LinkedHashSet<String>()
        urls += loadToc(book.url).map { it.url }
        runCatching {
            val raw = sp.getString("tocu_${book.url.hashCode()}", null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                (0 until arr.length()).forEach { urls += arr.getString(it) }
            }
        }
        urls.filter { it.isNotBlank() }.forEach { u ->
            NovelContentCache.remove(book.sourceId, u)
            e.remove("c_${u.hashCode()}")
        }
        e.remove(tocKey(book.url))
        e.remove("toc_${book.url.hashCode()}")
        e.remove("tocu_${book.url.hashCode()}")
        e.remove("bm_${book.url.hashCode()}")
        e.apply()
    }

    /** 清空所有小说正文缓存（连目录和元数据一起） */
    fun clearNovelCaches() {
        NovelContentCache.clearAll()
        val e = sp.edit()
        sp.all.keys.filter {
            it.startsWith("c_") || it.startsWith("bm_") || it.startsWith("toc_") ||
                it.startsWith("tocu_") || it.startsWith("toc_v2_")
        }.forEach { e.remove(it) }
        e.apply()
    }

    fun loadToc(bookUrl: String): List<Chapter> {
        decodeToc(sp.getString(tocKey(bookUrl), null))?.let {
            if (tocSane(bookUrl, it)) return it
        }
        // 老版本的缓存：内容靠谱就顺手迁移过来，省得升级后离线书架被清空；
        // 不靠谱（推荐书单当章节那种）就丢掉，让它重新抓。
        val legacy = decodeToc(sp.getString("toc_${bookUrl.hashCode()}", null)) ?: return emptyList()
        return if (tocSane(bookUrl, legacy)) {
            saveToc(bookUrl, legacy)
            legacy
        } else emptyList()
    }

    private fun decodeToc(s: String?): List<Chapter>? {
        if (s.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val a = arr.getJSONArray(it)
                Chapter(a.getString(0), a.getString(1), it, if (a.length() > 2) a.optString(2) else "")
            }
        }.getOrNull()
    }

    /**
     * 目录是否像话：章节地址不能跟书籍页长一个样。
     * 「书页 -> /b/123/，章节 -> /b/456/」这种就是把推荐书单当章节了，
     * 打开当然看到的是别的书的简介。数字统一抹成 N 再比形态。
     */
    private fun tocSane(bookUrl: String, chapters: List<Chapter>): Boolean {
        if (chapters.isEmpty()) return false
        val bookShape = shapeOf(bookUrl)
        if (bookShape.isBlank()) return true
        val same = chapters.count { shapeOf(it.url) == bookShape }
        return same * 2 < chapters.size
    }

    private fun shapeOf(url: String): String = runCatching {
        java.net.URI(url).path.orEmpty().replace(Regex("\\d+"), "N").lowercase()
    }.getOrDefault("")

    // ── 正文两级缓存（L1 内存 LRU 50章 + L2 磁盘 TTL 24h + 容量上限） ──
    fun saveContent(url: String, text: String, sourceId: String = "") {
        if (text.isBlank() || BookRepo.isTranscodeOrPiracyError(text)) return
        NovelContentCache.put(sourceId, url, text)
        sp.edit().remove("c_${url.hashCode()}").apply()
    }

    fun loadContent(url: String, sourceId: String = ""): String? {
        val cached = NovelContentCache.get(sourceId, url)
        if (cached != null) return cached
        val legacy = sp.getString("c_${url.hashCode()}", null)
        if (!legacy.isNullOrBlank()) {
            NovelContentCache.put(sourceId, url, legacy)
            return legacy
        }
        return null
    }

    /**
     * 这本书本地存了多少章正文（离线能直接读的章数）。
     * 目录都没存过 = 从来没打开过这本书，正文自然也没有。
     */
    fun cachedChaptersOf(book: Book): Int {
        val chs = loadToc(book.url)
        if (chs.isEmpty()) return 0
        return chs.count { loadContent(it.url, book.sourceId) != null }
    }

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
    // key 升级到 v4：书源表动过（起点下线换成 17K、速读谷修好、SF 修好），
    // 老用户存的开关里可能还带着已经删掉的 id，换 key 让「推荐默认」重新生效一次，
    // 顺便把不存在的源过滤掉，免得列表里出现空白卡片。
    fun enabledSources(): Set<String> {
        val s = sp.getString("enabled_sources_v4", null) ?: return Sources.defaultEnabled
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }
                .filter { id -> Sources.ALL.any { it.id == id } }
                .toSet()
        }.getOrDefault(Sources.defaultEnabled)
    }

    fun setSourceEnabled(id: String, on: Boolean): Set<String> {
        val cur = enabledSources().toMutableSet()
        if (on) cur.add(id) else cur.remove(id)
        sp.edit().putString("enabled_sources_v4", JSONArray(cur.toList()).toString()).apply()
        return cur
    }

    // ── 网络代理 ──
    fun proxy(): Net.ProxyCfg = Net.ProxyCfg(
        enabled = sp.getBoolean("proxy_on", false),
        socks = sp.getBoolean("proxy_socks", false),
        host = sp.getString("proxy_host", "") ?: "",
        port = sp.getInt("proxy_port", 0),
        user = sp.getString("proxy_user", "") ?: "",
        pass = sp.getString("proxy_pass", "") ?: "",
    )

    fun saveProxy(cfg: Net.ProxyCfg) {
        sp.edit()
            .putBoolean("proxy_on", cfg.enabled)
            .putBoolean("proxy_socks", cfg.socks)
            .putString("proxy_host", cfg.host.trim())
            .putInt("proxy_port", cfg.port)
            .putString("proxy_user", cfg.user)
            .putString("proxy_pass", cfg.pass)
            .apply()
        Net.setProxy(cfg.copy(host = cfg.host.trim()))
    }

    // ── 阅读设置 ──
    fun readerPrefs(): ReaderPrefs = ReaderPrefs(
        fontSize = sp.getFloat("font_size", 19f),
        lineHeight = sp.getFloat("line_height", 1.75f),
        themeIndex = sp.getInt("theme", 0),
        pageMode = sp.getInt("page_mode", 1),
        indent = sp.getBoolean("indent", true),
    )

    // ── 软件更新设置 ──
    fun autoCheckUpdate(): Boolean = sp.getBoolean("auto_check_update", true)

    fun setAutoCheckUpdate(on: Boolean) {
        sp.edit().putBoolean("auto_check_update", on).apply()
    }

    fun ignoredUpdateVersion(): String = sp.getString("ignored_update_version", "") ?: ""

    fun setIgnoredUpdateVersion(version: String) {
        sp.edit().putString("ignored_update_version", version.trim()).apply()
    }

    fun saveReaderPrefs(p: ReaderPrefs) {
        sp.edit()
            .putFloat("font_size", p.fontSize)
            .putFloat("line_height", p.lineHeight)
            .putInt("theme", p.themeIndex)
            .putInt("page_mode", p.pageMode)
            .putBoolean("indent", p.indent)
            .apply()
    }

    // ── 站点 Cookie 凭据持久化（Keystore 加密，支持 Cloudflare 质询与登录凭据同步） ──
    fun saveHostCookies(host: String, cookieHeader: String) {
        val clean = host.removePrefix("http://").removePrefix("https://").substringBefore('/')
        val encrypted = SecureStore.encrypt(cookieHeader)
        sp.edit().putString("cookie_$clean", encrypted).apply()
    }

    fun loadHostCookies(host: String): String? {
        val clean = host.removePrefix("http://").removePrefix("https://").substringBefore('/')
        val raw = sp.getString("cookie_$clean", null) ?: return null
        val decrypted = SecureStore.decrypt(raw)
        if (!raw.startsWith("enc_v1:") && decrypted.isNotBlank()) {
            sp.edit().putString("cookie_$clean", SecureStore.encrypt(decrypted)).apply()
        }
        return decrypted.ifBlank { null }
    }

    fun allSavedCookies(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((k, v) in sp.all) {
            if (k.startsWith("cookie_") && v is String) {
                val host = k.removePrefix("cookie_")
                val decrypted = SecureStore.decrypt(v)
                if (decrypted.isNotBlank()) {
                    out[host] = decrypted
                }
            }
        }
        return out
    }

    // ── ActiveBases 镜像持久化（24h TTL） ──
    fun saveActiveBase(sourceId: String, base: String, timestamp: Long = System.currentTimeMillis()) {
        sp.edit().putString("active_base_$sourceId", "$base|$timestamp").apply()
    }

    fun loadActiveBases(): Map<String, Pair<String, Long>> {
        val out = HashMap<String, Pair<String, Long>>()
        val now = System.currentTimeMillis()
        val ttl = 24L * 3600 * 1000L
        for ((k, v) in sp.all) {
            if (k.startsWith("active_base_") && v is String) {
                val sid = k.removePrefix("active_base_")
                val parts = v.split("|")
                if (parts.size == 2) {
                    val base = parts[0]
                    val ts = parts[1].toLongOrNull() ?: 0L
                    if (now - ts <= ttl) {
                        out[sid] = base to ts
                    }
                }
            }
        }
        return out
    }

    // ── 书源健康指标持久化 ──
    fun saveSourceMetrics(json: String) {
        sp.edit().putString("source_health_metrics_v2", json).apply()
    }

    fun loadSourceMetrics(): String? = sp.getString("source_health_metrics_v2", null)

    fun clearAll() {
        sp.edit().clear().apply()
    }
}
