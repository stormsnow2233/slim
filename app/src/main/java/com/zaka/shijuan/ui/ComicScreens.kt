package com.zaka.shijuan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
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
import coil.request.ImageRequest
import com.zaka.shijuan.data.Comic
import com.zaka.shijuan.data.ComicChapter
import com.zaka.shijuan.data.ComicHit
import com.zaka.shijuan.data.ComicDownloader
import com.zaka.shijuan.data.ComicRepo
import com.zaka.shijuan.data.DownloadTask
import com.zaka.shijuan.data.STATE_DONE
import com.zaka.shijuan.data.STATE_ERROR
import com.zaka.shijuan.data.STATE_PAUSED
import com.zaka.shijuan.data.STATE_RUNNING
import com.zaka.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/* ──────────────────────────── 漫画首页 ──────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicScreen(
    store: Store,
    onOpen: (Comic) -> Unit,
    onOpenDrawer: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var fromRank by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    val records = remember(tick, store.comicTick) { store.comicRecords() }

    // 顶部三个入口：探索 / 书架 / 缓存
    var tab by remember { mutableStateOf(0) }
    var shelfTick by remember { mutableStateOf(0) }
    val shelfList = remember(tab, shelfTick, store.comicTick) { store.comicShelf() }
    val dlTasks = ComicDownloader.tasks
    // 每个源各自拿回一批，选「全部」时合并去重，选单个源时只看它
    var hits by remember { mutableStateOf<List<ComicHit>>(emptyList()) }
    var picked by remember { mutableStateOf("all") }
    // 封面补全的触发器：列表先出来，封面随后一批批填上
    var coverToken by remember { mutableStateOf(0) }

    LaunchedEffect(tick, query.isNotBlank()) {
        if (query.isNotBlank()) return@LaunchedEffect
        loading = true
        error = ""
        hits = withContext(Dispatchers.IO) {
            ComicRepo.sources.map { src ->
                val t0 = System.currentTimeMillis()
                runCatching { src.home() }.fold(
                    onSuccess = { ComicHit(src.id, src.name, it, true, System.currentTimeMillis() - t0) },
                    onFailure = {
                        ComicHit(
                            src.id, src.name, emptyList(), false,
                            System.currentTimeMillis() - t0, it.message ?: "失败",
                        )
                    },
                )
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

    // 封面按源一个个补，补好一个就地刷新，不影响已经显示出来的列表
    LaunchedEffect(coverToken) {
        if (coverToken == 0) return@LaunchedEffect
        val snapshot = hits
        for (h in snapshot) {
            if (!h.ok || h.list.none { it.cover.isBlank() }) continue
            val src = ComicRepo.byIdOrNull(h.sourceId) ?: continue
            val filled = withContext(Dispatchers.IO) { src.fillCovers(h.list) }
            hits = hits.map { if (it.sourceId == h.sourceId) it.copy(list = filled) else it }
        }
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
                    ComicRepo.sources.joinToString(" · ") { it.name },
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
            listOf("探索", "书架", "缓存").forEachIndexed { i, label ->
                val selected = tab == i
                FilterChip(
                    selected = selected,
                    onClick = { tab = i; shelfTick++ },
                    label = {
                        Text(
                            if (i == 2 && dlTasks.isNotEmpty()) "$label ${dlTasks.size}" else label,
                            fontSize = 12.sp,
                        )
                    },
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
        } else if (tab == 1) {
            ComicShelfGrid(
                items = shelfList,
                store = store,
                onOpen = onOpen,
                onChanged = { shelfTick++ },
            )
        } else {
            DownloadPanel(
                tasks = dlTasks,
                onOpenComic = onOpen,
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
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("漫画书架还空着", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "在详情页点「加入书架」，看过的也会自动进这里",
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
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
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
    var tick by remember { mutableStateOf(0) }
    var shelfTick by remember { mutableStateOf(0) }

    LaunchedEffect(comic.url, tick) {
        loading = true
        error = ""
        runCatching {
            withContext(Dispatchers.IO) {
                val d = ComicRepo.detail(comic)
                val c = ComicRepo.chapters(comic)
                d to c
            }
        }.onSuccess { (d, c) ->
            full = d
            chapters = c
            if (c.isEmpty()) error = "没解析到话数，可能站点结构变了"
        }.onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    val record = remember(comic.url, chapters.size, tick) { store.comicRecordOf(comic) }
    val startIdx = (record?.chapterIndex ?: 0).coerceIn(0, (chapters.size - 1).coerceAtLeast(0))

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
                                enabled = chapters.isNotEmpty() && !loading,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                            ) {
                                val label = when (dlTask?.state) {
                                    STATE_RUNNING -> "暂停 ${dlTask.doneChapters}/${dlTask.total}"
                                    STATE_PAUSED -> "继续缓存"
                                    STATE_ERROR -> "重试缓存"
                                    STATE_DONE -> "已缓存完"
                                    else -> "缓存整本"
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onRead(full, chapters, ch.index) }
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
                    )
                    Spacer(Modifier.weight(1f))
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
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(store.comicMode()) }
    var zoomable by remember { mutableStateOf(store.comicZoom()) }
    var fromCache by remember { mutableStateOf(false) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { images.size })
    val headers = remember(comic.sourceId) { ComicRepo.imageHeaders(comic) }

    val chapter = chapters.getOrNull(index)

    LaunchedEffect(index, attempt) {
        val ch = chapters.getOrNull(index) ?: return@LaunchedEffect
        loading = true
        error = ""
        // 本地缓存优先：这一话整话存过就直接读文件，不走网络
        val local = runCatching { ComicDownloader.localPages(comic, index) }.getOrDefault(emptyList())
        if (local.size >= 2) {
            images = local.map { "file://" + it.absolutePath }
            fromCache = true
            loading = false
            store.putComicRecord(comic, index, ch.name)
            runCatching { listState.scrollToItem(0) }
            return@LaunchedEffect
        }
        fromCache = false
        runCatching { withContext(Dispatchers.IO) { ComicRepo.pages(comic, ch) } }
            .onSuccess {
                images = it.images
                if (it.images.isNotEmpty()) store.putComicRecord(comic, index, ch.name)
            }
            .onFailure { error = it.message ?: "这一话加载失败" }
        loading = false
        runCatching { listState.scrollToItem(0) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
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
                // 左右翻页：一屏一页，划屏翻页
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    ComicPageImage(
                        src = images.getOrNull(page).orEmpty(),
                        headers = headers,
                        zoomable = zoomable,
                        onTap = { chrome = !chrome },
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
                modifier = Modifier.fillMaxSize(),
            ) {
                items(images.size) { i ->
                    ComicPageImage(
                        src = images[i],
                        headers = headers,
                        zoomable = zoomable,
                        fillWidth = true,
                        onTap = { chrome = !chrome },
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

/** 一页漫画。zoomable 打开时可以双指缩放、双击放大，放大后能拖动看图 */
@Composable
private fun ComicPageImage(
    src: String,
    headers: Map<String, String>,
    zoomable: Boolean,
    fillWidth: Boolean = false,
    onTap: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var scale by remember(src) { mutableFloatStateOf(1f) }
    var offset by remember(src) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val base = if (fillWidth) {
        Modifier.fillMaxWidth().wrapContentHeight().heightIn(min = 160.dp)
    } else {
        Modifier.fillMaxSize()
    }

    Box(
        base
            .clipToBounds()
            .background(Color(0xFF101114))
            .pointerInput(zoomable, src) {
                if (!zoomable) {
                    detectTapGestures { onTap() }
                    return@pointerInput
                }
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 4f)
                    scale = next
                    offset = if (next > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                }
            }
            .pointerInput(zoomable, src) {
                if (!zoomable) return@pointerInput
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = androidx.compose.ui.geometry.Offset.Zero
                        } else {
                            scale = 2.2f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(src)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = if (fillWidth) ContentScale.FillWidth else ContentScale.Fit,
            modifier = if (fillWidth) {
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            },
        )
    }
}
