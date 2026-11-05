package com.zaka.shijuan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.zaka.shijuan.data.Comic
import com.zaka.shijuan.data.ComicChapter
import com.zaka.shijuan.data.ComicHit
import com.zaka.shijuan.data.ComicDownloader
import com.zaka.shijuan.data.ComicRepo
import com.zaka.shijuan.data.DownloadTask
import com.zaka.shijuan.data.Offline
import com.zaka.shijuan.data.STATE_DONE
import com.zaka.shijuan.data.STATE_ERROR
import com.zaka.shijuan.data.STATE_PAUSED
import com.zaka.shijuan.data.STATE_RUNNING
import com.zaka.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext


/* ──────────────────────────── 漫画首页 ──────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicScreen(
    store: Store,
    onOpen: (Comic) -> Unit,
    onOpenDrawer: () -> Unit = {},
    offline: Boolean = false,
    onOpenCache: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var fromRank by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    val records = remember(tick, store.comicTick, offline) {
        val all = store.comicRecords()
        // 离线时「继续看」只留本地有图的，点进去别白屏
        if (offline) all.filter { ComicDownloader.cachedChapters(it.comic).isNotEmpty() } else all
    }

    // 顶部三个入口：探索 / 书架 / 缓存
    var tab by remember(offline) { mutableStateOf(if (offline) 1 else 0) }
    var shelfTick by remember { mutableStateOf(0) }
    val shelfList = remember(tab, shelfTick, store.comicTick, offline) {
        val all = store.comicShelf()
        // 离线只留本地有图的：缓存过至少一话的才算
        if (offline) all.filter { ComicDownloader.cachedChapters(it).isNotEmpty() } else all
    }
    val dlTasks = ComicDownloader.tasks
    // 每个源各自拿回一批，选「全部」时合并去重，选单个源时只看它
    var hits by remember { mutableStateOf<List<ComicHit>>(emptyList()) }
    var picked by remember { mutableStateOf("all") }
    // 封面补全的触发器：列表先出来，封面随后一批批填上
    var coverToken by remember { mutableStateOf(0) }

    LaunchedEffect(tick, query.isNotBlank()) {
        if (offline) return@LaunchedEffect
        if (query.isNotBlank()) return@LaunchedEffect
        loading = true
        error = ""
        hits = withContext(Dispatchers.IO) {
            // 四个源同时打，谁先回谁先上；单个源挂了不拖累其它源
            coroutineScope {
                ComicRepo.sources.map { src ->
                    async {
                        val t0 = System.currentTimeMillis()
                        runCatching { withTimeoutOrNull(12_000) { src.home() } ?: emptyList() }.fold(
                            onSuccess = { ComicHit(src.id, src.name, it, true, System.currentTimeMillis() - t0) },
                            onFailure = {
                                ComicHit(
                                    src.id, src.name, emptyList(), false,
                                    System.currentTimeMillis() - t0, it.message ?: "失败",
                                )
                            },
                        )
                    }
                }.awaitAll()
            }
        }
        list = ComicRepo.merge(hits)
        fromRank = true
        if (list.isEmpty()) error = "榜单没解析出内容，换个时间再试"
        loading = false
        coverToken++
    }

    // 搜索触发：用一个 token 驱动
    var searchToken by remember { mutableStateOf(0) }
    LaunchedEffect(searchToken) {
        val kw = query.trim()
        if (searchToken == 0) return@LaunchedEffect
        if (kw.isEmpty()) return@LaunchedEffect
        loading = true
        error = ""
        hits = withContext(Dispatchers.IO) { ComicRepo.searchAll(kw, ComicRepo.sources.map { it.id }) }
        list = ComicRepo.merge(hits)
        fromRank = false
        if (list.isEmpty()) {
            error = if (hits.all { !it.ok }) "几个源都连不上，检查一下网络" else "没搜到「$kw」，换个关键词"
        }
        loading = false
        coverToken++
    }

    // 封面按源并发补，补好一个就地刷新，不影响已经显示出来的列表
    LaunchedEffect(coverToken) {
        if (coverToken == 0) return@LaunchedEffect
        val snapshot = hits
        val pend = snapshot.filter { it.ok && it.list.any { c -> c.cover.isBlank() } }
        if (pend.isEmpty()) return@LaunchedEffect
        val filled = withContext(Dispatchers.IO) {
            coroutineScope {
                pend.map { h ->
                    async {
                        val src = ComicRepo.byIdOrNull(h.sourceId) ?: return@async h
                        val got = runCatching { withTimeoutOrNull(10_000) { src.fillCovers(h.list) } }.getOrNull()
                        if (got == null) h else h.copy(list = got)
                    }
                }.awaitAll()
            }
        }
        hits = hits.map { old -> filled.firstOrNull { it.sourceId == old.sourceId } ?: old }
    }

    // 当前选中源要展示的列表
    val shown = remember(hits, picked) {
        if (picked == "all") ComicRepo.merge(hits)
        else hits.firstOrNull { it.sourceId == picked }?.list ?: emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerButton(onClick = onOpenDrawer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("漫画", style = MaterialTheme.typography.headlineLarge)
                Text(
                    if (offline) "离线模式 · 只显示缓存过的漫画"
                    else ComicRepo.sources.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!offline) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    label = { Text("探索", fontSize = 12.sp) },
                )
            }
            FilterChip(
                selected = tab == 1,
                onClick = { tab = 1; shelfTick++ },
                label = { Text("书架", fontSize = 12.sp) },
            )
            if (!offline) {
                FilterChip(
                    selected = tab == 2,
                    onClick = { tab = 2; shelfTick++ },
                    label = {
                        Text(
                            if (dlTasks.isNotEmpty()) "缓存 ${dlTasks.size}" else "缓存",
                            fontSize = 12.sp,
                        )
                    },
                )
            } else {
                FilterChip(
                    selected = tab == 2,
                    onClick = { tab = 2; shelfTick++ },
                    label = { Text("缓存管理", fontSize = 12.sp) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (tab == 0) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜漫画名，回车开始") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                searchToken++
            }),
            trailingIcon = {
                if (query.isNotBlank()) {
                    TextButton(onClick = {
                        query = ""
                        error = ""
                        tick++
                    }) { Text("清除") }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )

        Spacer(Modifier.height(8.dp))

        // 源切换：全部 / 单个站
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = picked == "all",
                    onClick = { picked = "all" },
                    label = { Text("全部", fontSize = 12.sp) },
                )
            }
            items(ComicRepo.sources, key = { it.id }) { src ->
                val hit = hits.firstOrNull { it.sourceId == src.id }
                FilterChip(
                    selected = picked == src.id,
                    onClick = { picked = src.id },
                    label = {
                        Text(
                            buildString {
                                append(src.name)
                                if (hit != null) {
                                    if (hit.ok) append(" ${hit.list.size}") else append(" ×")
                                }
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (fromRank) "热门榜" else "搜索结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (loading) "加载中…" else "${shown.size} 部",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (query.isBlank()) tick++ else searchToken++
            }) {
                Icon(Icons.Rounded.Refresh, "刷新", Modifier.size(19.dp))
            }
        }

        // 分源状态：哪个源通了、返回几本、花多久，一眼看清
        if (hits.isNotEmpty()) {
            Text(
                hits.joinToString("   ") { h ->
                    if (h.ok) "${h.sourceName} ${h.list.size}本/${h.ms}ms"
                    else "${h.sourceName} ${h.error.take(10).ifBlank { "连不上" }}"
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (error.isNotBlank() && shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { if (query.isBlank()) tick++ else searchToken++ }) {
                        Text("重试")
                    }
                }
            }
        }

        if (loading && shown.isEmpty() && error.isBlank()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (records.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "继续看",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(records, key = { it.comic.url }) { rec ->
                                Column(
                                    Modifier.width(78.dp).clickable {
                                        onOpen(rec.comic)
                                    }
                                ) {
                                    BookCover(
                                        cover = rec.comic.cover,
                                        name = rec.comic.name,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.74f),
                                        corner = 9.dp,
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        rec.comic.name,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        rec.chapterName,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            itemsIndexed(shown, key = { _, c -> c.url }) { i, c ->
                Column(
                    Modifier.clickable { onOpen(c) }
                ) {
                    BookCover(
                        cover = c.cover,
                        name = c.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.74f),
                        corner = 10.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        c.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (c.author.isNotBlank()) {
                        Text(
                            "作者：${c.author}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (c.latest.isNotBlank()) {
                        Text(
                            c.latest,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (picked == "all") {
                        Text(
                            c.sourceName,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        }
        } else if (tab == 1) {
            ComicShelfGrid(
                items = shelfList,
                store = store,
                onOpen = onOpen,
                onChanged = { shelfTick++ },
                offline = offline,
            )
        } else {
            DownloadPanel(
                tasks = dlTasks,
                onOpenComic = onOpen,
                onOpenCache = onOpenCache,
            )
        }
    }
}

/* ──────────────────── 漫画书架 ──────────────────── */

@Composable
private fun ComicShelfGrid(
    items: List<Comic>,
    store: Store,
    onOpen: (Comic) -> Unit,
    onChanged: () -> Unit,
    offline: Boolean = false,
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (offline) "离线模式：没有缓存过的漫画" else "漫画书架还空着",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (offline) "联网后进漫画详情页点「缓存整本」，下载完断网也能看"
                    else "在详情页点「加入书架」，看过的也会自动进这里",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, c -> c.sourceId + c.url }) { _, c ->
            val cached = remember(c.url) { ComicDownloader.cachedChapters(c).size }
            Column(Modifier.clickable { onOpen(c) }) {
                Box {
                    BookCover(
                        cover = c.cover,
                        name = c.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.74f),
                        corner = 10.dp,
                    )
                    if (cached > 0) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(5.dp)
                                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text("已存 $cached", fontSize = 9.sp, color = Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    c.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.sourceName,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        "移出",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            store.removeComicFromShelf(c)
                            onChanged()
                        },
                    )
                }
            }
        }
    }
}

/* ──────────────────── 缓存下载管理 ──────────────────── */

@Composable
private fun DownloadPanel(
    tasks: List<DownloadTask>,
    onOpenComic: (Comic) -> Unit,
    onOpenCache: () -> Unit = {},
) {
    if (tasks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有缓存任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "进漫画详情页，右上角 ⋮ → 缓存整本",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = onOpenCache) { Text("缓存管理 · 看磁盘上存了什么", fontSize = 12.sp) }
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            OutlinedButton(
                onClick = onOpenCache,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("缓存管理 · 占用 / 体检 / 删除", fontSize = 12.sp)
            }
        }
        items(tasks, key = { it.key }) { t ->
            Card(shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BookCover(
                            cover = t.comic.cover,
                            name = t.comic.name,
                            modifier = Modifier.width(46.dp).aspectRatio(0.74f),
                            corner = 8.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.comic.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when (t.state) {
                                    STATE_RUNNING -> t.currentChapter.ifBlank { "准备中…" }
                                    STATE_PAUSED -> "已暂停"
                                    STATE_DONE -> "已缓存完 · ${t.pagesDone} 张"
                                    STATE_ERROR -> t.error.ifBlank { "出错了" }
                                    else -> "等待中"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when (t.state) {
                            STATE_RUNNING -> TextButton(onClick = { ComicDownloader.pause(t.key) }) { Text("暂停", fontSize = 12.sp) }
                            STATE_PAUSED, STATE_ERROR -> TextButton(onClick = { ComicDownloader.resume(t.key) }) { Text("继续", fontSize = 12.sp) }
                            STATE_DONE -> TextButton(onClick = { onOpenComic(t.comic) }) { Text("去看", fontSize = 12.sp) }
                        }
                        IconButton(onClick = { ComicDownloader.remove(t.key) }) {
                            Icon(Icons.Rounded.Delete, "删除", Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { t.percent },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.height(5.dp))
                    Row {
                        Text(
                            "${t.doneChapters}/${t.total} 话 · ${t.pagesDone} 张" +
                                if (t.bytes > 0) " · ${fmtSize(t.bytes)}" else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("${(t.percent * 100).toInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun fmtSize(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1fG".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1fM".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0fK".format(b / 1024.0)
    else -> "${b}B"
}

/* ──────────────────────────── 漫画详情 ──────────────────────────── */

@Composable
fun ComicDetailScreen(
    comic: Comic,
    store: Store,
    onBack: () -> Unit,
    onRead: (Comic, List<ComicChapter>, Int) -> Unit,
) {
    var full by remember { mutableStateOf(comic) }
    var chapters by remember { mutableStateOf<List<ComicChapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var offlineNote by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var shelfTick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current

    LaunchedEffect(comic.url, tick) {
        loading = true
        error = ""
        offlineNote = ""
        // 先把本地目录摆出来：断网、站点抽风都能马上点进去看存过的话
        val local = withContext(Dispatchers.IO) { ComicDownloader.localChapters(comic) }
        if (local.isNotEmpty()) {
            chapters = local
            loading = false
        }
        if (!Offline.isOnline(ctx)) {
            if (local.isEmpty()) {
                error = "离线模式：这本还没缓存过，联网后再试"
            } else {
                offlineNote = "离线模式 · 用的是本地存下来的话数目录"
            }
            loading = false
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val d = ComicRepo.detail(comic)
                val c = ComicRepo.chapters(comic)
                d to c
            }
        }.onSuccess { (d, c) ->
            full = d
            if (c.isNotEmpty()) {
                chapters = c
                error = ""
                offlineNote = ""
            } else if (local.isEmpty()) {
                error = "没解析到话数，可能站点结构变了"
            } else {
                offlineNote = "站点这次没给出目录，先用本地存下来的话数"
            }
        }.onFailure {
            if (local.isEmpty()) {
                error = it.message ?: "加载失败"
            } else {
                offlineNote = "网络没通，先用本地存下来的话数"
            }
        }
        loading = false
    }

    // 哪几话本地真的有图（离线能直接看）
    val cachedSet = remember(comic.url, chapters.size, tick) {
        runCatching { ComicDownloader.cachedChapters(full) }.getOrDefault(emptySet())
    }

    val record = remember(comic.url, chapters.size, tick) { store.comicRecordOf(comic) }
    val startIdx = run {
        val base = (record?.chapterIndex ?: 0).coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        // 离线时上次看到的那话要是没存过，就近落到已缓存的话上，别点进去空欢喜
        if (Offline.isOnline(ctx) || cachedSet.isEmpty() || base in cachedSet) base
        else cachedSet.filter { it <= base }.maxOrNull() ?: cachedSet.min()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Box {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        FilledTonalIconButton(onClick = onBack, shape = CircleShape) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 18.dp)) {
                    BookCover(
                        cover = full.cover,
                        name = full.name,
                        modifier = Modifier
                            .width(112.dp)
                            .aspectRatio(0.72f)
                            .offset(y = (-46).dp),
                        corner = 12.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).padding(top = 6.dp)) {
                        Text(full.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                full.author.ifBlank { null },
                                full.status.ifBlank { null },
                                if (chapters.isNotEmpty()) "共 ${chapters.size} 话" else null,
                            ).joinToString(" · "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (full.latest.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "最新：${full.latest}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { if (chapters.isNotEmpty()) onRead(full, chapters, startIdx) },
                            enabled = chapters.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                when {
                                    record != null && record.chapterName.isNotBlank() ->
                                        "继续看 · ${record.chapterName.take(10)}"
                                    chapters.isNotEmpty() -> "从第 1 话开始"
                                    loading -> "加载中…"
                                    else -> "暂无内容"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        val inShelf = remember(full.url, shelfTick) { store.inComicShelf(full) }
                        val dlTask = ComicDownloader.tasks.firstOrNull { it.key == ComicDownloader.keyOf(full) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (inShelf) store.removeComicFromShelf(full)
                                    else store.addComicToShelf(full)
                                    shelfTick++
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                Text(if (inShelf) "已加入书架" else "加入书架", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    when (dlTask?.state) {
                                        STATE_RUNNING -> ComicDownloader.pause(dlTask.key)
                                        STATE_PAUSED, STATE_ERROR -> ComicDownloader.resume(dlTask.key)
                                        STATE_DONE -> onRead(full, chapters, 0)
                                        else -> ComicDownloader.start(full, chapters)
                                    }
                                },
                                enabled = chapters.isNotEmpty() && !loading && Offline.isOnline(ctx),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                val online = Offline.isOnline(ctx)
                                val label = when {
                                    !online -> "离线不能缓存"
                                    else -> when (dlTask?.state) {
                                        STATE_RUNNING -> "暂停 ${dlTask.doneChapters}/${dlTask.total}"
                                        STATE_PAUSED -> "继续缓存"
                                        STATE_ERROR -> "重试缓存"
                                        STATE_DONE -> "已缓存完"
                                        else -> "缓存整本"
                                    }
                                }
                                if (dlTask?.state == STATE_RUNNING) {
                                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(label, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            if (full.intro.isNotBlank()) {
                item {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                        Text("简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            full.intro,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("话数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("${chapters.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            if (offlineNote.isNotBlank()) {
                item {
                    Text(
                        offlineNote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
            }
            if (error.isNotBlank()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { tick++ }) { Text("重试") }
                    }
                }
            }
            items(chapters, key = { it.url }) { ch ->
                val cur = ch.index == startIdx && record != null
                val saved = ch.index in cachedSet
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val online = Offline.isOnline(ctx)
                            if (!online && !saved) {
                                android.widget.Toast.makeText(
                                    ctx,
                                    "离线模式：这一话没缓存过，联网后再看",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                onRead(full, chapters, ch.index)
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ch.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (cur) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (saved) {
                        Text(
                            "已存",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    if (cur) TinyTag("看到这")
                }
            }
        }
    }
}

/* ──────────────────────────── 漫画阅读器 ──────────────────────────── */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    comic: Comic,
    chapters: List<ComicChapter>,
    startIndex: Int,
    store: Store,
    onBack: () -> Unit,
) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))) }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var pageHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(store.comicMode()) }
    var zoomable by remember { mutableStateOf(store.comicZoom()) }
    var fromCache by remember { mutableStateOf(false) }
    // 整页缩放（PDF 阅读器那种），不是单张图各缩各的
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var zoomOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { images.size })
    val defaultHeaders = remember(comic.sourceId) { ComicRepo.imageHeaders(comic) }
    val activeHeaders = if (pageHeaders.isNotEmpty()) pageHeaders else defaultHeaders

    // 预取：进话先抓前几张，翻到哪就把后面两张也抓下来，翻页基本不用等
    val ctx = LocalContext.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val screenW = with(androidx.compose.ui.platform.LocalDensity.current) {
        cfg.screenWidthDp.dp.roundToPx()
    }
    val screenH = with(androidx.compose.ui.platform.LocalDensity.current) {
        cfg.screenHeightDp.dp.roundToPx()
    }

    LaunchedEffect(images, activeHeaders) {
        if (images.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            images.take(4).forEach { u -> ComicImages.prefetch(ctx, u, activeHeaders, screenW, screenH) }
        }
        // 下一话的图地址也顺手要回来
        ComicRepo.prefetchPages(comic, chapters.getOrNull(index + 1))
    }

    LaunchedEffect(images.size, mode, activeHeaders) {
        if (images.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }.collect { p ->
            withContext(Dispatchers.IO) {
                for (i in (p + 1)..(p + 3)) {
                    images.getOrNull(i)?.let { ComicImages.prefetch(ctx, it, activeHeaders, screenW, screenH) }
                }
            }
        }
    }

    val chapter = chapters.getOrNull(index)

    // 点屏幕：左边上一页、右边下一页，到头了才切话；中间叫出/隐藏工具栏
    fun tapAt(x: Float, width: Int) {
        if (width <= 0) { chrome = !chrome; return }
        val f = x / width
        if (mode == "page") {
            when {
                f < 0.30f -> when {
                    pagerState.currentPage > 0 ->
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    index > 0 -> index--
                    else -> chrome = !chrome
                }
                f > 0.70f -> when {
                    pagerState.currentPage < images.size - 1 ->
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    index < chapters.size - 1 -> index++
                    else -> chrome = !chrome
                }
                else -> chrome = !chrome
            }
        } else {
            when {
                f < 0.28f -> if (index > 0) index-- else chrome = !chrome
                f > 0.72f -> if (index < chapters.size - 1) index++ else chrome = !chrome
                else -> chrome = !chrome
            }
        }
    }

    LaunchedEffect(index, attempt) {
        val ch = chapters.getOrNull(index) ?: return@LaunchedEffect
        loading = true
        error = ""
        pageHeaders = emptyMap()
        zoomScale = 1f
        zoomOffset = androidx.compose.ui.geometry.Offset.Zero
        // 本地缓存优先：这一话存过就直接读文件，不碰网络
        val local = runCatching { ComicDownloader.localPages(comic, index) }.getOrDefault(emptyList())
        val online = Offline.isOnline(ctx)
        val completeLocal = local.isNotEmpty() && ComicDownloader.isChapterComplete(comic, index)
        if (local.isNotEmpty() && (completeLocal || !online)) {
            images = local.map { "file://" + it.absolutePath }
            fromCache = true
            loading = false
            store.putComicRecord(comic, index, ch.name)
            runCatching { listState.scrollToItem(0) }
            runCatching { pagerState.scrollToPage(0) }
            return@LaunchedEffect
        }
        if (!online) {
            // 断网又没存过：别去撞网络，直接说清楚
            images = emptyList()
            error = "离线模式：这一话没缓存过，联网后再看"
            loading = false
            return@LaunchedEffect
        }
        fromCache = false
        runCatching { withContext(Dispatchers.IO) { ComicRepo.pages(comic, ch) } }
            .onSuccess {
                images = it.images
                val merged = defaultHeaders.toMutableMap()
                if (it.headers.isNotEmpty()) merged.putAll(it.headers)
                if (it.referer.isNotBlank()) merged["Referer"] = it.referer
                pageHeaders = merged
                if (it.images.isNotEmpty()) store.putComicRecord(comic, index, ch.name)
            }
            .onFailure {
                // 网络没拿到但本地有半话的图：先给用户看，别白屏
                if (local.isNotEmpty()) {
                    images = local.map { f -> "file://" + f.absolutePath }
                    fromCache = true
                } else {
                    error = it.message ?: "这一话加载失败"
                }
            }
        loading = false
        runCatching { listState.scrollToItem(0) }
        runCatching { pagerState.scrollToPage(0) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // 点击：左边上一页、右边下一页、中间开关工具栏
            .pointerInput(mode, images.size, chapters.size, zoomable) {
                val onDouble: ((androidx.compose.ui.geometry.Offset) -> Unit)? = if (zoomable) {
                    { _: androidx.compose.ui.geometry.Offset ->
                        if (zoomScale > 1f) {
                            zoomScale = 1f
                            zoomOffset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            zoomScale = 2.2f
                        }
                    }
                } else null
                detectTapGestures(
                    onTap = { o -> tapAt(o.x, size.width) },
                    onDoubleTap = onDouble,
                )
            }
            // 缩放：两指缩放整层；没放大时单指全部放行，翻页器/列表照常滑
            .pointerInput(zoomable) {
                if (!zoomable) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var twoFinger = false
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.size >= 2) twoFinger = true
                        if (twoFinger) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                            if (zoomScale <= 1.001f) {
                                zoomScale = 1f
                                zoomOffset = androidx.compose.ui.geometry.Offset.Zero
                            } else {
                                zoomOffset = clampPan(zoomOffset + pan, zoomScale, size)
                            }
                            event.changes.forEach { if (it.pressed) it.consume() }
                        } else if (zoomScale > 1f) {
                            // 放大后单指拖动 = 在页面里挪着看
                            val dragged = event.changes.firstOrNull { it.pressed }
                            if (dragged != null) {
                                zoomOffset = clampPan(
                                    zoomOffset + (dragged.position - dragged.previousPosition),
                                    zoomScale,
                                    size,
                                )
                                dragged.consume()
                            }
                        }
                    }
                }
            }
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(chapter?.name.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            error.isNotBlank() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { attempt++ }) { Text("重试") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { if (index < chapters.size - 1) index++ }) { Text("下一话") }
                }
            }

            mode == "page" -> {
                // 左右翻页：一屏一页，划屏翻页。缩放作用在整页上（PDF 阅读器那种），
                // 不是每张图各自在自己格子里放大
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().canvasZoom(zoomScale, zoomOffset),
                    beyondViewportPageCount = 1,
                ) { page ->
                    ComicPage(
                        src = images.getOrNull(page).orEmpty(),
                        headers = activeHeaders,
                    )
                }
                // 页码
                if (chrome && images.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 92.dp)
                            .background(Color(0x99000000), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${pagerState.currentPage + 1} / ${images.size}",
                            fontSize = 11.sp,
                            color = Color.White,
                        )
                    }
                }
            }

            else -> LazyColumn(
                state = listState,
                // 整列一起缩放、一起平移，页面之间不留缝，像看 PDF
                modifier = Modifier.fillMaxSize().canvasZoom(zoomScale, zoomOffset),
            ) {
                items(images.size) { i ->
                    ComicPage(
                        src = images[i],
                        headers = activeHeaders,
                        fillWidth = true,
                    )
                }
                items(1) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 96.dp)) {
                        TextButton(
                            onClick = { if (index < chapters.size - 1) index++ },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (index < chapters.size - 1) "下一话" else "已经是最后一话") }
                    }
                }
            }
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        comic.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(chapter?.name.orEmpty())
                            append(" · ")
                            append(index + 1)
                            append("/")
                            append(chapters.size)
                            if (fromCache) append(" · 本地")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { if (index > 0) index-- },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("上一话") }
                OutlinedButton(
                    onClick = { showSettings = true },
                    shape = RoundedCornerShape(10.dp),
                ) { Text("设置", fontSize = 12.sp) }
                Button(
                    onClick = { if (index < chapters.size - 1) index++ },
                    enabled = index < chapters.size - 1,
                    modifier = Modifier.weight(1f),
                ) { Text("下一话") }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("阅读设置", fontSize = 16.sp) },
            text = {
                Column {
                    Text("翻页方式", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == "page",
                            onClick = { mode = "page"; store.setComicMode("page") },
                            label = { Text("左右翻页", fontSize = 12.sp) },
                        )
                        FilterChip(
                            selected = mode == "scroll",
                            onClick = { mode = "scroll"; store.setComicMode("scroll") },
                            label = { Text("上下滚动", fontSize = 12.sp) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("允许放大缩小", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "双指缩放，双击放大 / 复位",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = zoomable,
                            onCheckedChange = { zoomable = it; store.setComicZoom(it) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("好了") }
            },
        )
    }
}

/** 一页漫画。缩放和翻页手势统一在阅读器外层处理，这里只管把图画好 */
@Composable
private fun ComicPage(
    src: String,
    headers: Map<String, String>,
    fillWidth: Boolean = false,
) {
    val ctx = LocalContext.current
    var retryKey by remember(src) { mutableIntStateOf(0) }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(ctx)
            .data(src)
            .apply { headers.forEach { (k, v) -> setHeader(k, v) } }
            .memoryCacheKey(src)
            .diskCacheKey(src)
            .setParameter("retry", retryKey, memoryCacheKey = null)
            .build(),
        contentDescription = null,
        contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Fit,
        modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
        loading = {
            Box(
                modifier = if (fillWidth) Modifier.fillMaxWidth().height(260.dp) else Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        error = {
            Box(
                modifier = if (fillWidth) Modifier.fillMaxWidth().height(200.dp) else Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { retryKey++ }.padding(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "重试",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("加载失败，点击重试", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** 整层缩放 + 平移，作用在翻页器 / 滚动列表上，跟 PDF 阅读器一样 */
private fun Modifier.canvasZoom(
    scale: Float,
    offset: androidx.compose.ui.geometry.Offset,
): Modifier = this.graphicsLayer {
    scaleX = scale
    scaleY = scale
    translationX = offset.x
    translationY = offset.y
}

/** 放大后拖动时别把图拖出屏幕外 */
private fun clampPan(
    o: androidx.compose.ui.geometry.Offset,
    scale: Float,
    size: androidx.compose.ui.unit.IntSize,
): androidx.compose.ui.geometry.Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return androidx.compose.ui.geometry.Offset(
        o.x.coerceIn(-maxX, maxX),
        o.y.coerceIn(-maxY, maxY),
    )
}
