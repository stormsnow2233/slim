package com.storm.shijuan.data

import java.io.File

/** 一本漫画本地缓存的体检结果 */
data class ComicCacheEntry(
    val key: String,
    val comic: Comic,
    val chapters: List<ComicChapter>,
    /** 完整存下来的话下标 */
    val complete: List<Int>,
    /** 存了但缺页 / 有坏图的话下标 */
    val partial: List<Int>,
    /** 一张都没存的话下标（没有目录信息时为空） */
    val missing: List<Int>,
    val pages: Int,
    val brokenPages: Int,
    val bytes: Long,
    val hasToc: Boolean,
) {
    val total: Int get() = chapters.size
    val cachedCount: Int get() = complete.size + partial.size

    val ok: Boolean
        get() = hasToc && total > 0 && missing.isEmpty() && partial.isEmpty() && brokenPages == 0

    /** 一行状态，直接显示给用户 */
    fun summary(): String = when {
        !hasToc && total == 0 -> "没有目录信息，只能按已下到的图显示"
        total == 0 -> "没有图"
        ok -> "完整 · $total 话都不缺"
        else -> buildList {
            if (missing.isNotEmpty()) add("缺 ${missing.size} 话")
            if (partial.isNotEmpty()) add("${partial.size} 话没下全")
            if (brokenPages > 0) add("$brokenPages 张坏图")
        }.joinToString(" · ").ifBlank { "完整" }
    }
}

/** 一本小说本地缓存的体检结果 */
data class NovelCacheEntry(
    val book: Book,
    val total: Int,
    val cached: Int,
    val missing: List<Int>,
    val broken: List<Int>,
    val bytes: Long,
) {
    val ok: Boolean get() = missing.isEmpty() && broken.isEmpty() && cached > 0
    val title: String get() = book.name.ifBlank { book.url }

    fun summary(): String = when {
        total == 0 && cached == 0 -> "没有缓存"
        cached == 0 -> "正文没存下来"
        ok -> "完整 · $cached 章"
        else -> buildList {
            if (missing.isNotEmpty()) add("缺 ${missing.size} 章")
            if (broken.isNotEmpty()) add("${broken.size} 章是空的")
        }.joinToString(" · ")
    }
}

/**
 * 缓存体检 + 清理。
 *
 * 漫画缓存在私有目录里，一话一个文件夹；小说正文在偏好设置里。
 * 这里只做两件事：把磁盘上真实存在的东西数清楚、把不要的删干净。
 */
object CacheManager {

    // ── 漫画 ──

    fun scanComics(store: Store): List<ComicCacheEntry> {
        val root = runCatching { ComicDownloader.rootDir() }.getOrNull() ?: return emptyList()
        if (!root.exists()) return emptyList()
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        if (dirs.isEmpty()) return emptyList()

        // 老版本缓存目录里没有 meta.json，退回去书架 / 阅读记录里认领
        val known = HashMap<String, Comic>()
        store.comicShelf().forEach { known[ComicDownloader.keyOf(it)] = it }
        store.comicRecords().forEach { known.putIfAbsent(ComicDownloader.keyOf(it.comic), it.comic) }

        return dirs.mapNotNull { dir ->
            val comic = ComicDownloader.comicOfDir(dir) ?: known[dir.name] ?: return@mapNotNull null
            runCatching { evaluate(dir, comic) }.getOrNull()
        }.sortedByDescending { it.pages }
    }

    private fun evaluate(dir: File, comic: Comic): ComicCacheEntry {
        val toc = ComicDownloader.localChapters(comic)
        val chDirs = dir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("ch") }
            ?: emptyList()

        // 没有 chapters.json 的老缓存：按目录名把话补出来，缺哪些话没法判断
        val chapters = if (toc.isNotEmpty()) toc else chDirs
            .mapNotNull { it.name.removePrefix("ch").toIntOrNull() }
            .sorted()
            .map { ComicChapter(name = "第 ${it + 1} 话", url = "", index = it, sourceId = comic.sourceId) }

        val complete = ArrayList<Int>()
        val partial = ArrayList<Int>()
        var pagesTotal = 0
        var brokenTotal = 0
        var bytes = 0L

        // 以目录里真实存在的为准，目录信息说有但磁盘上没有的算缺失
        val seen = HashSet<Int>()
        chDirs.forEach { chDir ->
            val idx = chDir.name.removePrefix("ch").toIntOrNull() ?: return@forEach
            seen += idx
            val images = chDir.listFiles()
                ?.filter { it.isFile && it.nameWithoutExtension.toIntOrNull() != null }
                ?: emptyList()
            bytes += images.sumOf { it.length() }

            val good = ArrayList<File>()
            var broken = 0
            images.forEach { f ->
                if (f.length() > 256 && looksLikeImage(f)) good.add(f) else broken++
            }
            brokenTotal += broken
            pagesTotal += good.size

            val numbers = good.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
            val contiguous = numbers.isNotEmpty() &&
                numbers.first() == 1 && numbers.last() == numbers.size && numbers.distinct().size == numbers.size

            if (good.isEmpty()) {
                // 目录存在但一张能看的图都没有，当没下
                runCatching { chDir.deleteRecursively() }
                return@forEach
            }
            val done = File(chDir, DONE_MARK).exists()
            if (done && broken == 0 && contiguous) {
                complete += idx
            } else if (!done && broken == 0 && contiguous) {
                // 老缓存没盖完成章但页是齐的：顺手补上，下次体检就不再当半成品
                runCatching { File(chDir, DONE_MARK).writeText(good.size.toString()) }
                complete += idx
            } else {
                partial += idx
            }
        }

        val missing = chapters.indices.filter { it !in seen }
        return ComicCacheEntry(
            key = dir.name,
            comic = comic,
            chapters = chapters,
            complete = complete,
            partial = partial,
            missing = missing,
            pages = pagesTotal,
            brokenPages = brokenTotal,
            bytes = bytes,
            hasToc = toc.isNotEmpty(),
        )
    }

    /** 文件头像不像图片：只读前 12 个字节，不解析整张图 */
    private fun looksLikeImage(f: File): Boolean = runCatching {
        f.inputStream().use { input ->
            val buf = ByteArray(12)
            val n = input.read(buf)
            if (n < 4) return false
            val jpg = buf[0] == 0xFF.toByte() && buf[1] == 0xD8.toByte()
            val png = buf[0] == 0x89.toByte() && buf[1] == 'P'.code.toByte()
            val gif = buf[0] == 'G'.code.toByte() && buf[1] == 'I'.code.toByte()
            val webp = n >= 12 && buf[8] == 'W'.code.toByte() && buf[9] == 'E'.code.toByte()
            val bmp = buf[0] == 'B'.code.toByte() && buf[1] == 'M'.code.toByte()
            jpg || png || gif || webp || bmp
        }
    }.getOrDefault(false)

    /** 补齐缺失的话：已下完的话有完成标记，会秒过，只补真的缺的 */
    fun repair(entry: ComicCacheEntry) {
        if (entry.chapters.isEmpty()) return
        ComicDownloader.start(entry.comic, entry.chapters, force = true)
    }

    fun deleteComic(entry: ComicCacheEntry) {
        ComicDownloader.remove(entry.key)
    }

    fun clearComics() {
        ComicDownloader.clearAll()
    }

    // ── 小说 ──

    fun scanNovels(store: Store): List<NovelCacheEntry> =
        store.cachedNovelBooks().mapNotNull { book ->
            runCatching { store.novelCacheEntry(book) }.getOrNull()
        }.filter { it.cached > 0 || it.total > 0 }
            .sortedByDescending { it.cached }

    fun deleteNovel(store: Store, entry: NovelCacheEntry) {
        store.deleteNovelCache(entry.book)
    }

    fun clearNovels(store: Store) {
        store.clearNovelCaches()
    }
}
