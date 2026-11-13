package com.storm.shijuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.CacheManager
import com.storm.shijuan.data.ComicCacheEntry
import com.storm.shijuan.data.ComicDownloader
import com.storm.shijuan.data.NovelCacheEntry
import com.storm.shijuan.data.STATE_DONE
import com.storm.shijuan.data.STATE_PAUSED
import com.storm.shijuan.data.STATE_RUNNING
import com.storm.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缓存管理：本机到底存了什么、存全没存全、不要了怎么删。
 *
 * 每次进来（和每次点「重新检查」）都是真去读磁盘：数文件、验图片头、比对目录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheScreen(
    store: Store,
    onBack: () -> Unit,
    onOpenComic: (com.storm.shijuan.data.Comic) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var comics by remember { mutableStateOf<List<ComicCacheEntry>>(emptyList()) }
    var novels by remember { mutableStateOf<List<NovelCacheEntry>>(emptyList()) }
    var confirmAll by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        scanning = true
        val r = withContext(Dispatchers.IO) {
            CacheManager.scanComics(store) to CacheManager.scanNovels(store)
        }
        comics = r.first
        novels = r.second
        scanning = false
    }

    val comicBytes = comics.sumOf { it.bytes }
    val novelBytes = novels.sumOf { it.bytes }
    val dlRunning = ComicDownloader.tasks.filter { it.state == STATE_RUNNING }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack, shape = CircleShape) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("缓存管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "漫画 ${comics.size} 本 · 小说 ${novels.size} 本 · 共约 ${fmtBytes(comicBytes + novelBytes)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { tick++ }, enabled = !scanning) {
                Icon(Icons.Rounded.Refresh, "重新检查", Modifier.size(20.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("漫画 ${comics.size}", fontSize = 12.sp) })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("小说 ${novels.size}", fontSize = 12.sp) })
            Spacer(Modifier.weight(1f))
            if (scanning) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { confirmAll = true }) { Text("全部清空", fontSize = 12.sp) }
            }
        }

        if (dlRunning.isNotEmpty()) {
            Text(
                "正在缓存：${dlRunning.joinToString("、") { it.comic.name.take(8) + " " + (it.percent * 100).toInt() + "%" }}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HorizontalDivider(Modifier.padding(top = 4.dp))

        if (tab == 0) {
            if (comics.isEmpty() && !scanning) {
                EmptyState(
                    "没有漫画缓存",
                    "在漫画详情页点「缓存整本」，下完断网也能看。",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(comics, key = { it.key }) { e ->
                        ComicCacheCard(
                            entry = e,
                            onOpen = { onOpenComic(e.comic) },
                            onRepair = {
                                CacheManager.repair(e)
                                tick++
                            },
                            onDelete = {
                                CacheManager.deleteComic(e)
                                tick++
                            },
                            onPause = {
                                ComicDownloader.pause(e.key)
                                tick++
                            },
                            onResume = {
                                ComicDownloader.resume(e.key)
                                tick++
                            },
                        )
                    }
                }
            }
        } else {
            if (novels.isEmpty() && !scanning) {
                EmptyState(
                    "没有小说缓存",
                    "在书里读过的章节会自动存到本机，断网也能接着看。",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(novels, key = { it.book.url }) { e ->
                        NovelCacheCard(
                            entry = e,
                            onDelete = {
                                CacheManager.deleteNovel(store, e)
                                tick++
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("清空全部缓存？") },
            text = {
                Text(
                    "会把本机存的漫画图片和小说正文都删掉，书架和阅读进度保留。" +
                        "删了就只能联网重新看，已下线的书也补不回来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (tab == 0) CacheManager.clearComics() else CacheManager.clearNovels(store)
                        }
                        tick++
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ComicCacheCard(
    entry: ComicCacheEntry,
    onOpen: () -> Unit,
    onRepair: () -> Unit,
    onDelete: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val task = ComicDownloader.tasks.firstOrNull { it.key == entry.key }
    val running = task?.state == STATE_RUNNING
    val isPaused = task?.state == STATE_PAUSED
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    cover = entry.comic.cover,
                    name = entry.comic.name,
                    modifier = Modifier.width(44.dp).aspectRatio(0.74f),
                    corner = 8.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.comic.name.ifBlank { "本地缓存" },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${entry.cachedCount}/${entry.total} 话 · ${entry.pages} 张 · ${fmtBytes(entry.bytes)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TinyTag(
                            entry.summary(),
                            color = if (entry.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.tertiary,
                        )
                        if (entry.comic.sourceName.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                entry.comic.sourceName,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, "删除缓存", Modifier.size(18.dp))
                }
            }

            if (task != null && (running || isPaused)) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.percent },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (running) "下载中 ${task.doneChapters}/${task.total} 话 · ${task.currentChapter.take(14)}"
                    else "已暂停 ${task.doneChapters}/${task.total} 话 · 点击继续下载",
                    fontSize = 10.sp,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) { Text("去看", fontSize = 12.sp) }
                if (running) {
                    OutlinedButton(
                        onClick = onPause,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("暂停", fontSize = 12.sp)
                    }
                } else if (isPaused) {
                    Button(
                        onClick = onResume,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("继续下载", fontSize = 12.sp)
                    }
                } else if (!entry.ok && entry.chapters.isNotEmpty()) {
                    TextButton(onClick = onRepair) {
                        Text(if (task?.state == STATE_DONE) "补齐缺的" else "重新检查补齐", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelCacheCard(entry: NovelCacheEntry, onDelete: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.cached}/${entry.total} 章 · 约 ${fmtBytes(entry.bytes)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TinyTag(
                        entry.summary(),
                        color = if (entry.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.tertiary,
                    )
                    if (entry.book.sourceName.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            entry.book.sourceName,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "删除缓存", Modifier.size(18.dp))
            }
        }
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1fG".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1fM".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0fK".format(b / 1024.0)
    else -> "${b}B"
}
