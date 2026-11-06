package com.zaka.shijuan.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaka.shijuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    book: Book,
    store: Store,
    onBack: () -> Unit,
    onRead: (Book, List<Chapter>, Int, Int) -> Unit,
    onShelfChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var full by remember { mutableStateOf(book) }
    var toc by remember { mutableStateOf<List<Chapter>>(store.loadToc(book.url)) }
    var loading by remember { mutableStateOf(toc.isEmpty()) }
    var error by remember { mutableStateOf("") }
    var inShelf by remember { mutableStateOf(store.inShelf(book)) }
    var showToc by remember { mutableStateOf(false) }
    var tocQuery by remember { mutableStateOf("") }
    var expandedIntro by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf("") }
    val export by Exporter.state.collectAsState()

    val chapterList = remember(toc, tocQuery) {
        if (tocQuery.isBlank()) toc else toc.filter { it.name.contains(tocQuery, true) }
    }

    fun loadToc(force: Boolean = false) {
        scope.launch {
            // 离线：本地有目录就直接用，不去撞网络；本地没有就直说
            if (!force && !Offline.isOnline(ctx)) {
                if (toc.isEmpty()) error = "离线模式：这本的目录没存过，联网后再进"
                loading = false
                return@launch
            }
            loading = true
            error = ""
            withContext(Dispatchers.IO) {
                val src = Sources.byId(book.sourceId)
                if (src == null) {
                    error = "这个书源已被删除或停用，点「换个源」再找一次"; loading = false; return@withContext
                }
                val (det, tocRes) = BookRepo.getDetailAndToc(src, full)
                full = det
                tocRes.onSuccess {
                    toc = it.chapters
                    store.saveToc(book.url, it.chapters)
                    full = full.copy(
                        cover = full.cover.ifBlank { it.cover },
                        intro = full.intro.ifBlank { it.intro },
                        author = full.author.ifBlank { it.author },
                    )
                    if (it.chapters.isEmpty()) error = "目录为空"
                    store.rememberBook(full)
                }.onFailure { error = it.message ?: "目录加载失败" }
                loading = false
            }
        }
    }

    LaunchedEffect(book.url) {
        if (toc.isNotEmpty()) {
            // 有缓存：先把目录放出来，详情后台补，打开就秒显
            val s0 = Sources.byId(book.sourceId)
            if (s0 != null) withContext(Dispatchers.IO) {
                full = runCatching { BookRepo.getDetail(s0, book) }.getOrDefault(book)
            }
            return@LaunchedEffect
        }
        loadToc()
    }

    // 上次读到哪：阅读记忆优先（不管在不在书架都记得），其次书架进度
    val shelfItem = remember(full.url) {
        store.shelf().find { it.book.url == full.url && it.book.sourceId == full.sourceId }
    }
    val record = remember(full.url, toc.size) { store.recordOf(full) }
    val startIdx = (record?.chapterIndex ?: shelfItem?.chapterIndex ?: 0)
        .coerceIn(0, (toc.size - 1).coerceAtLeast(0))
    val startName = record?.chapterName?.ifBlank { null } ?: shelfItem?.chapterName.orEmpty()
    val startPage = record?.pageIndex ?: shelfItem?.pageIndex ?: store.loadBookPage(full.url, startIdx)

    fun read(idx: Int, fromContinue: Boolean = false) {
        if (toc.isEmpty()) return
        val targetIdx = idx.coerceIn(0, toc.size - 1)
        val page = if (fromContinue || targetIdx == startIdx) startPage else store.loadBookPage(full.url, targetIdx)
        onRead(full, toc, targetIdx, page)
    }

    fun doExport(format: String) {
        if (toc.isEmpty()) {
            Toast.makeText(ctx, "目录还没加载出来", Toast.LENGTH_SHORT).show()
            return
        }
        showExport = true
        Exporter.start(ctx, full, toc, format, store)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingFormat.isNotBlank()) doExport(pendingFormat)
        else if (!granted) Toast.makeText(ctx, "没存储权限，存不到下载目录", Toast.LENGTH_SHORT).show()
        pendingFormat = ""
    }

    fun requestExport(format: String) {
        pendingFormat = format
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doExport(format)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 背景：封面虚化
        if (full.cover.isNotBlank()) AsyncImageBlur(full.cover)

        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 22 },
        ) {
            Column(Modifier.fillMaxSize()) {

                // ── 顶栏（右上角三点菜单） ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack, "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!loading && toc.isNotEmpty()) {
                        Text(
                            "${toc.size} 章", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Rounded.MoreVert, "更多",
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(14.dp),
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            if (!full.external) {
                                DropdownMenuItem(
                                    text = { Text("下载 TXT") },
                                    leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                    trailingIcon = { Text("${toc.size} 章", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = {
                                        menuOpen = false
                                        requestExport("txt")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("下载 EPUB") },
                                    leadingIcon = { Icon(Icons.Rounded.MenuBook, null) },
                                    trailingIcon = { Text("带目录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    onClick = {
                                        menuOpen = false
                                        requestExport("epub")
                                    },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                            DropdownMenuItem(
                                text = { Text("刷新目录") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = {
                                    menuOpen = false
                                    toc = emptyList()
                                    loadToc()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制书籍链接") },
                                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                onClick = {
                                    menuOpen = false
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("书架", full.url))
                                    Toast.makeText(ctx, "链接已复制", Toast.LENGTH_SHORT).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("浏览器打开") },
                                leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, null) },
                                onClick = {
                                    menuOpen = false
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full.url)))
                                    }
                                },
                            )
                        }
                    }
                }

                Row(Modifier.padding(horizontal = 20.dp)) {
                    BookCover(
                        cover = full.cover,
                        name = full.name,
                        modifier = Modifier.size(width = 104.dp, height = 148.dp),
                        corner = 10.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            full.name,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            full.author.ifBlank { "佚名" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            TinyTag(full.sourceName)
                            if (full.kind.isNotBlank()) TinyTag(full.kind, MaterialTheme.colorScheme.secondary)
                            if (full.latest.isNotBlank()) {
                                TinyTag("更新 ${full.latest.take(10)}", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (full.external) {
                            Button(
                                onClick = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(full.downloadUrl.ifBlank { full.url }))
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("打开下载页")
                            }
                        } else {
                            Button(
                                onClick = { read(startIdx, fromContinue = true) },
                                enabled = toc.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.MenuBook, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    when {
                                        startName.isNotBlank() -> {
                                            val pageStr = if (startPage > 0) " (第${startPage + 1}页)" else ""
                                            "继续阅读 · ${startName.take(8)}$pageStr"
                                        }
                                        toc.isNotEmpty() -> "开始阅读"
                                        loading -> "加载中…"
                                        else -> "暂无目录"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // 简介
                if (full.intro.isNotBlank()) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            full.intro,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expandedIntro) 40 else 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 21.sp,
                        )
                        TextButton(
                            onClick = { expandedIntro = !expandedIntro },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text(if (expandedIntro) "收起" else "展开简介", fontSize = 12.sp)
                        }
                    }
                }

                // 操作行
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        if (inShelf) {
                            store.removeFromShelf(full.url, full.sourceId)
                            inShelf = false
                        } else {
                            store.addToShelf(full)
                            inShelf = true
                        }
                        onShelfChanged()
                    }) {
                        Icon(
                            if (inShelf) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            null, Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (inShelf) "已在书架" else "加入书架")
                    }
                    TextButton(onClick = { showToc = true }) {
                        Icon(Icons.Rounded.FormatListBulleted, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("目录")
                    }
                    Spacer(Modifier.weight(1f))
                    if (toc.isNotEmpty()) {
                        TextButton(onClick = { read(toc.size - 1) }) {
                            Text("看最新")
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                // 目录预览
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                } else if (error.isNotBlank()) {
                    EmptyState(
                        title = "目录没加载出来",
                        subtitle = error,
                        action = {
                            Button(onClick = {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full.url)))
                                }
                            }) { Text("浏览器打开") }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { loadToc() }) { Text("重试") }
                        },
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(toc.take(if (showToc) toc.size else 12), key = { it.url + it.index }) { ch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { read(ch.index) }
                                    .padding(horizontal = 22.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ch.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (ch.index == startIdx) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(Modifier.weight(1f))
                                if (ch.index == startIdx && startName.isNotBlank()) {
                                    TinyTag("上次读到")
                                }
                            }
                        }
                        if (!showToc && toc.size > 12) {
                            item {
                                TextButton(
                                    onClick = { showToc = true },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) { Text("查看全部 ${toc.size} 章") }
                            }
                        }
                    }
                }
            }
        }

        // 导出进度浮层
        if (showExport && (export.running || export.finished || export.failed)) {
            ExportSheet(
                export = export,
                onCancel = { Exporter.cancel(); showExport = false },
                onClose = { showExport = false },
            )
        }
    }

    // 全屏目录抽屉
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Column(Modifier.fillMaxHeight(0.86f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("目录", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${toc.size} 章", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextField(
                    value = tocQuery,
                    onValueChange = { tocQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
                    placeholder = { Text("搜索章节", fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                )
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(chapterList, key = { it.url + it.index }) { ch ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showToc = false
                                    read(ch.index)
                                }
                                .padding(horizontal = 22.dp, vertical = 13.dp)
                        ) {
                            Text(
                                ch.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (ch.index == startIdx) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 导出进度 / 结果 */
@Composable
private fun ExportSheet(
    export: ExportState,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val progress by animateFloatAsState(export.percent, tween(280), label = "exp")
    AlertDialog(
        onDismissRequest = { if (!export.running) onClose() },
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                when {
                    export.running -> "正在导出 ${export.format}"
                    export.failed -> "导出没成功"
                    else -> "导出完成"
                },
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                Text(
                    export.bookName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (export.running) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${export.done} / ${export.total}  ·  ${export.current}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "章节多的话要等几分钟，退出这个页面也会继续下",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                } else {
                    Text(export.message, fontSize = 13.sp)
                    if (export.savedPath.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            export.savedPath,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (export.running) TextButton(onClick = onCancel) { Text("取消") }
            else TextButton(onClick = onClose) { Text("好") }
        },
    )
}

@Composable
private fun AsyncImageBlur(url: String) {
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {}
        coil.compose.AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(38.dp)
                .background(Color.Transparent),
            alpha = 0.22f,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                )
            )
        )
    }
}
