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
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    initialPage: Int = 0,
    store: Store,
    onBack: () -> Unit,
    onSwitchBook: (Book) -> Unit = {},
    onProgress: (Int, String, Float, Int, Int) -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, maxOf(0, chapters.size - 1))) }
    var targetPageForChapter by remember {
        mutableIntStateOf(if (initialPage > 0) initialPage else store.loadBookPage(book.url, startIndex))
    }
    var content by remember { mutableStateOf<Content?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var showBar by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    // 源站这本看不了时（章节页出错/收费），一键搜同名书换源
    var altLoading by remember { mutableStateOf(false) }
    var altBooks by remember { mutableStateOf<List<Book>>(emptyList()) }
    var prefs by remember { mutableStateOf(store.readerPrefs()) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }

    // 章内分页状态
    var pageCount by remember { mutableIntStateOf(1) }
    var pagerState by remember { mutableStateOf<PagerState?>(null) }
    var currentPage by remember { mutableIntStateOf(targetPageForChapter) }

    val theme = ReaderThemes.getOrElse(prefs.themeIndex) { ReaderThemes[0] }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val tocState = rememberLazyListState()
    val chapter = chapters.getOrNull(index)

    // 切章统一方法：离开前保存当前章页码，切到新章指定页
    fun switchChapter(newIndex: Int, newPage: Int = 0) {
        if (newIndex !in chapters.indices || newIndex == index) return
        val p = when {
            prefs.pageMode == 1 && pageCount > 0 -> (currentPage + 1).toFloat() / pageCount
            scrollState.maxValue > 0 -> scrollState.value.toFloat() / scrollState.maxValue
            else -> 0f
        }
        store.saveBookPage(book.url, index, currentPage)
        onProgress(index, chapter?.name ?: "", p, currentPage, chapters.size)

        targetPageForChapter = newPage
        currentPage = newPage
        index = newIndex
    }

    // 加载章节 + 后台预取下一章
    LaunchedEffect(index) {
        val ch = chapters.getOrNull(index) ?: return@LaunchedEffect
        loading = true
        error = ""
        content = null
        pageCount = 1
        currentPage = targetPageForChapter
        val savedScroll = store.loadBookScroll(book.url, index)
        if (savedScroll <= 0) scrollState.scrollTo(0)
        withContext(Dispatchers.IO) {
            val src = Sources.byId(book.sourceId)
            val cached = store.loadContent(ch.url)
            if (cached != null && !BookRepo.isTranscodeOrPiracyError(cached) && !BookRepo.isSoftError(cached)) {
                content = Content(ch.name, cached)
                loading = false
            } else if (src == null) {
                error = "这个书源已被删除或停用，换个源看这本"; loading = false
            } else {
                BookRepo.getChapter(src, ch)
                    .onSuccess {
                        content = it
                        store.saveContent(ch.url, it.text)
                    }
                    .onFailure {
                        error = if (!Offline.isOnline(ctx)) "离线模式：这一章没缓存过，联网后再看"
                        else it.message ?: "加载失败"
                    }
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
    fun saveProgress(p: Float, page: Int = currentPage) {
        val now = System.currentTimeMillis()
        if (now - lastSave < 1200) return
        lastSave = now
        store.saveBookPage(book.url, index, page)
        onProgress(index, chapter?.name ?: "", p, page, chapters.size)
    }

    LaunchedEffect(scrollState.value, scrollState.maxValue, loading) {
        if (scrollState.maxValue > 0) {
            val ratio = scrollState.value.toFloat() / scrollState.maxValue
            store.saveBookScroll(book.url, index, scrollState.value)
            saveProgress(ratio, 0)
        }
    }

    // 翻页模式下按页数算进度并实时持久化当前页码
    LaunchedEffect(currentPage, pageCount) {
        if (prefs.pageMode == 1 && pageCount > 0) {
            store.saveBookPage(book.url, index, currentPage)
            saveProgress((currentPage + 1).toFloat() / pageCount, currentPage)
        }
    }

    // 滚动模式恢复保存的滚动高度
    LaunchedEffect(scrollState.maxValue, index) {
        if (prefs.pageMode != 1 && scrollState.maxValue > 0) {
            val savedScroll = store.loadBookScroll(book.url, index)
            if (savedScroll > 0 && scrollState.value == 0) {
                scrollState.scrollTo(savedScroll.coerceIn(0, scrollState.maxValue))
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val p = when {
                prefs.pageMode == 1 && pageCount > 0 -> (currentPage + 1).toFloat() / pageCount
                scrollState.maxValue > 0 -> scrollState.value.toFloat() / scrollState.maxValue
                else -> 0f
            }
            store.saveBookPage(book.url, index, currentPage)
            onProgress(index, chapter?.name ?: "", p, currentPage, chapters.size)
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
                    } else if (index > 0) switchChapter(index - 1)
                }
                x > width * 0.70f -> {
                    val p = pagerState
                    if (p != null && p.currentPage < pageCount - 1) {
                        scope.launch { p.animateScrollToPage(p.currentPage + 1) }
                    } else if (index < chapters.size - 1) switchChapter(index + 1)
                }
                else -> showBar = !showBar
            }
        } else {
            when {
                x < width * 0.28f -> if (index > 0) switchChapter(index - 1) else showBar = true
                x > width * 0.72f -> if (index < chapters.size - 1) switchChapter(index + 1) else showBar = true
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { if (index < chapters.size - 1) switchChapter(index + 1) }) {
                            Text("下一章")
                        }
                        OutlinedButton(onClick = { showToc = true }) {
                            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("选择章节")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (altBooks.isEmpty()) {
                        TextButton(enabled = !altLoading, onClick = {
                            altLoading = true
                            scope.launch {
                                val key = book.name.trim().take(24)
                                val found = withContext(Dispatchers.IO) {
                                    val on = store.enabledSources()
                                    val srcs = Sources.all.filter { it.id in on && it.id != book.sourceId }
                                    val got = mutableListOf<Book>()
                                    BookRepo.searchAll(srcs, key) { r ->
                                        if (r.ok) r.books
                                            .filter { it.name.replace(Regex("\\s"), "").contains(key.take(6).replace(Regex("\\s"), ""), true) }
                                            .take(2).let { got.addAll(it) }
                                    }
                                    got.distinctBy { it.sourceId + it.url }.take(5)
                                }
                                altBooks = found
                                altLoading = false
                            }
                        }) {
                            Text(if (altLoading) "正在找其它源…" else "换个源看这本", fontSize = 12.sp)
                        }
                    } else {
                        Text("别的源也收着这本：", color = theme.text.copy(alpha = 0.5f), fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        altBooks.forEach { b ->
                            TextButton(onClick = { onSwitchBook(b) }) {
                                Text(
                                    "《${b.name}》· ${Sources.byId(b.sourceId)?.name ?: b.sourceId}",
                                    fontSize = 12.sp, maxLines = 1,
                                )
                            }
                        }
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
                                initialPage = targetPageForChapter,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp),
                                onPageCount = { pageCount = it },
                                onPagerReady = { p ->
                                    pagerState = p
                                    currentPage = p.currentPage
                                },
                                onImageClick = { previewImageUrl = it },
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
                        snapshotFlow { p.currentPage }.collect {
                            currentPage = it
                            targetPageForChapter = it
                        }
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
                        ChapterTitle(content?.title ?: chapter?.name ?: "", chapter?.volume.orEmpty(), prefs, theme)
                        BodyText(content?.text ?: "", prefs, theme, onImageClick = { previewImageUrl = it })
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
                    val p = when {
                        prefs.pageMode == 1 && pageCount > 0 -> (currentPage + 1).toFloat() / pageCount
                        scrollState.maxValue > 0 -> scrollState.value.toFloat() / scrollState.maxValue
                        else -> 0f
                    }
                    store.saveBookPage(book.url, index, currentPage)
                    onProgress(index, chapter?.name ?: "", p, currentPage, chapters.size)
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
                    switchChapter(index - 1)
                }
                BarAction("目录", Icons.Rounded.FormatListBulleted, theme, true) { showToc = true }
                BarAction("设置", Icons.Rounded.TextFields, theme, true) { showSettings = true }
                BarAction("下一章", Icons.AutoMirrored.Rounded.KeyboardArrowRight, theme, index < chapters.size - 1) {
                    switchChapter(index + 1)
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
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showToc = false
                                    switchChapter(ch.index, store.loadBookPage(book.url, ch.index))
                                }
                                .padding(horizontal = 22.dp, vertical = 10.dp)
                        ) {
                            if (ch.volume.isNotBlank()) {
                                Text(
                                    ch.volume,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
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

    // ── 轻小说插图全屏缩放预览（参考 MewX 双击缩放与手势操作） ──
    if (previewImageUrl != null) {
        ImageViewerDialog(previewImageUrl!!) { previewImageUrl = null }
    }
}

@Composable
private fun ChapterTitle(title: String, volume: String = "", prefs: ReaderPrefs, theme: ReaderTheme) {
    if (title.isBlank()) return
    Column(Modifier.padding(top = 8.dp, bottom = 14.dp)) {
        if (volume.isNotBlank()) {
            Text(
                volume,
                color = theme.text.copy(alpha = 0.55f),
                fontSize = (prefs.fontSize * 0.72f).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Text(
            title,
            color = theme.text,
            fontSize = (prefs.fontSize + 3).sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (prefs.fontSize * prefs.lineHeight).sp,
        )
    }
}

@Composable
private fun BodyText(
    text: String,
    prefs: ReaderPrefs,
    theme: ReaderTheme,
    onImageClick: (String) -> Unit = {}
) {
    val paragraphs = remember(text) {
        text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val imgRegex = remember { Regex("^<!--image-->([\\s\\S]*?)<!--image-->$") }

    Column {
        paragraphs.forEach { p ->
            val match = imgRegex.find(p)
            if (match != null) {
                val imgUrl = match.groupValues[1].trim()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = theme.bar.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onImageClick(imgUrl) }
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(imgUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "插图",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 560.dp)
                        )
                    }
                }
            } else {
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
}

@Composable
private fun ImageViewerDialog(
    imageUrl: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.8f, 5f)
        offset += offsetChange
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1.2f) 1f else 2.5f
                            offset = Offset.Zero
                        },
                        onTap = { onDismiss() }
                    )
                }
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "全屏插图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .transformable(state = transformState)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
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
