package com.storm.shijuan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 小说整本离线缓存任务 */
data class NovelDownloadTask(
    val key: String = "",
    val book: Book = Book(),
    val chapters: List<Chapter> = emptyList(),
    val state: String = STATE_WAITING,
    val doneChapters: Int = 0,
    val currentChapter: String = "",
    val error: String = "",
    val createdAt: Long = 0,
    val speedChaptersPerSec: Float = 0f,
) {
    val total: Int get() = chapters.size
    val percent: Float
        get() = if (total == 0) 0f else (doneChapters.toFloat() / total).coerceIn(0f, 1f)

    val speedFormatted: String
        get() {
            if (state != STATE_RUNNING || speedChaptersPerSec <= 0f) return ""
            return String.format("%.1f 章/s", speedChaptersPerSec)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("book", book.toJson())
        val arr = JSONArray()
        chapters.forEach { c ->
            arr.put(JSONObject().apply {
                put("name", c.name)
                put("url", c.url)
                put("index", c.index)
            })
        }
        put("chapters", arr)
        put("state", state)
        put("doneChapters", doneChapters)
        put("currentChapter", currentChapter)
        put("error", error)
        put("createdAt", createdAt)
    }

    companion object {
        fun from(o: JSONObject): NovelDownloadTask {
            val book = Book.from(o.optJSONObject("book") ?: JSONObject())
            val arr = o.optJSONArray("chapters") ?: JSONArray()
            val chs = (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                Chapter(
                    name = c.optString("name"),
                    url = c.optString("url"),
                    index = c.optInt("index", i),
                )
            }
            return NovelDownloadTask(
                key = o.optString("key"),
                book = book,
                chapters = chs,
                state = o.optString("state", STATE_WAITING),
                doneChapters = o.optInt("doneChapters"),
                currentChapter = o.optString("currentChapter"),
                error = o.optString("error"),
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}

/**
 * 小说整本/批量多协程离线缓存引擎。
 * - 后台稳定运行，退出页面不中断；
 * - 5 并发多协程加速拉取章节正文，极大缩短缓存耗时；
 * - 缓存正文自动存入 NovelContentCache 与持久化存储，断网即开即读；
 * - 带有系统通知栏进度与完成提醒。
 */
object NovelDownloader {

    private var ctx: Context? = null
    private var store: Store? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val paused = ConcurrentHashMap<String, Boolean>()
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    private const val CHANNEL_ID = "shijuan_novel_cache"
    private var lastNotifTime = 0L

    var tasks by mutableStateOf<List<NovelDownloadTask>>(emptyList())
        private set

    fun init(context: Context, st: Store) {
        if (ctx != null) return
        ctx = context.applicationContext
        store = st
        tasks = load()
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "小说离线缓存",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "显示小说整本离线缓存实时进度"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun notifIdOf(key: String): Int = (key.hashCode() and 0x7FFFFFFF) % 10000 + 4000

    private fun notifyProgress(task: NovelDownloadTask, force: Boolean = false) {
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifTime < 400 && task.doneChapters < task.total) return
        lastNotifTime = now
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(c, nm)
            val percent = if (task.total > 0) (task.doneChapters * 100 / task.total) else 0
            val subText = if (task.currentChapter.isNotBlank()) "正在缓存: ${task.currentChapter}" else "已完成 ${task.doneChapters}/${task.total} 章"
            val n = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在离线《${task.book.name}》 ($percent%)")
                .setContentText(subText)
                .setProgress(task.total, task.doneChapters, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(notifIdOf(task.key), n)
        }
    }

    private fun notifyDone(task: NovelDownloadTask) {
        val c = ctx ?: return
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(c, nm)
            val n = NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("《${task.book.name}》离线完成")
                .setContentText("共 ${task.total} 章已全部缓存至本地，断网可随时阅读")
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(notifIdOf(task.key), n)
        }
    }

    private fun cancelNotif(key: String) {
        val c = ctx ?: return
        runCatching {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifIdOf(key))
        }
    }

    fun keyOf(book: Book): String = "${book.sourceId}_${book.url.hashCode()}"

    fun taskOf(book: Book): NovelDownloadTask? = tasks.firstOrNull { it.key == keyOf(book) }

    fun isRunning(book: Book): Boolean = taskOf(book)?.state == STATE_RUNNING

    /** 检查某章节本地是否已有正文缓存 */
    fun isChapterCached(sourceId: String, chapterUrl: String): Boolean {
        if (NovelContentCache.get(sourceId, chapterUrl) != null) return true
        val st = store ?: return false
        return st.loadContent(chapterUrl, sourceId) != null
    }

    /** 快速计算整本书已缓存在本地的章节数 */
    fun cachedCount(book: Book, chapters: List<Chapter>): Int {
        if (chapters.isEmpty()) return 0
        var count = 0
        for (c in chapters) {
            if (isChapterCached(book.sourceId, c.url)) count++
        }
        return count
    }

    fun start(book: Book, chapters: List<Chapter>, force: Boolean = false) {
        if (chapters.isEmpty()) return
        val key = keyOf(book)
        val exist = tasks.firstOrNull { it.key == key }
        if (exist != null && exist.state == STATE_RUNNING) return
        if (!force && exist != null && exist.state == STATE_DONE) return

        paused[key] = false
        cancelled[key] = false
        val initialCached = cachedCount(book, chapters)
        val task = NovelDownloadTask(
            key = key,
            book = book,
            chapters = chapters,
            state = STATE_RUNNING,
            doneChapters = initialCached,
            createdAt = exist?.createdAt ?: System.currentTimeMillis(),
        )
        upsert(task)
        ctx?.let(CacheDownloadService::sync)
        notifyProgress(task, force = true)
        launchWorker(task)
    }

    fun pause(key: String) {
        paused[key] = true
        jobs.remove(key)?.cancel()
        update(key) { it.copy(state = STATE_PAUSED, speedChaptersPerSec = 0f) }
        cancelNotif(key)
    }

    fun pause(book: Book) = pause(keyOf(book))

    fun resume(key: String) {
        val t = tasks.firstOrNull { it.key == key } ?: return
        if (t.state == STATE_DONE) return
        paused[key] = false
        cancelled[key] = false
        val runningTask = t.copy(state = STATE_RUNNING, error = "")
        update(key) { runningTask }
        ctx?.let(CacheDownloadService::sync)
        notifyProgress(runningTask, force = true)
        launchWorker(runningTask)
    }

    fun resume(book: Book) = resume(keyOf(book))

    fun remove(key: String) {
        cancelled[key] = true
        jobs[key]?.cancel()
        jobs.remove(key)
        cancelNotif(key)
        tasks = tasks.filterNot { it.key == key }
        persist()
        ctx?.let(CacheDownloadService::sync)
    }

    private fun launchWorker(task: NovelDownloadTask) {
        jobs[task.key]?.cancel()
        jobs[task.key] = scope.launch {
            try {
                work(task)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                update(task.key) { it.copy(state = STATE_ERROR, speedChaptersPerSec = 0f, error = e.message ?: "缓存失败") }
            }
        }
    }

    private suspend fun work(task: NovelDownloadTask) {
        val book = task.book
        val src = Sources.byId(book.sourceId) ?: Source(id = book.sourceId, name = book.sourceName, base = book.url, group = "网络")
        val st = store

        val chapters = task.chapters
        val total = chapters.size
        val doneCounter = AtomicInteger(task.doneChapters)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val semaphore = Semaphore(5) // 5 路并发

        var lastSampleTime = System.currentTimeMillis()
        var chaptersSinceSample = 0
        var currentSpeed = 0f
        var lastUiTime = 0L

        coroutineScope {
            for (idx in chapters.indices) {
                if (cancelled[task.key] == true) break
                while (paused[task.key] == true) {
                    delay(250)
                }

                val ch = chapters[idx]
                // 已经缓存过的章节直接跳过
                if (isChapterCached(book.sourceId, ch.url)) {
                    continue
                }

                launch {
                    semaphore.withPermit {
                        if (cancelled[task.key] == true) return@withPermit
                        while (paused[task.key] == true) {
                            delay(250)
                        }

                        var downloaded = false
                        var lastFailure = ""
                        repeat(3) { attempt ->
                            if (downloaded || cancelled[task.key] == true) return@repeat
                            val result = try {
                                BookRepo.getChapter(src, ch)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                lastFailure = e.message.orEmpty()
                                null
                            }
                            val text = result?.getOrNull()?.text.orEmpty()
                            if (text.isNotBlank()) {
                                NovelContentCache.put(book.sourceId, ch.url, text)
                                st?.saveContent(ch.url, book.sourceId, text)
                                downloaded = true
                            } else {
                                lastFailure = result?.exceptionOrNull()?.message ?: lastFailure
                                if (attempt < 2) delay(500L * (attempt + 1))
                            }
                        }
                        if (!downloaded) {
                            failures.add(ch.name)
                            Log.w("NovelDownloader", "chapter failed: ${ch.name}; $lastFailure")
                            return@withPermit
                        }

                        val done = doneCounter.incrementAndGet()
                        chaptersSinceSample++
                        val now = System.currentTimeMillis()
                        val dt = now - lastSampleTime
                        if (dt >= 600) {
                            currentSpeed = (chaptersSinceSample * 1000f / dt.coerceAtLeast(1))
                            lastSampleTime = now
                            chaptersSinceSample = 0
                        }

                        if (now - lastUiTime >= 450 || done >= total) {
                            lastUiTime = now
                            val cur = task.copy(
                                state = STATE_RUNNING,
                                doneChapters = done,
                                currentChapter = ch.name,
                                speedChaptersPerSec = currentSpeed,
                                error = "",
                            )
                            update(task.key) { cur }
                            notifyProgress(cur)
                        }
                    }
                }
            }
        }

        val finalDone = cachedCount(book, chapters)
        val finishedTask = task.copy(
            state = if (finalDone == total && failures.isEmpty()) STATE_DONE else STATE_ERROR,
            doneChapters = finalDone,
            currentChapter = "",
            speedChaptersPerSec = 0f,
            error = if (finalDone == total && failures.isEmpty()) "" else "${total - finalDone} 章未缓存成功，请重试补齐",
        )
        update(task.key) { finishedTask }
        jobs.remove(task.key)
        persist()
        if (finishedTask.state == STATE_DONE) notifyDone(finishedTask)
    }

    private fun update(key: String, f: (NovelDownloadTask) -> NovelDownloadTask) {
        val wasRunning = tasks.any { it.key == key && it.state == STATE_RUNNING }
        tasks = tasks.map { if (it.key == key) f(it) else it }
        persist()
        if (wasRunning && tasks.none { it.key == key && it.state == STATE_RUNNING }) {
            ctx?.let(CacheDownloadService::sync)
        }
    }

    private fun upsert(task: NovelDownloadTask) {
        val idx = tasks.indexOfFirst { it.key == task.key }
        tasks = if (idx >= 0) tasks.toMutableList().also { it[idx] = task }
        else (tasks + task).sortedByDescending { it.createdAt }
        persist()
    }

    private fun persist() {
        val st = store ?: return
        val arr = JSONArray()
        tasks.forEach { t -> arr.put(t.toJson()) }
        st.saveNovelDownloadTasks(arr.toString())
    }

    private fun load(): List<NovelDownloadTask> {
        val raw = store?.loadNovelDownloadTasks() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> NovelDownloadTask.from(o) } }
                .map { if (it.state == STATE_RUNNING) it.copy(state = STATE_PAUSED) else it }
        }.getOrDefault(emptyList())
    }
}
