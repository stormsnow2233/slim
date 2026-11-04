package com.zaka.shijuan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaka.shijuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    book: Book,
    chapters: List<Chapter>,
    startIndex: Int,
    store: Store,
    onBack: () -> Unit,
    onProgress: (Int, String, Float, Int) -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, maxOf(0, chapters.size - 1))) }
    var content by remember { mutableStateOf<Content?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showBar by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var prefs by remember { mutableStateOf(store.readerPrefs()) }

    // 章内分页状态
    var pageCount by remember { mutableIntStateOf(1) }
    var pagerState by remember { mutableStateOf<PagerState?>(null) }
    var currentPage by remember { mutableIntStateOf(0) }

    val theme = ReaderThemes.getOrElse(prefs.themeIndex) { ReaderThemes[0] }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val tocState = rememberLazyListState()
    val chapter = chapters.getOrNull(index)

    // 加载章节 + 后台预取下一章
    LaunchedEffect(index) {
        val ch = chapters.getOrNull(index) ?: return@LaunchedEffect
        loading = true
        error = ""
        content = null
        pageCount = 1
        currentPage = 0
        scrollState.scrollTo(0)
        withContext(Dispatchers.IO) {
            val src = Sources.byId(book.sourceId)
            val cached = store.loadContent(ch.url)
            if (cached != null) {
                content = Content(ch.name, cached)
                loading = false
            } else if (src == null) {
                error = "书源不存在"; loading = false
            } else {
                BookRepo.getChapter(src, ch)
                    .onSuccess {
                        content = it
                        store.saveContent(ch.url, it.text)
                    }
                    .onFailure { error = it.message ?: "加载失败" }
                loading = false
            }

            // 提前把下一章拉回来，翻到下一章基本不用等
            val next = chapters.getOrNull(index + 1)
            if (src != null && next != null) {
                BookRepo.prefetchChapter(src, next, store)
                if (chapters.getOrNull(index + 2) != null) {
                    chapters.getOrNull(index + 2)?.let { BookRepo.prefetchChapter(src, it, store) }
                }
            }
        }
    }

    // 阅读进度回传（节流）
    var lastSave by remember { mutableLongStateOf(0L) }
    fun saveProgress(p: Float) {
        val now = System.currentTimeMillis()
        if (now - lastSave < 1200) return
        lastSave = now
        onProgress(index, chapter?.name ?: "", p, chapters.size)
    }

    LaunchedEffect(scrollState.value, scrollState.maxValue, loading) {
        if (scrollState.maxValue > 0) {
            saveProgress(scrollState.value.toFloat() / scrollState.maxValue)
        }
    }

    // 翻页模式下按页数算进度
    LaunchedEffect(currentPage, pageCount) {
        if (prefs.pageMode == 1 && pageCount > 0) {
            saveProgress((currentPage + 1).toFloat() / pageCount)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val p = when {
                prefs.pageMode == 1 && pageCount > 0 -> (currentPage + 1).toFloat() / pageCount
                scrollState.maxValue > 0 -> scrollState.value.toFloat() / scrollState.maxValue
                else -> 0f
            }
            onProgress(index, chapter?.name ?: "", p, chapters.size)
        }
    }

    BackHandler(enabled = showSettings || showToc) {
        showSettings = false; showToc = false
    }
    BackHandler(enabled = !showSettings && !showToc && showBar) { showBar = false }

    // 翻页模式下的边缘点击：先翻页，翻到头才切章
    fun handleTap(x: Float, width: Int) {
        if (prefs.pageMode == 1) {
            when {
                x < width * 0.30f -> {
                    val p = pagerState
                    if (p != null && p.currentPage > 0) {
                        scope.launch { p.animateScrollToPage(p.currentPage - 1) }
                    } else if (index > 0) index--
                }
                x > width * 0.70f -> {
                    val p = pagerState
                    if (p != null && p.currentPage < pageCount - 1) {
                        scope.launch { p.animateScrollToPage(p.currentPage + 1) }
                    } else if (index < chapters.size - 1) index++
                }
                else -> showBar = !showBar
            }
        } else {
            when {
                x < width * 0.28f -> if (index > 0) index-- else showBar = true
                x > width * 0.72f -> if (index < chapters.size - 1) index++ else showBar = true
                else -> showBar = !showBar
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bg)
            .pointerInput(prefs.pageMode, pageCount, pagerState) {
                detectTapGestures(onTap = { offset -> handleTap(offset.x, size.width) })
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(statusBarHeight()))

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp), strokeWidth = 2.dp,
                        color = theme.text.copy(alpha = 0.5f),
                    )
                }

                error.isNotBlank() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("这章没加载出来", color = theme.text, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(error, color = theme.text.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val ch = chapters.getOrNull(index) ?: return@Button
                        loading = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val src = Sources.byId(book.sourceId)
                                if (src != null) {
                                    BookRepo.getChapter(src, ch).onSuccess {
                                        content = it; error = ""; store.saveContent(ch.url, it.text)
                                    }.onFailure { error = it.message ?: "加载失败" }
                                }
                                loading = false
                            }
                        }
                    }) { Text("重试") }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { if (index < chapters.size - 1) index++ }) {
                        Text("换下一章")
                    }
                }

                prefs.pageMode == 1 -> {
                    // ── 章内左右滑动翻页 ──
                    val bodyText = remember(content, prefs.indent) {
                        TextPaginator.normalize(content?.text.orEmpty(), prefs.indent)
                    }
                    Box(Modifier.fillMaxSize()) {
                        key(index, prefs.fontSize, prefs.lineHeight, prefs.indent) {
                            PagedChapter(
                                bodyText = bodyText,
                                title = content?.title ?: chapter?.name ?: "",
                                prefs = prefs,
                                colors = theme,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp),
                                onPageCount = { pageCount = it },
                                onPagerReady = { p ->
                                    pagerState = p
                                    currentPage = p.currentPage
                                },
                            )
                        }
                        Text(
                            "${currentPage + 1} / $pageCount",
                            color = theme.text.copy(alpha = 0.32f),
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp),
                        )
                    }
                    LaunchedEffect(pagerState) {
                        val p = pagerState ?: return@LaunchedEffect
                        snapshotFlow { p.currentPage }.collect { currentPage = it }
                    }
                }

                else -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 22.dp)
                    ) {
                        Spacer(Modifier.height(10.dp))
                        ChapterTitle(content?.title ?: chapter?.name ?: "", prefs, theme)
                        BodyText(content?.text ?: "", prefs, theme)
                        Spacer(Modifier.height(24.dp))
                        FootNav(index, chapters.size, theme) { }
                        Spacer(Modifier.height(60.dp))
                    }
                }
            }
        }

        // ── 顶部栏 ──
        AnimatedVisibility(
            visible = showBar,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(theme.bar)
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    onProgress(
                        index, chapter?.name ?: "",
                        if (prefs.pageMode == 1 && pageCount > 0) (currentPage + 1).toFloat() / pageCount
                        else if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue
                        else 0f,
                        chapters.size,
                    )
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = theme.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        book.name, color = theme.text, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${index + 1} / ${chapters.size}",
                        color = theme.text.copy(alpha = 0.6f), fontSize = 11.sp,
                    )
                }
                IconButton(onClick = { showToc = true }) {
                    Icon(Icons.Rounded.FormatListBulleted, "目录", tint = theme.text)
                }
            }
        }

        // ── 底部栏 ──
        AnimatedVisibility(
            visible = showBar,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(theme.bar)
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarAction("上一章", Icons.AutoMirrored.Rounded.KeyboardArrowLeft, theme, index > 0) {
                    index--
                }
                BarAction("目录", Icons.Rounded.FormatListBulleted, theme, true) { showToc = true }
                BarAction("设置", Icons.Rounded.TextFields, theme, true) { showSettings = true }
                BarAction("下一章", Icons.AutoMirrored.Rounded.KeyboardArrowRight, theme, index < chapters.size - 1) {
                    index++
                }
            }
        }
    }

    // ── 设置面板 ──
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 30.dp)) {
                Text("阅读设置", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(18.dp))

                Text("字号 ${prefs.fontSize.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = prefs.fontSize,
                    onValueChange = { prefs = prefs.copy(fontSize = it); store.saveReaderPrefs(prefs) },
                    valueRange = 14f..30f, steps = 15,
                )

                Text("行距 ${String.format("%.2f", prefs.lineHeight)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = prefs.lineHeight,
                    onValueChange = { prefs = prefs.copy(lineHeight = it); store.saveReaderPrefs(prefs) },
                    valueRange = 1.2f..2.4f, steps = 11,
                )

                Spacer(Modifier.height(10.dp))
                Text("背景", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderThemes.forEachIndexed { i, t ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(t.bg)
                                    .clickable {
                                        prefs = prefs.copy(themeIndex = i)
                                        store.saveReaderPrefs(prefs)
                                    }
                            ) {
                                if (i == prefs.themeIndex) {
                                    Icon(
                                        Icons.Rounded.Check, null,
                                        Modifier.size(18.dp).align(Alignment.Center), tint = t.text,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("翻页方式", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (prefs.pageMode == 0) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { prefs = prefs.copy(pageMode = 0); store.saveReaderPrefs(prefs) },
                    ) { Text("上下滚动", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (prefs.pageMode == 1) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { prefs = prefs.copy(pageMode = 1); store.saveReaderPrefs(prefs) },
                    ) { Text("左右翻页", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (prefs.pageMode == 1) "左右滑动在章内翻页，翻到本章最后一页继续滑才进下一章"
                    else "上下滑到底可继续往下读",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }

    // ── 目录 ──
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Column(Modifier.fillMaxHeight(0.85f)) {
                Text(
                    "目录 · ${chapters.size} 章",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                )
                LaunchedEffect(Unit) {
                    runCatching { tocState.scrollToItem(index) }
                }
                LazyColumn(state = tocState, modifier = Modifier.weight(1f)) {
                    items(chapters, key = { it.url + it.index }) { ch ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showToc = false; index = ch.index }
                                .padding(horizontal = 22.dp, vertical = 13.dp)
                        ) {
                            Text(
                                ch.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (ch.index == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (ch.index == index) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterTitle(title: String, prefs: ReaderPrefs, theme: ReaderTheme) {
    if (title.isBlank()) return
    Text(
        title,
        color = theme.text,
        fontSize = (prefs.fontSize + 3).sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
    )
}

@Composable
private fun BodyText(text: String, prefs: ReaderPrefs, theme: ReaderTheme) {
    val paragraphs = remember(text) {
        text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    Column {
        paragraphs.forEach { p ->
            Text(
                text = if (prefs.indent) "　　$p" else p,
                color = theme.text,
                fontSize = prefs.fontSize.sp,
                lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
                modifier = Modifier.padding(bottom = (prefs.fontSize * 0.45f).dp),
            )
        }
    }
}

@Composable
private fun FootNav(index: Int, total: Int, theme: ReaderTheme, onPrev: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (index >= total - 1) "—— 全书完 ——" else "—— 第 ${index + 1} / $total 章 ——",
            color = theme.text.copy(alpha = 0.45f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BarAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    theme: ReaderTheme,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(
            icon, label,
            tint = theme.text.copy(alpha = if (enabled) 0.9f else 0.3f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = theme.text.copy(alpha = if (enabled) 0.7f else 0.3f))
    }
}

@Composable
private fun statusBarHeight(): androidx.compose.ui.unit.Dp {
    val cfg = LocalConfiguration.current
    return WindowInsets.statusBars.getTop(LocalDensity.current).dp
}
