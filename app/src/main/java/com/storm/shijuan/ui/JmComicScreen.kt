package com.storm.shijuan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.Comic
import com.storm.shijuan.data.ComicDownloader
import com.storm.shijuan.data.ComicRepo
import com.storm.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JM源（JMcomic 禁漫天堂）独立搜索与探索界面
 * 完全模仿漫画界面体验，专注单源极速检索与分类浏览
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JmComicScreen(
    store: Store,
    onOpen: (Comic) -> Unit,
    onOpenDrawer: () -> Unit = {},
    offline: Boolean = false,
    onOpenCache: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf("mr") }
    var list by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchToken by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    val records = remember(store.comicTick, offline) {
        val all = store.comicRecords().filter { it.comic.sourceId == "jm" }
        if (offline) all.filter { ComicDownloader.cachedChapters(it.comic).isNotEmpty() } else all
    }

    val categories = remember {
        listOf(
            "最新上架" to "mr",
            "最多观看" to "mv",
            "最多收藏" to "tf",
            "同人" to "doujin",
            "单本" to "single",
            "短篇" to "short",
            "韩漫" to "hanman",
            "美漫" to "meiman",
            "其他类" to "another",
            "3D" to "3D",
            "Cosplay" to "another_cosplay",
            "禁漫汉化组" to "%E7%A6%81%E6%BC%AB%E6%B1%89%E5%8C%96%E7%BB%84",
        )
    }

    // 默认加载或切换分类时触发
    LaunchedEffect(selectedCat, refreshKey) {
        if (offline) return@LaunchedEffect
        if (query.isNotBlank()) return@LaunchedEffect
        loading = true
        error = ""
        val got = withContext(Dispatchers.IO) {
            runCatching {
                ComicRepo.jmSource.exploreCategory(selectedCat)
            }.getOrElse { emptyList() }
        }
        loading = false
        if (got.isNotEmpty()) {
            list = got
        } else if (list.isEmpty()) {
            error = "JMcomic 加载失败，请检查网络或稍后重试"
        }
    }

    // 搜索触发
    LaunchedEffect(searchToken) {
        val kw = query.trim()
        if (searchToken == 0 || kw.isEmpty() || offline) return@LaunchedEffect
        loading = true
        error = ""
        val got = withContext(Dispatchers.IO) {
            runCatching {
                ComicRepo.jmSource.search(kw)
            }.getOrElse { emptyList() }
        }
        loading = false
        if (got.isNotEmpty()) {
            list = got
        } else {
            error = "未搜到「$kw」，换个关键词试试"
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "JM源",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            "JMcomic",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "JMcomic 禁漫 · 单源独享 · 极速检索",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // 搜索框
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜 JMcomic 漫画，回车开始") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (query.isNotBlank()) searchToken++
            }),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotBlank()) {
                        IconButton(onClick = {
                            query = ""
                            error = ""
                            refreshKey++
                        }) {
                            Icon(Icons.Rounded.Delete, "清除", Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = {
                        if (query.isNotBlank()) searchToken++
                    }) {
                        Icon(Icons.Rounded.Search, "搜索", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )

        Spacer(Modifier.height(8.dp))

        // 分类标签横向滑动
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(categories) { (name, key) ->
                val isSelected = query.isBlank() && selectedCat == key
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        query = ""
                        selectedCat = key
                    },
                    label = { Text(name, fontSize = 12.sp) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // 标题与刷新栏
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val titleText = when {
                query.isNotBlank() -> "搜索结果"
                else -> categories.firstOrNull { it.second == selectedCat }?.first ?: "热门推荐"
            }
            Text(
                titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (loading) "加载中…" else "${list.size} 部",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (query.isNotBlank()) searchToken++ else refreshKey++
            }) {
                Icon(Icons.Rounded.Refresh, "刷新", Modifier.size(19.dp))
            }
        }

        // 错误提示
        if (error.isNotBlank() && list.isEmpty() && !loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        if (query.isNotBlank()) searchToken++ else refreshKey++
                    }) {
                        Text("重试")
                    }
                }
            }
        }

        // 加载指示器
        if (loading && list.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            // 漫画网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 继续看（最近阅读的 JM 漫画）
                if (records.isNotEmpty() && query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "继续看",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(records, key = { it.comic.url }) { rec ->
                                    Column(
                                        Modifier.width(78.dp).clickable { onOpen(rec.comic) }
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

                // 漫画卡片列表
                itemsIndexed(list, key = { _, c -> c.url }) { _, c ->
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
                        Text(
                            "JMcomic",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
