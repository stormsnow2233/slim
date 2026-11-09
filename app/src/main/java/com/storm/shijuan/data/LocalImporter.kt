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

    fun inspectEpubStream(stream: InputStream): EpubStat {
        var imgCount = 0
        var textChars = 0
        ZipInputStream(stream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var sampledHtmls = 0
            while (entry != null) {
                val name = entry.name.lowercase()
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                    name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".bmp")
                ) {
                    imgCount++
                } else if ((name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm")) && sampledHtmls < 10) {
                    sampledHtmls++
                    val bytes = zis.readBytes()
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

    fun importNovel(context: Context, uri: Uri, fileName: String, store: Store): Result<Book> {
        return runCatching {
            val bookId = "local_novel_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            val baseDir = File(context.filesDir, "imported/novels/$bookId").apply { mkdirs() }
            val ext = fileName.substringAfterLast('.', "").lowercase()

            when (ext) {
                "txt" -> importTxtAsNovel(context, uri, fileName, baseDir, store)
                "epub" -> importEpubAsNovel(context, uri, fileName, baseDir, store)
                "pdf" -> importPdfAsNovel(context, uri, fileName, baseDir, store)
                else -> {
                    importTxtAsNovel(context, uri, fileName, baseDir, store)
                }
            }
        }
    }

    fun importComic(context: Context, uri: Uri, fileName: String, store: Store): Result<Comic> {
        return runCatching {
            val comicId = "local_comic_" + System.currentTimeMillis() + "_" + (1000..9999).random()
            val baseDir = File(context.filesDir, "imported/comics/$comicId").apply { mkdirs() }
            val ext = fileName.substringAfterLast('.', "").lowercase()

            when (ext) {
                "epub" -> importEpubAsComic(context, uri, fileName, baseDir, store)
                "pdf" -> importPdfAsComic(context, uri, fileName, baseDir, store)
                else -> {
                    importTxtAsComic(context, uri, fileName, baseDir, store)
                }
            }
        }
    }

    /* -------------------- TXT -------------------- */

    private fun importTxtAsNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store
    ): Book {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取文件内容")
        val fullText = Exporter.decodeNovelBytes(bytes)
        val title = fileName.substringBeforeLast('.')

        val chapterRegex = Regex("""(?m)^[ \t]*(第[0-9一二三四五六七八九十百千万]+[章回节卷集幕篇部][^\r\n]{0,35}|Chapter\s*\d+[^\r\n]{0,35}|(?:引子|序言|序章|楔子|尾声|后记|番外)[^\r\n]{0,35})[ \t]*$""")
        val matches = chapterRegex.findAll(fullText).toList()

        val chaptersDir = File(baseDir, "chapters").apply { mkdirs() }
        val chapterList = mutableListOf<Chapter>()

        if (matches.size >= 2) {
            val firstStart = matches[0].range.first
            if (firstStart > 0) {
                val preContent = fullText.substring(0, firstStart).trim()
                if (preContent.isNotBlank()) {
                    val chFile = File(chaptersDir, "ch_0.txt")
                    chFile.writeText(preContent)
                    val chUrl = "file://" + chFile.absolutePath
                    NovelContentCache.put("local", chUrl, preContent)
                    chapterList.add(Chapter(name = "序章 / 前言", url = chUrl, index = 0))
                }
            }

            for (i in matches.indices) {
                val m = matches[i]
                val name = m.value.trim()
                val start = m.range.first
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else fullText.length
                val content = fullText.substring(start, end).trim()

                val chIdx = chapterList.size
                val chFile = File(chaptersDir, "ch_$chIdx.txt")
                chFile.writeText(content)
                val chUrl = "file://" + chFile.absolutePath
                NovelContentCache.put("local", chUrl, content)
                chapterList.add(Chapter(name = name, url = chUrl, index = chIdx))
            }
        } else {
            val lines = fullText.lines()
            val chunk = StringBuilder()
            var partIdx = 1

            fun flush() {
                if (chunk.isBlank()) return
                val content = chunk.toString().trim()
                val chIdx = chapterList.size
                val chFile = File(chaptersDir, "ch_$chIdx.txt")
                chFile.writeText(content)
                val chUrl = "file://" + chFile.absolutePath
                NovelContentCache.put("local", chUrl, content)
                chapterList.add(Chapter(name = "第 $partIdx 部分", url = chUrl, index = chIdx))
                partIdx++
                chunk.clear()
            }

            for (line in lines) {
                chunk.append(line).append('\n')
                if (chunk.length >= 6000) flush()
            }
            flush()
        }

        if (chapterList.isEmpty()) {
            val chFile = File(chaptersDir, "ch_0.txt")
            chFile.writeText(fullText)
            val chUrl = "file://" + chFile.absolutePath
            NovelContentCache.put("local", chUrl, fullText)
            chapterList.add(Chapter(name = "正文", url = chUrl, index = 0))
        }

        val book = Book(
            name = title,
            author = "本地导入",
            sourceId = "local",
            sourceName = "本地导入",
            url = "file://${baseDir.absolutePath}",
            cover = "",
            intro = fullText.take(200).trim(),
            latest = chapterList.lastOrNull()?.name ?: "",
            isImported = true,
        )

        store.saveToc(book.url, chapterList)
        store.addToShelf(book)
        return book
    }

    /* -------------------- EPUB (Novel) -------------------- */

    private fun importEpubAsNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store
    ): Book {
        val extractedDir = File(baseDir, "extracted").apply { mkdirs() }
        val chaptersDir = File(baseDir, "chapters").apply { mkdirs() }

        context.contentResolver.openInputStream(uri)?.use { stream ->
            unzip(stream, extractedDir)
        } ?: throw IllegalStateException("无法打开 EPUB 文件")

        val containerFile = File(extractedDir, "META-INF/container.xml")
        var opfPath = ""
        if (containerFile.exists()) {
            runCatching {
                val doc = Jsoup.parse(containerFile, "UTF-8")
                opfPath = doc.select("rootfile").attr("full-path")
            }
        }
        val opfFile = if (opfPath.isNotBlank()) File(extractedDir, opfPath)
        else extractedDir.walkTopDown().firstOrNull { it.name.endsWith(".opf") }
            ?: throw IllegalStateException("EPUB 格式不合法：未找到 OPF 文件")

        val opfDir = opfFile.parentFile ?: extractedDir
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
            val cFile = File(opfDir, coverRel)
            if (cFile.exists()) coverUrl = "file://" + cFile.absolutePath
        }
        if (coverUrl.isBlank()) {
            val coverEntry = manifest.values.firstOrNull { (href, media) ->
                media.startsWith("image/") && (href.lowercase().contains("cover") || href.lowercase().contains("193698"))
            } ?: manifest.values.firstOrNull { it.second.startsWith("image/") }
            if (coverEntry != null) {
                val cFile = File(opfDir, coverEntry.first)
                if (cFile.exists()) coverUrl = "file://" + cFile.absolutePath
            }
        }

        val spineItems = opfDoc.select("spine > itemref").map { it.attr("idref") }
        val chapterList = mutableListOf<Chapter>()

        for (idref in spineItems) {
            val pair = manifest[idref] ?: continue
            val href = pair.first
            val xhtmlFile = File(opfDir, href)
            if (!xhtmlFile.exists()) continue

            val doc = Jsoup.parse(xhtmlFile, "UTF-8")
            val chTitle = doc.selectFirst("h1, h2, h3, title")?.text()?.trim()
                ?.ifBlank { null } ?: "第 ${chapterList.size + 1} 章"

            val body = doc.body() ?: continue
            val sb = StringBuilder()

            fun resolveImg(rawSrc: String): File? {
                val s = rawSrc.trim().replace("\\", "/")
                if (s.isBlank()) return null
                val target = File(xhtmlFile.parentFile ?: opfDir, s).normalize()
                return if (target.exists() && target.isFile) target else null
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
                NovelContentCache.put("local", chUrl, chContent)
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

        store.saveToc(book.url, chapterList)
        store.addToShelf(book)
        return book
    }

    /* -------------------- EPUB (Comic) -------------------- */

    private fun importEpubAsComic(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store
    ): Comic {
        val extractedDir = File(baseDir, "extracted").apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            unzip(stream, extractedDir)
        } ?: throw IllegalStateException("无法打开 EPUB 文件")

        val imageFiles = extractedDir.walkTopDown().filter { f ->
            val n = f.name.lowercase()
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                n.endsWith(".webp") || n.endsWith(".gif")
        }.sortedWith(Comparator { a, b ->
            a.name.compareTo(b.name, ignoreCase = true)
        }).toList()

        if (imageFiles.isEmpty()) {
            throw IllegalStateException("EPUB 中未找到图片内容")
        }

        val title = fileName.substringBeforeLast('.')
        val coverUrl = "file://" + imageFiles.first().absolutePath

        val manifest = JSONObject()
        manifest.put("name", title)
        manifest.put("author", "本地导入")
        manifest.put("cover", coverUrl)

        val chArray = JSONArray()
        val chunkSize = if (imageFiles.size > 80) 40 else imageFiles.size
        val chunks = imageFiles.chunked(chunkSize)

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

        store.addComicToShelf(comic)
        return comic
    }

    /* -------------------- PDF (Comic) -------------------- */

    private fun importPdfAsComic(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store
    ): Comic {
        val pdfFile = File(baseDir, "document.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取 PDF 文件")

        val pagesDir = File(baseDir, "pages").apply { mkdirs() }
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount

        if (pageCount == 0) {
            renderer.close()
            pfd.close()
            throw IllegalStateException("PDF 页面为空")
        }

        val renderedImages = mutableListOf<String>()
        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val scale = (1440f / page.width).coerceIn(1.0f, 2.0f)
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val outFile = File(pagesDir, "page_%04d.jpg".format(i))
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
            bmp.recycle()
            renderedImages.add("file://" + outFile.absolutePath)
        }
        renderer.close()
        pfd.close()

        val title = fileName.substringBeforeLast('.')
        val coverUrl = renderedImages.firstOrNull() ?: ""

        val manifest = JSONObject()
        manifest.put("name", title)
        manifest.put("author", "本地导入")
        manifest.put("cover", coverUrl)

        val chArray = JSONArray()
        val chunkSize = if (renderedImages.size > 80) 40 else renderedImages.size
        val chunks = renderedImages.chunked(chunkSize)

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

        store.addComicToShelf(comic)
        return comic
    }

    private fun importPdfAsNovel(
        context: Context,
        uri: Uri,
        fileName: String,
        baseDir: File,
        store: Store
    ): Book {
        val pdfFile = File(baseDir, "document.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(pdfFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取 PDF 文件")

        val pagesDir = File(baseDir, "pages").apply { mkdirs() }
        val chaptersDir = File(baseDir, "chapters").apply { mkdirs() }
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount

        val chapterList = mutableListOf<Chapter>()
        var coverUrl = ""

        for (i in 0 until pageCount) {
            val page = renderer.openPage(i)
            val scale = (1200f / page.width).coerceIn(1.0f, 1.8f)
            val bmp = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val imgFile = File(pagesDir, "page_%04d.jpg".format(i))
            FileOutputStream(imgFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos)
            }
            bmp.recycle()

            if (i == 0) coverUrl = "file://" + imgFile.absolutePath

            val chContent = "<!--image-->file://${imgFile.absolutePath}<!--image-->"
            val chFile = File(chaptersDir, "ch_$i.txt")
            chFile.writeText(chContent)
            val chUrl = "file://" + chFile.absolutePath
            NovelContentCache.put("local", chUrl, chContent)
            chapterList.add(Chapter(name = "第 ${i + 1} 页", url = chUrl, index = i))
        }
        renderer.close()
        pfd.close()

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

        store.saveToc(book.url, chapterList)
        store.addToShelf(book)
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

    private fun unzip(inputStream: InputStream, targetDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val f = File(targetDir, entry.name)
                if (!f.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
