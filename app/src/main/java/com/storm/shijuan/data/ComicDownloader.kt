package com.storm.shijuan.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 一个缓存任务：整本或某几话，落盘在 App 私有目录 */
data class DownloadTask(
    val key: String = "",
    val comic: Comic = Comic(),
    val chapters: List<ComicChapter> = emptyList(),
    val state: String = STATE_WAITING,
    val doneChapters: Int = 0,
    val pagesDone: Int = 0,
    val currentChapter: String = "",
    val error: String = "",
    val bytes: Long = 0,
    val createdAt: Long = 0,
) {
    val total: Int get() = chapters.size
    val percent: Float
        get() = if (total == 0) 0f else (doneChapters.toFloat() / total).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("comic", comic.toJson())
        val arr = JSONArray()
        chapters.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name); put("url", c.url)
                put("index", c.index); put("cover", c.cover)
                put("sourceId", c.sourceId)
            })
        }
        put("chapters", arr)
        put("state", state)
        put("doneChapters", doneChapters)
        put("pagesDone", pagesDone)
        put("currentChapter", currentChapter)
        put("error", error)
        put("bytes", bytes)
        put("createdAt", createdAt)
    }

    companion object {
        fun from(o: JSONObject): DownloadTask {
            val comic = Comic.from(o.optJSONObject("comic") ?: JSONObject())
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val chs = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                ComicChapter(
                    name = c.optString("name"),
                    url = c.optString("url"),
                    index = c.optInt("index", i),
                    sourceId = c.optString("sourceId").ifBlank { comic.sourceId.ifBlank { "dm5" } },
                    cover = c.optString("cover"),
                )
            }
            return DownloadTask(
                key = o.optString("key"),
                comic = comic,
                chapters = chs,
                state = o.optString("state", STATE_WAITING),
                doneChapters = o.optInt("doneChapters"),
                pagesDone = o.optInt("pagesDone"),
                currentChapter = o.optString("currentChapter"),
                error = o.optString("error"),
                bytes = o.optLong("bytes"),
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}

const val STATE_WAITING = "waiting"
const val STATE_RUNNING = "running"
const val STATE_PAUSED = "paused"
const val STATE_DONE = "done"
const val STATE_ERROR = "error"

/** 一话完整下完的标记文件（存在即代表这一话页数齐了） */
const val DONE_MARK = ".done"

/** 缓存目录里存漫画信息的文件名 */
const val META_FILE = "meta.json"

/**
 * 漫画整本缓存。
 *
 * - 后台跑，退出页面不中断；App 被杀掉后重开，没下完的任务还在，点继续接着下
 * - 一话下完才记账，半话的重开时从头补这一话（图片本身有断点，已存在且够大的直接跳过）
 * - 存 App 私有目录，不进相册
 */
object ComicDownloader {

    private var ctx: Context? = null
    private var store: Store? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val paused = ConcurrentHashMap<String, Boolean>()
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    var tasks by mutableStateOf<List<DownloadTask>>(emptyList())
        private set

    fun init(context: Context, st: Store) {
        if (ctx != null) return
        ctx = context.applicationContext
        store = st
        tasks = load()
    }

    fun keyOf(comic: Comic): String = "${comic.sourceId}_${comic.url.hashCode()}"

    fun dirOf(comic: Comic): File = File(ctx?.filesDir ?: File("."), "comics/" + keyOf(comic))

    /** 所有漫画缓存的根目录 */
    fun rootDir(): File = File(ctx?.filesDir ?: File("."), "comics")

    fun taskOf(comic: Comic): DownloadTask? = tasks.firstOrNull { it.key == keyOf(comic) }

    fun isRunning(comic: Comic): Boolean = taskOf(comic)?.state == STATE_RUNNING

    /** 本地已缓存的话数（用来在详情页标「已缓存」） */
    fun cachedChapters(comic: Comic): Set<Int> {
        val root = dirOf(comic)
        if (!root.exists()) return emptySet()
        return root.listFiles()?.filter { it.isDirectory && it.name.startsWith("ch") }
            ?.mapNotNull { it.name.removePrefix("ch").toIntOrNull() }
            ?.toSet() ?: emptySet()
    }

    /** 校验文件头魔数，确保是真正的图片而非 HTML 错误页或损坏文件 */
    fun isImageFile(f: File): Boolean {
        if (!f.exists() || f.length() < 12) return false
        return runCatching {
            f.inputStream().use { input ->
                val h = ByteArray(12)
                val read = input.read(h)
                if (read < 12) return@use false
                // JPEG: FF D8 FF
                if (h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()) return@use true
                // PNG: 89 50 4E 47
                if (h[0] == 0x89.toByte() && h[1] == 0x50.toByte() && h[2] == 0x4E.toByte() && h[3] == 0x47.toByte()) return@use true
                // WebP: 'R' 'I' 'F' 'F' ... 'W' 'E' 'B' 'P'
                if (h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
                    h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() && h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte()) return@use true
                // GIF: 'G' 'I' 'F' '8'
                if (h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == '8'.code.toByte()) return@use true
                // BMP: 'B' 'M'
                if (h[0] == 'B'.code.toByte() && h[1] == 'M'.code.toByte()) return@use true
                false
            }
        }.getOrDefault(false)
    }

    /** 某一话的本地图片（按页码排好），没缓存就空 */
    fun localPages(comic: Comic, chapterIndex: Int): List<File> {
        val dir = File(dirOf(comic), "ch$chapterIndex")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 256 && it.nameWithoutExtension.toIntOrNull() != null && isImageFile(it) }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
            ?: emptyList()
    }

    /** 这一话是不是完整下完过（有完成标记）。没标记但有图，说明是断在半路的老缓存 */
    fun isChapterComplete(comic: Comic, chapterIndex: Int): Boolean =
        File(File(dirOf(comic), "ch$chapterIndex"), DONE_MARK).exists()

    /** 本地这一话存了几张有效图 */
    fun pageCount(comic: Comic, chapterIndex: Int): Int = localPages(comic, chapterIndex).size

    /** 缓存目录里所有存过图片的话（下标集合） */
    fun cachedIndexes(comic: Comic): Set<Int> =
        cachedChapters(comic)

    /**
     * 章节表跟着缓存一起存本地。
     * 断网的时候开详情页，联网拉不到目录，就用这份，照样能进阅读器看已缓存的那些话。
     */
    private fun saveChapters(comic: Comic, chapters: List<ComicChapter>) {
        runCatching {
            val dir = dirOf(comic).apply { mkdirs() }
            val arr = JSONArray()
            chapters.forEach { c ->
                arr.put(
                    JSONObject().apply {
                        put("n", c.name); put("u", c.url); put("i", c.index)
                    },
                )
            }
            File(dir, "chapters.json").writeText(arr.toString())
            // 书名、封面、来源一起存下来：离线时详情页、缓存管理不联网也知道这是哪本
            runCatching { File(dir, META_FILE).writeText(comic.toJson().toString()) }
        }
    }

    /** 从缓存目录里的 meta.json 还原 Comic；老缓存没存过就返回 null */
    fun comicOfDir(dir: File): Comic? = runCatching {
        val f = File(dir, META_FILE)
        if (!f.exists()) return null
        Comic.from(JSONObject(f.readText()))
    }.getOrNull()

    fun localChapters(comic: Comic): List<ComicChapter> = runCatching {
        val f = File(dirOf(comic), "chapters.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ComicChapter(
                name = o.optString("n").ifBlank { "第 ${i + 1} 话" },
                url = o.optString("u"),
                index = o.optInt("i", i),
                sourceId = comic.sourceId,
                cover = comic.cover,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 开始缓存整本；已经在跑或已完成就不重复开。
     * force = 重新检查补齐：已完成的话有完成标记会秒过，只补缺的那些。
     */
    fun start(comic: Comic, chapters: List<ComicChapter>, force: Boolean = false) {
        if (chapters.isEmpty()) return
        saveChapters(comic, chapters)
        val key = keyOf(comic)
        val exist = tasks.firstOrNull { it.key == key }
        if (exist != null && exist.state == STATE_RUNNING) return
        if (!force && exist != null && exist.state == STATE_DONE) return

        paused[key] = false
        cancelled[key] = false
        val task = DownloadTask(
            key = key,
            comic = comic,
            chapters = chapters,
            state = STATE_RUNNING,
            doneChapters = if (force) 0 else 0,
            pagesDone = if (force) 0 else exist?.pagesDone ?: 0,
            bytes = exist?.bytes ?: 0,
            createdAt = exist?.createdAt ?: System.currentTimeMillis(),
        )
        upsert(task)
        launchWorker(task)
    }

    fun pause(key: String) {
        paused[key] = true
        update(key) { it.copy(state = STATE_PAUSED) }
    }

    fun resume(key: String) {
        val t = tasks.firstOrNull { it.key == key } ?: return
        if (t.state == STATE_DONE) return
        paused[key] = false
        cancelled[key] = false
        update(key) { it.copy(state = STATE_RUNNING, error = "") }
        launchWorker(t.copy(state = STATE_RUNNING))
    }

    fun remove(key: String) {
        cancelled[key] = true
        jobs[key]?.cancel()
        jobs.remove(key)
        val t = tasks.firstOrNull { it.key == key }
        if (t != null) runCatching { dirOf(t.comic).deleteRecursively() }
        tasks = tasks.filterNot { it.key == key }
        persist()
    }

    /** 删掉缓存但保留任务记录 */
    fun clearFiles(comic: Comic) {
        runCatching { dirOf(comic).deleteRecursively() }
    }

    /** 清空所有漫画缓存和任务记录（缓存管理里的「全部删除」） */
    fun clearAll() {
        jobs.keys.toList().forEach { k ->
            cancelled[k] = true
            jobs[k]?.cancel()
            jobs.remove(k)
        }
        runCatching { rootDir().deleteRecursively() }
        tasks = emptyList()
        persist()
    }

    private fun launchWorker(task: DownloadTask) {
        jobs[task.key]?.cancel()
        jobs[task.key] = scope.launch {
            runCatching { work(task) }
                .onFailure { e -> update(task.key) { it.copy(state = STATE_ERROR, error = e.message ?: "缓存失败") } }
        }
    }

    private suspend fun work(task: DownloadTask) {
        val comic = task.comic
        val startFrom = tasks.firstOrNull { it.key == task.key }?.doneChapters ?: 0
        var done = startFrom
        var pages = tasks.firstOrNull { it.key == task.key }?.pagesDone ?: 0
        var bytes = tasks.firstOrNull { it.key == task.key }?.bytes ?: 0L

        for (i in startFrom until task.chapters.size) {
            if (cancelled[task.key] == true) return
            while (paused[task.key] == true) delay(250)

            val ch = task.chapters[i]
            update(task.key) {
                it.copy(
                    state = STATE_RUNNING,
                    currentChapter = ch.name,
                    doneChapters = done,
                    pagesDone = pages,
                    bytes = bytes,
                    error = "",
                )
            }

            val saved = runCatching { downloadOne(comic, ch, i, task.key) { addBytes, addPages ->
                bytes += addBytes
                pages += addPages
            } }.getOrElse { 0 }

            if (saved == 0) {
                // 这一话拿不到图，记一笔继续下一话，不整本卡死
                update(task.key) { it.copy(error = "第 ${i + 1} 话取图失败，已跳过") }
            }
            done = i + 1
            update(task.key) {
                it.copy(doneChapters = done, pagesDone = pages, bytes = bytes)
            }
            persist()
        }

        // 补齐时秒过的话不会回调进度，完成时按磁盘实际数量校正一次
        val realPages = runCatching {
            task.chapters.indices.sumOf { idx -> localPages(comic, idx).size }
        }.getOrDefault(pages)
        val realBytes = runCatching {
            dirOf(comic).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(bytes)

        update(task.key) {
            it.copy(
                state = STATE_DONE,
                doneChapters = task.chapters.size,
                pagesDone = realPages,
                bytes = realBytes,
                currentChapter = "",
            )
        }
        jobs.remove(task.key)
        persist()
    }

    /** 下一话，返回拿到的图片数 */
    private suspend fun downloadOne(
        comic: Comic,
        chapter: ComicChapter,
        chapterIndex: Int,
        taskKey: String,
        onProgress: (bytes: Long, pages: Int) -> Unit,
    ): Int {
        // 这一话完整下过、带完成标记 → 直接跳过，补齐时不用再走一次网络
        val doneMark = File(File(dirOf(comic), "ch$chapterIndex"), DONE_MARK)
        if (doneMark.exists()) return doneMark.readText().toIntOrNull()?.coerceAtLeast(1) ?: 1

        val pages = runCatching { ComicRepo.pages(comic, chapter) }.getOrNull() ?: return 0
        if (pages.images.isEmpty()) return 0
        val dir = File(dirOf(comic), "ch$chapterIndex")
        dir.mkdirs()
        val reqHeaders = ComicRepo.imageHeaders(comic) + pages.headers
        val referer = pages.referer.ifBlank { reqHeaders["Referer"].orEmpty() }
        var ok = 0
        pages.images.forEachIndexed { idx, url ->
            if (cancelled[taskKey] == true) return@forEachIndexed
            while (paused[taskKey] == true) {
                delay(200)
            }
            val ext = url.substringAfterLast('.').substringBefore('?').take(5)
                .lowercase().ifBlank { "jpg" }.let { if (it.length > 4) "jpg" else it }
            val f = File(dir, "${idx + 1}.$ext")
            if (f.exists() && f.length() > 512 && isImageFile(f)) {
                ok++
                return@forEachIndexed
            } else if (f.exists() && !isImageFile(f)) {
                f.delete()
            }
            val got = Net.downloadTo(url, f, reqHeaders, referer = referer)
            if (got > 0 && isImageFile(f)) {
                if (url.contains("/media/photos/") || reqHeaders.containsKey("X-JM")) {
                    JmImageDescrambler.descrambleFile(f, url)
                }
                ok++
                onProgress(got, 1)
            } else {
                f.delete()
            }
        }
        // 一页都没下下来的话，把空目录收掉，免得显示成已缓存
        if (ok == 0) runCatching { dir.deleteRecursively() }
        // 整话的图都齐了才盖完成章，断在半路的留着下次补
        if (ok > 0 && ok >= pages.images.size) {
            runCatching { File(dir, DONE_MARK).writeText(ok.toString()) }
        }
        return ok
    }

    private fun update(key: String, f: (DownloadTask) -> DownloadTask) {
        tasks = tasks.map { if (it.key == key) f(it) else it }
        persist()
    }

    private fun upsert(task: DownloadTask) {
        val idx = tasks.indexOfFirst { it.key == task.key }
        tasks = if (idx >= 0) tasks.toMutableList().also { it[idx] = task }
        else (tasks + task).sortedByDescending { it.createdAt }
        persist()
    }

    private fun persist() {
        val st = store ?: return
        val arr = JSONArray()
        tasks.forEach { t -> arr.put(t.toJson()) }
        st.saveDownloadTasks(arr.toString())
    }

    private fun load(): List<DownloadTask> {
        val raw = store?.loadDownloadTasks() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> DownloadTask.from(o) } }
                .map { if (it.state == STATE_RUNNING) it.copy(state = STATE_PAUSED) else it }
        }.getOrDefault(emptyList())
    }
}
