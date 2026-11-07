package com.storm.shijuan.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.Book
import com.storm.shijuan.data.ReadingRecord
import com.storm.shijuan.data.ShelfItem

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    shelf: List<ShelfItem>,
    records: List<ReadingRecord>,
    onOpen: (ShelfItem) -> Unit,
    onRemove: (ShelfItem) -> Unit,
    onOpenRecord: (ReadingRecord) -> Unit,
    onRemoveRecord: (Book) -> Unit,
    onBrowse: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    offline: Boolean = false,
) {
    // 不在书架上的阅读记忆单独列出来
    val offShelf = remember(shelf, records) {
        val shelfKeys = shelf.map { it.book.url + it.book.sourceId }.toSet()
        records.filter { (it.book.url + it.book.sourceId) !in shelfKeys }
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ShelfItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 顶部标题
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
                Text("书架", style = MaterialTheme.typography.headlineLarge)
                Text(
                    when {
                        offline && shelf.isEmpty() -> "离线模式 · 没有可离线读的书"
                        offline -> "离线模式 · ${shelf.size} 本可离线读"
                        shelf.isEmpty() -> "还没有收藏"
                        else -> "${shelf.size} 本在架上"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!offline) {
                FilledTonalIconButton(onClick = onBrowse) {
                    Icon(Icons.Rounded.Search, contentDescription = "找书")
                }
            }
        }

        if (offline) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当前没有网络，只显示本地缓存过的书",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (shelf.isEmpty() && offShelf.isEmpty()) {
            EmptyState(
                title = if (offline) "这里还没有能离线看的书" else "书架空着",
                subtitle = if (offline) "联网后打开一本书读几章，正文就会存下来，断网时也能接着看"
                else "去搜索页找一本，点「加入书架」就会出现在这里",
                action = if (offline) null else ({
                    Button(onClick = onBrowse) {
                        Icon(Icons.Rounded.AutoStories, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("去找书")
                    }
                }),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                if (offShelf.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent_header") {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "最近在读",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "不在书架也记得进度",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(end = 4.dp),
                            ) {
                                items(offShelf, key = { it.book.url + it.book.sourceId }) { rec ->
                                    Column(
                                        modifier = Modifier
                                            .width(86.dp)
                                            .combinedClickable(
                                                onClick = { onOpenRecord(rec) },
                                                onLongClick = { onRemoveRecord(rec.book) },
                                            )
                                    ) {
                                        BookCover(
                                            cover = rec.book.cover,
                                            name = rec.book.name,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                                            corner = 9.dp,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            rec.book.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            rec.chapterName.ifBlank { "第 ${rec.chapterIndex + 1} 章" } +
                                                if (rec.pageIndex > 0) " (第${rec.pageIndex + 1}页)" else "",
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
                items(shelf, key = { it.book.url + it.book.sourceId }) { item ->
                    Box(Modifier.animateItem()) {
                        Column(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { onOpen(item) },
                                    onLongClick = { menuFor = item.book.url },
                                )
                        ) {
                            Box {
                                BookCover(
                                    cover = item.book.cover,
                                    name = item.book.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.7f),
                                    corner = 10.dp,
                                )
                                if (item.totalChapters > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                                RoundedCornerShape(4.dp),
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            "${item.chapterIndex + 1}/${item.totalChapters}",
                                            fontSize = 9.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(
                                item.book.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.chapterName.ifBlank { item.book.author.ifBlank { "未开始" } } +
                                    if (item.pageIndex > 0) " (第${item.pageIndex + 1}页)" else "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.totalChapters > 0) {
                                Spacer(Modifier.height(5.dp))
                                ThinProgress(
                                    (item.chapterIndex + 1f) / item.totalChapters,
                                    Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = menuFor == item.book.url,
                            onDismissRequest = { menuFor = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("从书架移除") },
                                onClick = {
                                    menuFor = null
                                    confirmRemove = item
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("查看详情") },
                                onClick = {
                                    menuFor = null
                                    onOpen(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移除《${item.book.name}》？") },
            text = { Text("书架里会移除，但阅读进度仍然记着，之后从搜索或详情进来还能接着看。") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(item)
                    confirmRemove = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            },
        )
    }
}
