package com.zaka.shijuan.ui

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zaka.shijuan.data.ReaderPrefs

/** 分页结果：每页对应正文的一段字符区间 [start, end) */
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

    /** 段落整理：按行拆段，去掉空行，可选首行缩进 */
    fun normalize(text: String, indent: Boolean): String {
        val sb = StringBuilder()
        text.split("\n").forEach { raw ->
            val p = raw.trim()
            if (p.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                if (indent) sb.append("\u3000\u3000")
                sb.append(p)
            }
        }
        return sb.toString()
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
    onPageCount: (Int) -> Unit,
    onPagerReady: (PagerState) -> Unit,
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
            if (bodyText.isBlank() || wPx <= 0) listOf(0 to 0)
            else TextPaginator.paginate(
                text = bodyText,
                paint = bodyPaint,
                width = wPx.toInt(),
                firstHeight = (hPx - titleH - with(density) { 4.dp.toPx() }).toInt().coerceAtLeast(60),
                otherHeight = (hPx - with(density) { 4.dp.toPx() }).toInt(),
                spacingMult = prefs.lineHeight,
            )
        }

        val pager = rememberPagerState(pageCount = { pages.size })

        LaunchedEffect(pages.size) { onPageCount(pages.size) }
        LaunchedEffect(pager) { onPagerReady(pager) }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { i ->
            val page = pages.getOrElse(i) { 0 to 0 }
            val from = page.first.coerceIn(0, bodyText.length)
            val to = page.second.coerceIn(from, bodyText.length)
            val slice = bodyText.substring(from, to)
            val showTitle = i == 0 && title.isNotBlank()

            AndroidView(
                factory = { ctx -> PageView(ctx) },
                update = { v ->
                    v.renderIfChanged(
                        key = "$i|${from}|${to}|${prefs.fontSize}|${prefs.lineHeight}|${colors.text}|${showTitle}",
                        title = if (showTitle) title else "",
                        titlePaint = titlePaint,
                        body = slice,
                        bodyPaint = bodyPaint,
                        width = wPx.toInt(),
                        spacingMult = prefs.lineHeight,
                        titleGapPx = gapPx,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
