package com.storm.shijuan.ui

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.storm.shijuan.data.ReaderPrefs

/**
 * 每一页的内容形态（参考 MewX 文库分页器模型）：
 * - Text: 文本切片（由 PageView 的 StaticLayout 高性能绘制）
 * - Image: 独立插图页（由 Coil 异步加载居中展示，支持点按全屏大图预览）
 */
sealed class PageSlice {
    data class Text(val content: String, val showTitle: Boolean) : PageSlice()
    data class Image(val url: String) : PageSlice()
}

/** 分页结果：每页对应正文的一段字符区间 [start, end) 或独立插图 */
object TextPaginator {

    fun paginate(
        text: String,
        paint: TextPaint,
        width: Int,
        firstHeight: Int,
        otherHeight: Int,
        spacingMult: Float,
    ): List<Pair<Int, Int>> {
        if (text.isEmpty() || width <= 0) return listOf(0 to 0)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, spacingMult)
            .setIncludePad(false)
            .build()

        val pages = ArrayList<Pair<Int, Int>>()
        val total = layout.lineCount
        var line = 0
        while (line < total) {
            val limit = if (pages.isEmpty()) firstHeight else otherHeight
            val top = layout.getLineTop(line)
            var end = line
            while (end < total && layout.getLineBottom(end) - top <= limit) end++
            if (end == line) end = line + 1          // 一行都放不下也得放一行，防止死循环
            pages.add(layout.getLineStart(line) to layout.getLineEnd(end - 1))
            line = end
        }
        return pages.ifEmpty { listOf(0 to text.length) }
    }

    /** 段落整理：按行拆段，去掉空行，可选首行缩进（插图标记行不加缩进） */
    fun normalize(text: String, indent: Boolean): String {
        val sb = StringBuilder()
        text.split("\n").forEach { raw ->
            val p = raw.trim()
            if (p.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                if (indent && !p.startsWith("<!--image-->")) sb.append("\u3000\u3000")
                sb.append(p)
            }
        }
        return sb.toString()
    }

    /**
     * 图文混合切页：遇到轻小说插图时生成独立插图页（MewX IMAGE_DEPENDENT 模型），
     * 文字段落按可用高度静态排版切片。
     */
    fun paginateBlocks(
        text: String,
        paint: TextPaint,
        width: Int,
        firstHeight: Int,
        otherHeight: Int,
        spacingMult: Float,
    ): List<PageSlice> {
        if (text.isBlank() || width <= 0) return listOf(PageSlice.Text("", true))
        val imgRegex = Regex("<!--image-->([\\s\\S]*?)<!--image-->")
        val matches = imgRegex.findAll(text).toList()
        if (matches.isEmpty()) {
            val ranges = paginate(text, paint, width, firstHeight, otherHeight, spacingMult)
            return ranges.mapIndexed { i, (from, to) ->
                val slice = text.substring(from.coerceIn(0, text.length), to.coerceIn(from, text.length))
                PageSlice.Text(slice, i == 0)
            }
        }

        val slices = ArrayList<PageSlice>()
        var lastIdx = 0
        var isFirst = true

        for (match in matches) {
            val textBefore = text.substring(lastIdx, match.range.first).trim()
            if (textBefore.isNotEmpty()) {
                val fH = if (isFirst) firstHeight else otherHeight
                val ranges = paginate(textBefore, paint, width, fH, otherHeight, spacingMult)
                for (r in ranges) {
                    val slice = textBefore.substring(r.first.coerceIn(0, textBefore.length), r.second.coerceIn(r.first, textBefore.length))
                    slices.add(PageSlice.Text(slice, isFirst))
                    isFirst = false
                }
            }
            val imgUrl = match.groupValues[1].trim()
            if (imgUrl.isNotEmpty()) {
                slices.add(PageSlice.Image(imgUrl))
                isFirst = false
            }
            lastIdx = match.range.last + 1
        }

        if (lastIdx < text.length) {
            val remaining = text.substring(lastIdx).trim()
            if (remaining.isNotEmpty()) {
                val fH = if (isFirst) firstHeight else otherHeight
                val ranges = paginate(remaining, paint, width, fH, otherHeight, spacingMult)
                for (r in ranges) {
                    val slice = remaining.substring(r.first.coerceIn(0, remaining.length), r.second.coerceIn(r.first, remaining.length))
                    slices.add(PageSlice.Text(slice, isFirst))
                    isFirst = false
                }
            }
        }

        return slices.ifEmpty { listOf(PageSlice.Text("", true)) }
    }
}

/**
 * 单页绘制。
 * 标题和正文各用一份 StaticLayout，排版跟滚动模式一致，
 * 不会被 Compose 与系统排版的行高差劈开。
 */
class PageView(context: Context) : View(context) {

    private var titleLayout: StaticLayout? = null
    private var bodyLayout: StaticLayout? = null
    private var titleBlock = 0
    private var lastKey: Any? = null

    /** 同内容不重复排版：翻页滑动时省掉大量无用计算 */
    fun renderIfChanged(
        key: Any,
        title: String,
        titlePaint: TextPaint,
        body: String,
        bodyPaint: TextPaint,
        width: Int,
        spacingMult: Float,
        titleGapPx: Int,
    ) {
        if (key == lastKey) return
        lastKey = key
        render(title, titlePaint, body, bodyPaint, width, spacingMult, titleGapPx)
    }

    fun render(
        title: String,
        titlePaint: TextPaint,
        body: String,
        bodyPaint: TextPaint,
        width: Int,
        spacingMult: Float,
        titleGapPx: Int,
    ) {
        titleLayout = if (title.isBlank()) null else runCatching {
            StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, width)
                .setIncludePad(false)
                .build()
        }.getOrNull()
        titleBlock = (titleLayout?.height ?: 0) + if (titleLayout != null) titleGapPx else 0

        bodyLayout = runCatching {
            StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, width)
                .setLineSpacing(0f, spacingMult)
                .setIncludePad(false)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        }.getOrNull()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        titleLayout?.draw(canvas)
        canvas.translate(0f, titleBlock.toFloat())
        bodyLayout?.draw(canvas)
        canvas.restore()
    }
}

/**
 * 章内分页阅读区：左右滑动翻页（不是切章）。
 * 页数、pager 状态回传给外层，外层负责页码显示和点边缘翻页。
 */
@Composable
fun PagedChapter(
    bodyText: String,
    title: String,
    prefs: ReaderPrefs,
    colors: ReaderTheme,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    onPageCount: (Int) -> Unit,
    onPagerReady: (PagerState) -> Unit,
    onImageClick: (String) -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        val bodyPaint = remember(prefs.fontSize, colors.text) {
            TextPaint().apply {
                isAntiAlias = true
                textSize = with(density) { prefs.fontSize.dp.toPx() }
                color = colors.text.toArgb()
            }
        }
        val titlePaint = remember(prefs.fontSize, colors.text) {
            TextPaint().apply {
                isAntiAlias = true
                textSize = with(density) { (prefs.fontSize + 3).dp.toPx() }
                color = colors.text.toArgb()
                isFakeBoldText = true
            }
        }
        val gapPx = with(density) { 22.dp.toPx() }.toInt()

        val titleH = remember(title, wPx, prefs.fontSize) {
            if (title.isBlank() || wPx <= 0) 0 else runCatching {
                StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, wPx.toInt())
                    .setIncludePad(false)
                    .build().height + gapPx
            }.getOrDefault(0)
        }

        val pages = remember(bodyText, wPx, hPx, prefs.fontSize, prefs.lineHeight, prefs.indent, titleH) {
            if (bodyText.isBlank() || wPx <= 0) listOf(PageSlice.Text("", true))
            else TextPaginator.paginateBlocks(
                text = bodyText,
                paint = bodyPaint,
                width = wPx.toInt(),
                firstHeight = (hPx - titleH - with(density) { 4.dp.toPx() }).toInt().coerceAtLeast(60),
                otherHeight = (hPx - with(density) { 4.dp.toPx() }).toInt(),
                spacingMult = prefs.lineHeight,
            )
        }

        val pager = rememberPagerState(
            initialPage = if (pages.size > 0 && initialPage in 0 until pages.size) initialPage else 0,
            pageCount = { pages.size.coerceAtLeast(1) }
        )

        var restoredPage by remember(bodyText, initialPage) { mutableStateOf(false) }

        LaunchedEffect(pages.size, initialPage) {
            onPageCount(pages.size)
            if (!restoredPage && pages.isNotEmpty()) {
                val target = initialPage.coerceIn(0, pages.size - 1)
                if (target > 0 && pager.currentPage != target) {
                    pager.scrollToPage(target)
                }
                restoredPage = true
            }
        }
        LaunchedEffect(pager) { onPagerReady(pager) }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            val page = pages.getOrElse(i) { PageSlice.Text("", false) }
            when (page) {
                is PageSlice.Text -> {
                    AndroidView(
                        factory = { ctx -> PageView(ctx) },
                        update = { v ->
                            v.renderIfChanged(
                                key = "$i|${page.content.hashCode()}|${prefs.fontSize}|${prefs.lineHeight}|${colors.text}|${page.showTitle}",
                                title = if (page.showTitle) title else "",
                                titlePaint = titlePaint,
                                body = page.content,
                                bodyPaint = bodyPaint,
                                width = wPx.toInt(),
                                spacingMult = prefs.lineHeight,
                                titleGapPx = gapPx,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is PageSlice.Image -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(page.url)
                                .crossfade(true)
                                .build(),
                            contentDescription = "插图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(page.url) }
                        )
                    }
                }
            }
        }
    }
}
