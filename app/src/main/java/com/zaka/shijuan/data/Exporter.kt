package com.zaka.shijuan.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出进度（整本下载时章节多，必须能看进度） */
data class ExportState(
    val running: Boolean = false,
    val bookName: String = "",
    val format: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val finished: Boolean = false,
    val failed: Boolean = false,
    val message: String = "",
    val fileName: String = "",
    val savedPath: String = "",
) {
    val percent: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * 整本导出：TXT / EPUB。
 *
 * 章节并发抓（4 路），已缓存的直接命中；离开页面任务继续，
 * 完成后发通知，文件落在「下载/拾卷」。
 */
object Exporter {

    val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** 严格嗅探与解码外部下载的 TXT 字节（优先 BOM / 严格 UTF-8，遇到非法序列回退 GB18030） */
    fun decodeNovelBytes(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val utf8Result = runCatching {
            utf8Decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        if (utf8Result != null && !utf8Result.contains("\uFFFD")) {
            return utf8Result
        }
        return runCatching {
            String(bytes, java.nio.charset.Charset.forName("GB18030"))
        }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    val state = MutableStateFlow(ExportState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        job = null
        state.value = state.value.copy(running = false, message = "已取消")
    }

    /** format: "txt" / "epub" */
    fun start(ctx: Context, book: Book, chapters: List<Chapter>, format: String, store: Store) {
        if (chapters.isEmpty()) {
            state.value = ExportState(failed = true, message = "没有章节可导出")
            return
        }
        if (job?.isActive == true) return
        val appCtx = ctx.applicationContext
        val total = chapters.size
        state.value = ExportState(
            running = true, bookName = book.name, format = format.uppercase(), total = total,
        )

        job = scope.launch {
            val src = Sources.byId(book.sourceId)
            if (src == null) {
                state.value = state.value.copy(running = false, failed = true, message = "书源不存在")
                return@launch
            }
            // 轻小说文库（wenku8）有官方整本 TXT 接口，先试直下，失败再逐章
            if (format.equals("txt", true)) {
                val direct = directTxtUrl(book)
                if (direct != null) {
                    state.value = state.value.copy(current = "尝试整本直下…")
                    val bytes = Net.getBytes(direct, referer = "https://www.wenku8.net/")
                    if (bytes != null && bytes.size > 4000) {
                        val text = decodeNovelBytes(bytes)
                        if (text.length > 2000 && !text.contains("<html", true) && !text.contains("<script", true)) {
                            val safeName = sanitize(book.name)
                            val fileName = "$safeName.txt"
                            val outBytes = UTF8_BOM + text.toByteArray(Charsets.UTF_8)
                            saveToDownloads(appCtx, fileName, "text/plain", outBytes)
                                .onSuccess { path ->
                                    state.value = state.value.copy(
                                        running = false, finished = true, done = total, total = total,
                                        fileName = fileName, savedPath = path, message = "完成（整本直下）",
                                    )
                                    notifyDone(appCtx, book.name, path)
                                }
                                .onFailure {
                                    state.value = state.value.copy(
                                        running = false, failed = true, message = "保存失败：${it.message}"
                                    )
                                }
                            return@launch
                        }
                    }
                }
            }

            val results = arrayOfNulls<String>(total)
            val done = AtomicInteger(0)
            val sem = Semaphore(4)

            val workers = (0 until total).map { i ->
                launch {
                    sem.withPermit {
                        val ch = chapters[i]
                        val cached = store.loadContent(ch.url)
                        val text = cached ?: runCatching {
                            BookRepo.getChapter(src, ch).getOrNull()?.text
                        }.getOrNull().orEmpty()
                        results[i] = text
                        val d = done.incrementAndGet()
                        state.value = state.value.copy(
                            done = d, current = ch.name,
                        )
                    }
                }
            }
            workers.forEach { runCatching { it.join() } }

            val empty = results.count { it.isNullOrBlank() }
            val items = chapters.mapIndexed { i, ch -> ch to (results[i] ?: "") }

            val safeName = sanitize(book.name)
            val fileName = "$safeName.${format.lowercase()}"
            val bytes = try {
                if (format.equals("epub", true)) {
                    buildEpub(book, items)
                } else {
                    UTF8_BOM + buildTxt(book, items).toByteArray(Charsets.UTF_8)
                }
            } catch (e: Exception) {
                state.value = state.value.copy(running = false, failed = true, message = "生成文件失败：${e.message}")
                return@launch
            }

            val mime = if (format.equals("epub", true)) "application/epub+zip" else "text/plain"
            val saved = saveToDownloads(appCtx, fileName, mime, bytes)
            saved.onSuccess { path ->
                state.value = state.value.copy(
                    running = false, finished = true, fileName = fileName, savedPath = path,
                    message = if (empty > 0) "完成，$empty 章没取到（站点限制），其余已导出" else "完成",
                )
                notifyDone(appCtx, book.name, path)
            }.onFailure {
                state.value = state.value.copy(
                    running = false, failed = true,
                    message = "保存失败：${it.message}",
                )
            }
        }
    }

    /** 轻小说文库的整本 TXT 直链（dl.wenku8.com 官方打包，支持全部作品号） */
    private fun directTxtUrl(book: Book): String? {
        if (book.sourceId != "wenku8") return null
        val aid = Regex("(?:/book/|/novel/\\d+/|id=)(\\d+)").find(book.url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return "https://dl.wenku8.com/down.php?type=utf8&node=1&id=$aid"
    }

    // ───────────── 文件生成 ─────────────

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().take(60).ifBlank { "未命名" }

    internal fun buildTxt(book: Book, items: List<Pair<Chapter, String>>): String {
        val sb = StringBuilder()
        sb.append(book.name).append("\n")
        if (book.author.isNotBlank()) sb.append("作者：").append(book.author).append("\n")
        if (book.sourceName.isNotBlank()) sb.append("来源：").append(book.sourceName).append("\n")
        sb.append("导出：拾卷\n")
        sb.append("\n").append("=".repeat(30)).append("\n\n")
        for ((ch, text) in items) {
            sb.append(ch.name.trim()).append("\n\n")
            sb.append(if (text.isBlank()) "（本章未能获取）" else text.trim())
            sb.append("\n\n").append("-".repeat(20)).append("\n\n")
        }
        return sb.toString()
    }

    internal fun buildEpub(book: Book, items: List<Pair<Chapter, String>>): ByteArray {
        val chapters = items.filter { it.second.isNotBlank() }.ifEmpty { items }
        val baos = ByteArrayOutputStream()
        val zip = ZipOutputStream(baos)

        // mimetype 必须是第一个条目、且用 STORED（不压缩）写入，否则部分阅读器直接拒开
        val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        val mimeEntry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mime.size.toLong()
            compressedSize = mime.size.toLong()
            crc = java.util.zip.CRC32().apply { update(mime) }.value
        }
        zip.putNextEntry(mimeEntry)
        zip.write(mime)
        zip.closeEntry()

        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        put(
            "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
        )

        put(
            "OEBPS/style.css",
            """body { font-family: serif; line-height: 1.75; margin: 0 1em; }
h1, h2 { text-align: center; font-size: 1.2em; margin: 1.4em 0 1em; }
p { text-indent: 2em; margin: 0.55em 0; }
.meta { color: #666; font-size: 0.9em; text-indent: 0; }"""
        )

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navLi = StringBuilder()
        val navPoints = StringBuilder()

        chapters.forEachIndexed { i, (ch, text) ->
            val id = "c${i + 1}"
            val href = "text/$id.xhtml"
            val title = escapeXml(ch.name.trim().ifBlank { "第 ${i + 1} 章" })
            val body = text.trim().split(Regex("\n+")).filter { it.isNotBlank() }
                .joinToString("\n") { "<p>${escapeXml(it.trim())}</p>" }
            put(
                "OEBPS/$href",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><title>$title</title><link rel="stylesheet" type="text/css" href="../style.css"/></head>
<body>
<h2>$title</h2>
$body
</body>
</html>"""
            )
            manifest.append("""    <item id="$id" href="$href" media-type="application/xhtml+xml"/>""").append("\n")
            spine.append("""    <itemref idref="$id"/>""").append("\n")
            navLi.append("""      <li><a href="$href">$title</a></li>""").append("\n")
            navPoints.append("""    <navPoint id="np${i + 1}" playOrder="${i + 1}"><navLabel><text>$title</text></navLabel><content src="$href"/></navPoint>""").append("\n")
        }

        put(
            "OEBPS/nav.xhtml",
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="utf-8"/><title>目录</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>目录</h1>
    <ol>
$navLi    </ol>
  </nav>
</body>
</html>"""
        )

        put(
            "OEBPS/toc.ncx",
            """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="shijuan-${book.name.hashCode()}"/></head>
  <docTitle><text>${escapeXml(book.name)}</text></docTitle>
  <navMap>
$navPoints  </navMap>
</ncx>"""
        )

        put(
            "OEBPS/content.opf",
            """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">shijuan-${book.name.hashCode()}-${book.url.hashCode()}</dc:identifier>
    <dc:title>${escapeXml(book.name)}</dc:title>
    <dc:creator>${escapeXml(book.author.ifBlank { "佚名" })}</dc:creator>
    <dc:language>zh-CN</dc:language>
    <dc:source>${escapeXml(book.sourceName)}</dc:source>
    <meta property="dcterms:modified">${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())}</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
$manifest  </manifest>
  <spine toc="ncx">
$spine  </spine>
</package>"""
        )

        zip.finish()
        zip.close()
        return baos.toByteArray()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    // ───────────── 存盘 ─────────────

    /** 返回给用户看的位置描述 */
    fun saveToDownloads(ctx: Context, fileName: String, mime: String, bytes: ByteArray): Result<String> =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/拾卷")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("系统没给写入位置")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("写入失败")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "下载/拾卷/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "拾卷",
                )
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                f.writeBytes(bytes)
                "下载/拾卷/$fileName"
            }
        }

    // ───────────── 通知 ─────────────

    private fun notifyDone(ctx: Context, bookName: String, path: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "shijuan_export"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(channelId) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(channelId, "整本导出", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val n = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("《$bookName》导出完成")
                .setContentText(path)
                .setAutoCancel(true)
                .build()
            nm.notify(1001, n)
        }
    }
}
