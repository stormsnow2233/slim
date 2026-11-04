package com.zaka.shijuan.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaka.shijuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聚合后的书：同一本书可能有多个源都有 */
data class MergedBook(
    val name: String,
    val authors: Set<String>,
    val sources: List<Book>,
) {
    val primary: Book get() = sources.first()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    store: Store,
    enabled: Set<String>,
    onOpenBook: (Book) -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Map<String, SourceResult>>(emptyMap()) }
    var running by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(store.history()) }
    var showStates by remember { mutableStateOf(false) }
    var filterSource by remember { mutableStateOf<String?>(null) }

    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val activeSources = remember(enabled) { Sources.ALL.filter { it.id in enabled } }

    fun doSearch(k: String) {
        val key = k.trim()
        if (key.isEmpty() || running) return
        focus.clearFocus()
        keyword = key
        searched = key
        store.pushHistory(key)
        history = store.history()
        results = emptyMap()
        filterSource = null
        running = true
        scope.launch {
            val acc = HashMap<String, SourceResult>()
            withContext(Dispatchers.IO) {
                BookRepo.searchAll(activeSources, key) { r ->
                    synchronized(acc) { acc[r.sourceId] = r }
                    results = acc.toMap()
                }
            }
            running = false
        }
    }

    // 聚合
    val merged = remember(results, filterSource) {
        val pool = results.values
            .filter { it.ok }
            .filter { filterSource == null || it.sourceId == filterSource }
            .flatMap { it.books }
        pool.groupBy { normName(it.name) }
            .map { (_, list) ->
                MergedBook(
                    name = list.first().name,
                    authors = list.map { it.author }.filter { it.isNotBlank() }.toSet(),
                    sources = list.sortedBy { srcOrder(it.sourceId) },
                )
            }
            .sortedWith(compareByDescending<MergedBook> { it.sources.size }.thenBy { it.name })
    }

    val okCount = results.values.count { it.ok }
    val failCount = results.values.count { !it.ok }
    val totalBooks = results.values.filter { it.ok }.sumOf { it.books.size }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {

        // ── 搜索框 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text("书名或作者", style = MaterialTheme.typography.bodyLarge) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                leadingIcon = {
                    Icon(Icons.Rounded.Search, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { keyword = "" }) {
                            Icon(Icons.Rounded.Close, "清空", Modifier.size(18.dp))
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { doSearch(keyword) }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { doSearch(keyword) },
                enabled = !running && keyword.isNotBlank(),
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Rounded.ArrowForward, "搜索")
                }
            }
        }

        // ── 搜索状态条 ──
        if (searched.isNotBlank() && (results.isNotEmpty() || running)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .clickable { showStates = !showStates },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在搜 ${okCount + failCount}/${activeSources.size} 个源…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        buildString {
                            append("$totalBooks 个结果 · 聚合 ${merged.size} 本")
                            if (failCount > 0) append(" · $failCount 源无响应")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (showStates) "收起" else "源详情",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (showStates) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary,
                )
            }

            AnimatedVisibility(showStates) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp)
                ) {
                    activeSources.forEach { src ->
                        val st = results[src.id]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        when {
                                            st == null -> MaterialTheme.colorScheme.outline
                                            st.ok -> Color_OK
                                            else -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(src.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
                            Text(
                                when {
                                    st == null -> "搜索中…"
                                    st.ok -> "${st.count} 本 · ${st.ms}ms"
                                    else -> st.error
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 源筛选
            if (merged.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = filterSource == null,
                            onClick = { filterSource = null },
                            label = { Text("全部", fontSize = 12.sp) },
                        )
                    }
                    items(results.values.filter { it.ok }.sortedBy { srcOrder(it.sourceId) }) { st ->
                        FilterChip(
                            selected = filterSource == st.sourceId,
                            onClick = { filterSource = if (filterSource == st.sourceId) null else st.sourceId },
                            label = { Text(st.sourceName, fontSize = 12.sp) },
                        )
                    }
                }
            }
        }

        // ── 内容区 ──
        if (searched.isBlank()) {
            // 初始状态：历史 + 推荐词
            Column(Modifier.padding(horizontal = 18.dp)) {
                if (history.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近搜索", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            store.clearHistory(); history = mutableListOf()
                        }) { Text("清空", fontSize = 12.sp) }
                    }
                    FlowRowChips(history.take(10)) { doSearch(it) }
                    Spacer(Modifier.height(18.dp))
                }
                Text("试试这些", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                FlowRowChips(
                    listOf("斗破苍穹", "诡秘之主", "十日终焉", "凡人修仙传", "三体", "大奉打更人", "剑来", "遮天")
                ) { doSearch(it) }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已启用 ${activeSources.size} 个书源", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(activeSources) { s ->
                        TinyTag(s.name, if (s.status == "verified") Color_OK else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } else if (merged.isEmpty() && !running) {
            EmptyState(
                title = "没找到「$searched」",
                subtitle = if (failCount == activeSources.size)
                    "所有源都没响应，检查下网络，或者到「书源」页做一次体检"
                else "换个书名或作者试试，也可以到书源页多开几个源",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(merged, key = { it.name + it.primary.sourceId }) { m ->
                    BookRow(m, onOpenBook)
                }
            }
        }
    }
}

@Composable
private fun BookRow(m: MergedBook, onOpen: (Book) -> Unit) {
    val b = m.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(b) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        BookCover(
            cover = b.cover,
            name = b.name,
            modifier = Modifier.size(width = 68.dp, height = 96.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (m.authors.isNotEmpty()) append(m.authors.joinToString(" / "))
                    if (b.kind.isNotBlank()) { if (isNotEmpty()) append(" · "); append(b.kind) }
                    if (b.latest.isNotBlank()) { if (isNotEmpty()) append(" · "); append(b.latest) }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (b.intro.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    b.intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                m.sources.take(3).forEach { s ->
                    TinyTag(
                        s.sourceName,
                        if (Sources.byId(s.sourceId)?.status == "verified") Color_OK
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                if (m.sources.size > 3) {
                    TinyTag("+${m.sources.size - 3}", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>, onClick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { t ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = { onClick(t) },
            ) {
                Text(
                    t,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val Color_OK = androidx.compose.ui.graphics.Color(0xFF4C7A54)

private fun normName(s: String): String =
    s.replace(Regex("[\\s　]+"), "")
        .replace("（", "(").replace("）", ")")
        .replace(Regex("[《》\"'·]"), "")
        .lowercase()

private fun srcOrder(id: String): Int = Sources.ALL.indexOfFirst { it.id == id }.let { if (it < 0) 99 else it }
