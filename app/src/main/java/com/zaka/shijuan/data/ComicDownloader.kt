package com.zaka.shijuan.data

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
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val chs = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                ComicChapter(
                    name = c.optString("name"),
                    url = c.optString("url"),
                    index = c.optInt("index", i),
                    sourceId = "lizi",
                    cover = c.optString("cover"),
                )
            }
            return DownloadTask(
                key = o.optString("key"),
                comic = Comic.from(o.optJSONObject("comic") ?: JSONObject()),
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

    /** 某一话的本地图片（按页码排好），没缓存就空 */
    fun localPages(comic: Comic, chapterIndex: Int): List<File> {
        val dir = File(dirOf(comic), "ch$chapterIndex")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.length() > 1024 }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
            ?: emptyList()
    }

    /** 开始缓存整本；已经在跑或已完成就不重复开 */
    fun start(comic: Comic, chapters: List<ComicChapter>) {
        if (chapters.isEmpty()) return
        val key = keyOf(comic)
        val exist = tasks.firstOrNull { it.key == key }
        if (exist != null && (exist.state == STATE_RUNNING || exist.state == STATE_DONE)) return

        paused[key] = false
        cancelled[key] = false
        val task = DownloadTask(
            key = key,
            comic = comic,
            chapters = chapters,
            state = STATE_RUNNING,
            doneChapters = 0,
            pagesDone = 0,
            createdAt = System.currentTimeMillis(),
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

        update(task.key) {
            it.copy(
                state = STATE_DONE,
                doneChapters = task.chapters.size,
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
        val pages = runCatching { ComicRepo.pages(comic, chapter) }.getOrNull() ?: return 0
        if (pages.images.isEmpty()) return 0
        val dir = File(dirOf(comic), "ch$chapterIndex")
        dir.mkdirs()
        val headers = ComicRepo.imageHeaders(comic)
        var ok = 0
        pages.images.forEachIndexed { idx, url ->
            if (cancelled[taskKey] == true) return@forEachIndexed
            while (paused[taskKey] == true) {
                Thread.sleep(200)
            }
            val ext = url.substringAfterLast('.').substringBefore('?').take(5)
                .lowercase().ifBlank { "jpg" }.let { if (it.length > 4) "jpg" else it }
            val f = File(dir, "${idx + 1}.$ext")
            if (f.exists() && f.length() > 1024) {
                ok++
                return@forEachIndexed
            }
            val got = Net.downloadTo(url, f, headers)
            if (got > 0) {
                ok++
                onProgress(got, 1)
            }
        }
        // 一页都没下下来的话，把空目录收掉，免得显示成已缓存
        if (ok == 0) runCatching { dir.deleteRecursively() }
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
