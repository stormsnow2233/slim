package com.zaka.shijuan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.zaka.shijuan.data.ComicRepo
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
    // 每个源各自拿回一批，选「全部」时合并去重，选单个源时只看它
    var hits by remember { mutableStateOf<List<ComicHit>>(emptyList()) }
    var picked by remember { mutableStateOf("all") }

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
                    if (h.ok) "${h.sourceName} ${h.list.size}本/${h.ms}ms" else "${h.sourceName} 连不上"
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
    }
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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val headers = remember(comic.sourceId) { ComicRepo.imageHeaders(comic) }
    val ctx = LocalContext.current

    val chapter = chapters.getOrNull(index)

    LaunchedEffect(index, attempt) {
        val ch = chapters.getOrNull(index) ?: return@LaunchedEffect
        loading = true
        error = ""
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

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { chrome = !chrome },
            ) {
                items(images.size) { i ->
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(images[i])
                            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                            .crossfade(true)
                            .build(),
                        contentDescription = "第 ${i + 1} 页",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .heightIn(min = 160.dp)
                            .background(Color(0xFF101114)),
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
                    Text(comic.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${chapter?.name.orEmpty()} · ${index + 1}/${chapters.size}",
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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { if (index > 0) index-- },
                    enabled = index > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("上一话") }
                Button(
                    onClick = { if (index < chapters.size - 1) index++ },
                    enabled = index < chapters.size - 1,
                    modifier = Modifier.weight(1f),
                ) { Text("下一话") }
            }
        }
    }
}
