package com.storm.shijuan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

enum class ImportType(val label: String) {
    NOVEL("小说"),
    COMIC("漫画"),
    UNSURE("未确认")
}

data class DetectionResult(
    val fileName: String,
    val type: ImportType,
    val extension: String,
    val textChars: Int = 0,
    val imageCount: Int = 0,
    val reason: String = "",
)

/**
 * 本地书籍/漫画导入引擎
 */
object LocalImporter {

    fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1 && cursor.moveToFirst()) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
        if (name.isBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "unknown"
        }
        return name
    }

    fun detectType(context: Context, uri: Uri): DetectionResult {
        val fileName = getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()

        when (ext) {
            "txt" -> {
                return DetectionResult(
                    fileName = fileName,
                    type = ImportType.NOVEL,
                    extension = ext,
                    reason = "TXT 纯文本格式自动识别为小说",
                )
            }
            "pdf" -> {
                return DetectionResult(
                    fileName = fileName,
                    type = ImportType.UNSURE,
                    extension = ext,
                    reason = "PDF 文件可能为漫画画册或小说读物，请选择类型",
                )
            }
            "epub" -> {
                val stat = inspectEpub(context, uri)
                val type = when {
                    stat.textChars >= 1000 -> ImportType.NOVEL
                    stat.imageCount >= 8 && stat.textChars < 500 -> ImportType.COMIC
                    stat.textChars > 0 && stat.textChars > stat.imageCount * 80 -> ImportType.NOVEL
                    stat.imageCount > 0 && stat.textChars < 500 -> ImportType.COMIC
                    else -> ImportType.NOVEL
                }
                val reason = when (type) {
                    ImportType.COMIC -> "EPUB 包含大量图片（${stat.imageCount} 张）且无大段文本，自动识别为漫画"
                    ImportType.NOVEL -> "EPUB 包含丰富章节文本（约 ${stat.textChars} 字），自动识别为小说"
                    else -> "EPUB 识别为图文小说"
                }
                return DetectionResult(
                    fileName = fileName,
                    type = type,
                    extension = ext,
                    textChars = stat.textChars,
                    imageCount = stat.imageCount,
                    reason = reason,
                )
            }
            else -> {
                return DetectionResult(
                    fileName = fileName,
                    type = ImportType.UNSURE,
                    extension = ext,
                    reason = "未能识别的文件扩展名",
                )
            }
        }
    }

    data class EpubStat(val imageCount: Int, val textChars: Int)

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (count < 0) break
            if (count > 0) out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun inspectEpubStream(stream: InputStream): EpubStat {
        var imgCount = 0
        var textChars = 0
        ZipInputStream(stream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var sampledHtmls = 0
            var totalBytes = 0L
            var entryCount = 0
            while (entry != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) throw IllegalStateException("EPUB 条目过多")
                val bytes = zis.readBytesLimited(MAX_INSPECT_HTML_BYTES + 1)
                if (bytes.size > MAX_INSPECT_HTML_BYTES) throw IllegalStateException("EPUB 检测条目过大")
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) throw IllegalStateException("EPUB 检测数据过大")
                val name = entry.name.lowercase()
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")
                ) {
                    imgCount++
                } else if ((name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) && sampledHtmls < 10) {
                    sampledHtmls++
                    val text = Jsoup.parse(String(bytes, Charsets.UTF_8)).text()
                    textChars += text.length
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return EpubStat(imgCount, textChars)
    }

    private fun inspectEpub(context: Context, uri: Uri): EpubStat {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                inspectEpubStream(stream)
            }
        }.getOrNull() ?: EpubStat(0, 0)
    }

    fun importNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<Book> {
        var baseDir: File? = null
        return runCatching {
            onProgress(0.02f, "准备导入...")
            val bookId = "local_novel_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            baseDir = File(context.filesDir, "imported/novels/$bookId").apply { mkdirs() }
            val importDir = baseDir!!
            val ext = fileName.substringAfterLast('.', "").lowercase()

            when (ext) {
                "txt" -> importTxtAsNovel(context, uri, fileName, importDir, store, onProgress)
                "epub" -> importEpubAsNovel(context, uri, fileName, importDir, store, onProgress)
                "pdf" -> importPdfAsNovel(context, uri, fileName, importDir, store, onProgress)
                else -> {
                    importTxtAsNovel(context, uri, fileName, importDir, store, onProgress)
                }
            }
        }.onFailure { baseDir?.deleteRecursively() }
    }

    fun importComic(
        context: Context,
        uri: Uri,
        fileName: String,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<Comic> {
        var baseDir: File? = null
        return runCatching {
            onProgress(0.02f, "准备导入...")
            val comicId = "local_comic_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            baseDir = File(context.filesDir, "imported/comics/$comicId").apply { mkdirs() }
            val importDir = baseDir!!
            val ext = fileName.substringAfterLast('.', "").lowercase()

            when (ext) {
                "epub" -> importEpubAsComic(context, uri, fileName, importDir, store, onProgress)
                "pdf" -> importPdfAsComic(context, uri, fileName, importDir, store, onProgress)
                "txt" -> throw IllegalStateException("TXT 文件不能作为漫画导入")
                else -> throw IllegalStateException("不支持的漫画文件格式")
            }
        }.onFailure { baseDir?.deleteRecursively() }
    }

    /* -------------------- TXT -------------------- */

    private fun importTxtAsNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Book {
        onProgress(0.05f, "正在读取文本数据...")
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(MAX_TXT_BYTES + 1) }
            ?: throw IllegalStateException("无法读取文件内容")
        if (bytes.size > MAX_TXT_BYTES) {
            throw IllegalStateException("TXT 文件过大（超过 ${MAX_TXT_BYTES / (1024 * 1024)}MB），暂不支持导入")
        }
        val nulCount = bytes.take(4096).count { it == 0.toByte() }
        if (nulCount > 16) {
            throw IllegalStateException("文件不是纯文本小说（可能为 MOBI/AZW3 等格式，暂不支持）")
        }

        onProgress(0.10f, "正在探测字符编码...")
        val charset = Exporter.detectCharset(bytes)
        val title = fileName.substringBeforeLast('.')
        val chaptersDir = File(baseDir, "chapters").apply { mkdirs() }
        val chapterList = mutableListOf<Chapter>()

        onProgress(0.15f, "正在流式提取章节目录...")
        val chapterLineRegex = Regex("""^[ \t]*[\[【（(]?[ \t]*(第[0-9零一二三四五六七八九十百千万两]+[章回节卷集幕篇部][^\r\n]{0,35}|Chapter\s*\d+[^\r\n]{0,35}|(?:引子|序言|序章|楔子|尾声|后记|番外)[^\r\n]{0,35})[\]】）)]?[ \t]*$""")

        val reader = java.io.BufferedReader(java.io.InputStreamReader(java.io.ByteArrayInputStream(bytes), charset), 32 * 1024)
        var currentChapterTitle: String? = null
        val currentContent = StringBuilder(8192)
        var introPreview = ""
        val totalBytes = bytes.size.toLong().coerceAtLeast(1L)
        var estimatedBytesRead = 0L
        var lastReported = 0L

        fun flushChapter(titleName: String) {
            if (currentContent.isBlank()) return
            val chIdx = chapterList.size
            val chFile = File(chaptersDir, "ch_$chIdx.txt")
            chFile.bufferedWriter(Charsets.UTF_8).use { it.write(currentContent.toString().trim()) }
            val chUrl = "file://" + chFile.absolutePath
            chapterList.add(Chapter(name = titleName, url = chUrl, index = chIdx))
            currentContent.setLength(0)
        }

        reader.useLines { lines ->
            for (line in lines) {
                estimatedBytesRead += line.length + 2
                if (chapterLineRegex.matches(line)) {
                    val prevTitle = currentChapterTitle ?: if (chapterList.isEmpty()) "序章 / 前言" else "第 ${chapterList.size + 1} 节"
                    flushChapter(prevTitle)
                    currentChapterTitle = line.trim()

                    val now = System.currentTimeMillis()
                    if (now - lastReported > 80) {
                        lastReported = now
                        val ratio = (estimatedBytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                        val p = 0.15f + 0.75f * ratio
                        onProgress(p, "已解析 ${chapterList.size} 章 (${(p * 100).toInt()}%)...")
                    }
                } else {
                    currentContent.append(line).append('\n')
                    if (introPreview.length < 200 && line.isNotBlank()) {
                        introPreview += line.trim() + " "
                    }
                }
            }
        }

        val finalTitle = currentChapterTitle ?: if (chapterList.isEmpty()) "正文" else "尾声"
        flushChapter(finalTitle)

        if (chapterList.isEmpty()) {
            val chFile = File(chaptersDir, "ch_0.txt")
            chFile.writeText(String(bytes, charset))
            val chUrl = "file://" + chFile.absolutePath
            chapterList.add(Chapter(name = "正文", url = chUrl, index = 0))
        }

        val book = Book(
            name = title,
            author = "本地导入",
            sourceId = "local",
            sourceName = "本地导入",
            url = "file://${baseDir.absolutePath}",
            cover = "",
            intro = introPreview.take(200).trim(),
            latest = chapterList.lastOrNull()?.name ?: "",
            isImported = true,
        )

        onProgress(0.92f, "正在生成目录数据 (${chapterList.size} 章)...")
        store.saveToc(book.url, chapterList)
        onProgress(0.98f, "正在加入小说书架...")
        store.addToShelf(book)
        onProgress(1.0f, "导入完成")
        return book
    }

    /* -------------------- EPUB (Novel) -------------------- */

    private fun importEpubAsNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Book {
        onProgress(0.05f, "正在解压 EPUB 电子书...")
        val extractedDir = File(baseDir, "extracted").apply { mkdirs() }
        val chaptersDir = File(baseDir, "chapters").apply { mkdirs() }

        context.contentResolver.openInputStream(uri)?.use { stream ->
            unzip(stream, extractedDir) { p, stageText ->
                onProgress(0.05f + 0.20f * p, stageText)
            }
        } ?: throw IllegalStateException("无法打开 EPUB 文件")

        onProgress(0.28f, "正在读取书籍目录与元数据...")
        val containerFile = File(extractedDir, "META-INF/container.xml")
        var opfPath = ""
        if (containerFile.exists()) {
            runCatching {
                val doc = Jsoup.parse(containerFile, "UTF-8")
                opfPath = doc.select("rootfile").attr("full-path")
            }
        }
        val opfFile = if (opfPath.isNotBlank()) {
            resolveWithin(extractedDir, extractedDir, opfPath)
                ?: throw IllegalStateException("EPUB OPF 路径不安全")
        } else {
            extractedDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".opf", ignoreCase = true) }
        } ?: throw IllegalStateException("EPUB 格式不合法：未找到 OPF 文件")

        val opfDir = opfFile.parentFile ?: extractedDir
        if (opfFile.length() > MAX_OPF_BYTES) throw IllegalStateException("EPUB OPF 文件过大")
        val opfDoc = Jsoup.parse(opfFile, "UTF-8")

        val bookTitle = opfDoc.select("dc|title, title").firstOrNull()?.text()?.ifBlank { null }
            ?: fileName.substringBeforeLast('.')
        val bookAuthor = opfDoc.select("dc|creator, creator").firstOrNull()?.text()?.ifBlank { null }
            ?: "本地导入"

        val manifest = HashMap<String, Pair<String, String>>()
        opfDoc.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            val media = item.attr("media-type")
            if (id.isNotBlank() && href.isNotBlank()) {
                manifest[id] = href to media
            }
        }

        var coverUrl = ""
        val coverId = opfDoc.select("meta[name=cover]").attr("content").ifBlank {
            manifest.entries.firstOrNull { it.value.second.startsWith("image/") }?.key ?: ""
        }
        if (coverId.isNotBlank() && manifest.containsKey(coverId)) {
            val coverRel = manifest[coverId]!!.first
            val cFile = resolveWithin(extractedDir, opfDir, coverRel)
            if (cFile != null && cFile.isFile) coverUrl = "file://" + cFile.absolutePath
        }
        if (coverUrl.isBlank()) {
            val coverEntry = manifest.values.firstOrNull { (href, media) ->
                media.startsWith("image/") && (href.lowercase().contains("cover") || href.lowercase().contains("193698"))
            } ?: manifest.values.firstOrNull { it.second.startsWith("image/") }
            if (coverEntry != null) {
                val cFile = resolveWithin(extractedDir, opfDir, coverEntry.first)
                if (cFile != null && cFile.isFile) coverUrl = "file://" + cFile.absolutePath
            }
        }

        val spineItems = opfDoc.select("spine > itemref").map { it.attr("idref") }.distinct().take(MAX_SPINE_ITEMS)
        val chapterList = mutableListOf<Chapter>()
        val totalSpine = spineItems.size

        for ((idx, idref) in spineItems.withIndex()) {
            val pair = manifest[idref] ?: continue
            val href = pair.first
            val xhtmlFile = resolveWithin(extractedDir, opfDir, href)
            if (xhtmlFile == null || !xhtmlFile.isFile) continue

            if (xhtmlFile.length() > MAX_XHTML_BYTES) continue
            val doc = Jsoup.parse(xhtmlFile, "UTF-8")
            val chTitle = doc.selectFirst("h1, h2, h3, title")?.text()?.trim()
                ?.ifBlank { null } ?: "第 ${chapterList.size + 1} 章"

            val p = 0.30f + 0.60f * ((idx + 1).toFloat() / totalSpine.coerceAtLeast(1))
            onProgress(p, "正在提取章节与插图 (${idx + 1}/$totalSpine)...")

            val body = doc.body() ?: continue
            val sb = StringBuilder()

            fun resolveImg(rawSrc: String): File? {
                val s = rawSrc.trim().replace("\\", "/")
                if (s.isBlank()) return null
                val target = resolveWithin(extractedDir, xhtmlFile.parentFile ?: opfDir, s)
                return if (target != null && target.isFile) target else null
            }

            fun extractNode(el: org.jsoup.nodes.Element) {
                val tag = el.tagName().lowercase()
                if (tag == "img") {
                    val src = el.attr("src").ifBlank { el.attr("data-src") }
                    val f = resolveImg(src)
                    if (f != null) {
                        sb.append("\n<!--image-->file://${f.absolutePath}<!--image-->\n\n")
                    }
                    return
                }
                if (tag == "image") {
                    val href = el.attr("xlink:href").ifBlank { el.attr("href") }
                    val f = resolveImg(href)
                    if (f != null) {
                        sb.append("\n<!--image-->file://${f.absolutePath}<!--image-->\n\n")
                    }
                    return
                }
                if (tag in listOf("figure", "svg") || (tag == "div" && (el.hasClass("illust") || el.hasClass("cover")))) {
                    val imgs = el.select("img, image")
                    if (imgs.isNotEmpty()) {
                        imgs.forEach { imgNode ->
                            val src = if (imgNode.tagName().lowercase() == "image") {
                                imgNode.attr("xlink:href").ifBlank { imgNode.attr("href") }
                            } else {
                                imgNode.attr("src").ifBlank { imgNode.attr("data-src") }
                            }
                            val f = resolveImg(src)
                            if (f != null) {
                                sb.append("\n<!--image-->file://${f.absolutePath}<!--image-->\n\n")
                            }
                        }
                        return
                    }
                }
                if (tag in listOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "dt", "dd")) {
                    val embeddedImgs = el.select("img, image")
                    embeddedImgs.forEach { imgNode ->
                        val src = if (imgNode.tagName().lowercase() == "image") {
                            imgNode.attr("xlink:href").ifBlank { imgNode.attr("href") }
                        } else {
                            imgNode.attr("src").ifBlank { imgNode.attr("data-src") }
                        }
                        val f = resolveImg(src)
                        if (f != null) {
                            sb.append("\n<!--image-->file://${f.absolutePath}<!--image-->\n\n")
                        }
                    }
                    val clone = el.clone()
                    clone.select("img, image, svg").remove()
                    val text = clone.text().trim()
                    if (text.isNotBlank()) {
                        sb.append(text).append("\n\n")
                    }
                    return
                }
                val childElements = el.children()
                if (childElements.isNotEmpty()) {
                    childElements.forEach { child -> extractNode(child) }
                } else {
                    val text = el.text().trim()
                    if (text.isNotBlank()) {
                        sb.append(text).append("\n\n")
                    }
                }
            }

            body.children().forEach { extractNode(it) }

            val chContent = sb.toString().trim()
            if (chContent.isNotBlank()) {
                val chIdx = chapterList.size
                val chFile = File(chaptersDir, "ch_$chIdx.txt")
                chFile.writeText(chContent)
                val chUrl = "file://" + chFile.absolutePath
                chapterList.add(Chapter(name = chTitle, url = chUrl, index = chIdx))
            }
        }

        if (chapterList.isEmpty()) {
            throw IllegalStateException("EPUB 未包含有效的可读章节内容")
        }

        val book = Book(
            name = bookTitle,
            author = bookAuthor,
            sourceId = "local",
            sourceName = "本地导入",
            url = "file://${baseDir.absolutePath}",
            cover = coverUrl,
            intro = "本地 EPUB 导入，包含插图与正文（共 ${chapterList.size} 章）",
            latest = chapterList.lastOrNull()?.name ?: "",
            isImported = true,
        )

        onProgress(0.93f, "正在保存目录信息...")
        store.saveToc(book.url, chapterList)
        onProgress(0.98f, "正在加入小说书架...")
        store.addToShelf(book)
        onProgress(1.0f, "导入完成")
        return book
    }

    /* -------------------- EPUB (Comic) -------------------- */

    private fun importEpubAsComic(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Comic {
        onProgress(0.05f, "正在解压漫画图包...")
        val extractedDir = File(baseDir, "extracted").apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            unzip(stream, extractedDir) { p, stageText ->
                onProgress(0.05f + 0.30f * p, stageText)
            }
        } ?: throw IllegalStateException("无法打开 EPUB 文件")

        onProgress(0.40f, "正在检索图包图片...")
        val imageFiles = extractedDir.walkTopDown().filter { f ->
            val n = f.name.lowercase()
            f.isFile && f.canonicalPath.startsWith(extractedDir.canonicalPath + File.separator) &&
                (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                    n.endsWith(".webp") || n.endsWith(".gif"))
        }.sortedWith { a, b -> naturalCompare(a.relativeTo(extractedDir).path, b.relativeTo(extractedDir).path) }.toList()

        if (imageFiles.isEmpty()) {
            throw IllegalStateException("EPUB 中未找到图片内容")
        }

        onProgress(0.65f, "正在整理并排序漫画图片 (${imageFiles.size} 张)...")
        val title = fileName.substringBeforeLast('.')
        val coverUrl = "file://" + imageFiles.first().absolutePath

        val manifest = JSONObject()
        manifest.put("name", title)
        manifest.put("author", "本地导入")
        manifest.put("cover", coverUrl)

        val chArray = JSONArray()
        val chunkSize = if (imageFiles.size > 80) 40 else imageFiles.size
        val chunks = imageFiles.chunked(chunkSize)

        onProgress(0.85f, "正在构建漫画目录与分卷...")
        for (i in chunks.indices) {
            val chObj = JSONObject()
            chObj.put("name", if (chunks.size == 1) "全一卷" else "第 ${i + 1} 话")
            chObj.put("url", "ch_$i")
            val imgArray = JSONArray()
            chunks[i].forEach { imgArray.put("file://" + it.absolutePath) }
            chObj.put("images", imgArray)
            chArray.put(chObj)
        }
        manifest.put("chapters", chArray)

        File(baseDir, "manifest.json").writeText(manifest.toString())

        val comic = Comic(
            name = title,
            cover = coverUrl,
            author = "本地导入",
            intro = "本地 EPUB 导入漫画（共 ${imageFiles.size} 页）",
            latest = "全 ${chunks.size} 话",
            url = "file://${baseDir.absolutePath}",
            sourceId = "local",
            sourceName = "本地导入",
            status = "完结",
        )

        onProgress(0.98f, "正在加入漫画书架...")
        store.addComicToShelf(comic)
        onProgress(1.0f, "导入完成")
        return comic
    }

    /* -------------------- PDF (Comic) -------------------- */

    private fun importPdfAsComic(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Comic {
        onProgress(0.05f, "正在读取 PDF 文件...")
        val pdfFile = File(baseDir, "document.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取 PDF 文件")
        if (pdfFile.length() > MAX_PDF_BYTES) {
            pdfFile.delete()
            throw IllegalStateException("PDF 文件过大（超过 ${MAX_PDF_BYTES / (1024 * 1024)}MB），暂不支持导入")
        }

        val pagesDir = File(baseDir, "pages").apply { mkdirs() }
        val renderedImages = mutableListOf<String>()
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = renderer.pageCount
                if (pageCount <= 0) throw IllegalStateException("PDF 页面为空")
                for (i in 0 until pageCount) {
                    val p = 0.08f + 0.80f * ((i + 1).toFloat() / pageCount.coerceAtLeast(1))
                    onProgress(p, "正在渲染漫画 (${i + 1}/$pageCount 页)...")
                    renderer.openPage(i).use { page ->
                        val scale = (1440f / page.width).coerceIn(1.0f, 2.0f)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val outFile = File(pagesDir, "page_%04d.jpg".format(i))
                            FileOutputStream(outFile).use { fos ->
                                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)) throw IllegalStateException("PDF 页面压缩失败")
                            }
                            renderedImages.add("file://" + outFile.absolutePath)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }
        val pageCount = renderedImages.size

        val title = fileName.substringBeforeLast('.')
        val coverUrl = renderedImages.firstOrNull() ?: ""

        val manifest = JSONObject()
        manifest.put("name", title)
        manifest.put("author", "本地导入")
        manifest.put("cover", coverUrl)

        val chArray = JSONArray()
        val chunkSize = if (renderedImages.size > 80) 40 else renderedImages.size
        val chunks = renderedImages.chunked(chunkSize)

        onProgress(0.92f, "正在构建分卷与目录...")
        for (i in chunks.indices) {
            val chObj = JSONObject()
            chObj.put("name", if (chunks.size == 1) "全一卷" else "第 ${i + 1} 话")
            chObj.put("url", "ch_$i")
            val imgArray = JSONArray()
            chunks[i].forEach { imgArray.put(it) }
            chObj.put("images", imgArray)
            chArray.put(chObj)
        }
        manifest.put("chapters", chArray)
        File(baseDir, "manifest.json").writeText(manifest.toString())

        val comic = Comic(
            name = title,
            cover = coverUrl,
            author = "本地导入",
            intro = "本地 PDF 导入漫画（共 $pageCount 页）",
            latest = "全 ${chunks.size} 话",
            url = "file://${baseDir.absolutePath}",
            sourceId = "local",
            sourceName = "本地导入",
            status = "完结",
        )

        onProgress(0.98f, "正在加入漫画书架...")
        store.addComicToShelf(comic)
        onProgress(1.0f, "导入完成")
        return comic
    }

    private fun importPdfAsNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Book {
        onProgress(0.05f, "正在读取 PDF 文件...")
        val pdfFile = File(baseDir, "document.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取 PDF 文件")
        if (pdfFile.length() > MAX_PDF_BYTES) {
            pdfFile.delete()
            throw IllegalStateException("PDF 文件过大（超过 ${MAX_PDF_BYTES / (1024 * 1024)}MB），暂不支持导入")
        }

        val pagesDir = File(baseDir, "pages").apply { mkdirs() }
        val chaptersDir = File(baseDir, "chapters").apply { mkdirs() }
        val chapterList = mutableListOf<Chapter>()
        var coverUrl = ""
        var pageCount = 0
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                pageCount = renderer.pageCount
                if (pageCount <= 0) throw IllegalStateException("PDF 页面为空")
                for (i in 0 until pageCount) {
                    val p = 0.08f + 0.80f * ((i + 1).toFloat() / pageCount.coerceAtLeast(1))
                    onProgress(p, "正在逐页转换文档 (${i + 1}/$pageCount 页)...")
                    renderer.openPage(i).use { page ->
                        val scale = (1200f / page.width).coerceIn(1.0f, 1.8f)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val imgFile = File(pagesDir, "page_%04d.jpg".format(i))
                            FileOutputStream(imgFile).use { fos ->
                                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)) throw IllegalStateException("PDF 页面压缩失败")
                            }
                            if (i == 0) coverUrl = "file://" + imgFile.absolutePath
                            val chContent = "<!--image-->file://${imgFile.absolutePath}<!--image-->"
                            val chFile = File(chaptersDir, "ch_$i.txt")
                            chFile.writeText(chContent)
                            val chUrl = "file://" + chFile.absolutePath
                            chapterList.add(Chapter(name = "第 ${i + 1} 页", url = chUrl, index = i))
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }

        val title = fileName.substringBeforeLast('.')
        val book = Book(
            name = title,
            author = "本地导入",
            sourceId = "local",
            sourceName = "本地导入",
            url = "file://${baseDir.absolutePath}",
            cover = coverUrl,
            intro = "本地 PDF 导入（共 $pageCount 页）",
            latest = "第 $pageCount 页",
            isImported = true,
        )

        onProgress(0.92f, "正在生成目录数据...")
        store.saveToc(book.url, chapterList)
        onProgress(0.98f, "正在加入小说书架...")
        store.addToShelf(book)
        onProgress(1.0f, "导入完成")
        return book
    }

    private fun importTxtAsComic(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store
    ): Comic {
        val title = fileName.substringBeforeLast('.')
        val manifest = JSONObject()
        manifest.put("name", title)
        manifest.put("author", "本地导入")
        manifest.put("cover", "")
        manifest.put("chapters", JSONArray())
        File(baseDir, "manifest.json").writeText(manifest.toString())

        val comic = Comic(
            name = title,
            cover = "",
            author = "本地导入",
            intro = "本地 TXT 导入",
            latest = "已导入",
            url = "file://${baseDir.absolutePath}",
            sourceId = "local",
            sourceName = "本地导入",
        )
        store.addComicToShelf(comic)
        return comic
    }

    /* -------------------- Helper -------------------- */

    internal fun naturalCompare(a: String, b: String): Int {
        val regex = Regex("""\d+|\D+""")
        val ap = regex.findAll(a.lowercase()).map { it.value }.toList()
        val bp = regex.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ap.size, bp.size)) {
            val x = ap[i]; val y = bp[i]
            val cmp = if (x.firstOrNull()?.isDigit() == true && y.firstOrNull()?.isDigit() == true) {
                (x.toLongOrNull() ?: Long.MAX_VALUE).compareTo(y.toLongOrNull() ?: Long.MAX_VALUE)
            } else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return ap.size.compareTo(bp.size).takeIf { it != 0 } ?: a.compareTo(b, ignoreCase = true)
    }

    private fun resolveWithin(rootDir: File, baseDir: File, relativePath: String): File? {
        val clean = relativePath.trim().substringBefore('#').substringBefore('?').replace('\\', '/')
        if (clean.isBlank() || clean.startsWith('/') || clean.contains(':')) return null
        val root = rootDir.canonicalFile
        val candidate = File(baseDir.canonicalFile, clean).canonicalFile
        return if (candidate.path.startsWith(root.path + File.separator)) candidate else null
    }

    private fun unzip(
        inputStream: InputStream,
        targetDir: File,
        onProgress: ((Float, String) -> Unit)? = null
    ) {
        // EPUBs are untrusted archives: bound both metadata and expanded data to
        // prevent zip bombs and avoid leaving a partially imported book behind.
        val targetRoot = targetDir.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        try {
            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (++entryCount > MAX_ZIP_ENTRIES) {
                        throw IllegalStateException("EPUB 条目数量超过限制")
                    }
                    if (entryCount % 10 == 0) {
                        val progressRatio = (entryCount / 100f).coerceIn(0f, 1f)
                        onProgress?.invoke(progressRatio, "正在解压文件 ($entryCount 项)...")
                    }

                    val rawName = entry.name.replace('\\', '/')
                    if (entry.isDirectory) {
                        var directoryBytes = 0L
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            directoryBytes += read
                            totalBytes += read
                            if (directoryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                throw IllegalStateException("EPUB 解压大小超过限制")
                            }
                        }
                        if (directoryBytes > 0) throw IllegalStateException("EPUB 目录条目包含数据")
                    }
                    if (rawName.isBlank() || rawName.startsWith('/') ||
                        rawName.contains(':') || rawName.split('/').any { it == ".." }) {
                        throw IllegalStateException("EPUB 包含不安全路径")
                    }
                    val f = File(targetRoot, rawName).canonicalFile
                    if (f != targetRoot && !f.path.startsWith(targetRoot.path + File.separator)) {
                        throw IllegalStateException("EPUB 包含不安全路径")
                    }

                    if (entry.isDirectory) {
                        if (!f.exists() && !f.mkdirs()) {
                            throw IllegalStateException("无法创建 EPUB 目录")
                        }
                    } else {
                        val declaredSize = entry.size
                        if (declaredSize > MAX_ENTRY_BYTES) {
                            throw IllegalStateException("EPUB 单个条目超过大小限制")
                        }
                        f.parentFile?.let { parent ->
                            if (!parent.exists() && !parent.mkdirs()) {
                                throw IllegalStateException("无法创建 EPUB 目录")
                            }
                        }
                        var entryBytes = 0L
                        FileOutputStream(f).use { fos ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zis.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                    throw IllegalStateException("EPUB 解压大小超过限制")
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (failure: Throwable) {
            // The caller's import directory is new for this operation; remove all
            // extracted content so a failed import cannot be reused accidentally.
            targetDir.deleteRecursively()
            throw failure
        }
    }

    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_TXT_BYTES = 64 * 1024 * 1024
    private const val MAX_PDF_BYTES = 256L * 1024L * 1024L
    private const val MAX_INSPECT_HTML_BYTES = 4 * 1024 * 1024
    private const val MAX_OPF_BYTES = 4L * 1024L * 1024L
    private const val MAX_XHTML_BYTES = 16L * 1024L * 1024L
    private const val MAX_SPINE_ITEMS = 2_000
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L
}
