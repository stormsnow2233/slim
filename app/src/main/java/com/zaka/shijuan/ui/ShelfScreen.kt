package com.zaka.shijuan.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaka.shijuan.data.ShelfItem

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    shelf: List<ShelfItem>,
    onOpen: (ShelfItem) -> Unit,
    onRemove: (ShelfItem) -> Unit,
    onBrowse: () -> Unit,
) {
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
            Column(Modifier.weight(1f)) {
                Text("书架", style = MaterialTheme.typography.headlineLarge)
                Text(
                    if (shelf.isEmpty()) "还没有收藏" else "${shelf.size} 本在架上",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onBrowse) {
                Icon(Icons.Rounded.Add, contentDescription = "找书")
            }
        }

        if (shelf.isEmpty()) {
            EmptyState(
                title = "书架空着",
                subtitle = "去搜索页找一本，点「加入书架」就会出现在这里",
                action = {
                    Button(onClick = onBrowse) {
                        Icon(Icons.Rounded.AutoStories, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("去找书")
                    }
                },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shelf, key = { it.book.url + it.book.sourceId }) { item ->
                    Box {
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
                                item.chapterName.ifBlank { item.book.author.ifBlank { "未开始" } },
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
            text = { Text("阅读进度会一起删掉，重新加入不会恢复。") },
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
