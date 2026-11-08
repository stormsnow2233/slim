package com.storm.shijuan.ui

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import com.storm.shijuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 聚合后的书：同一本书可能有多个源都有 */
data class MergedBook(
    val name: String,
    val authors: Set<String>,
    val sources: List<Book>,
) {
    val primary: Book
        get() {
            val bestCover = sources.firstOrNull { it.cover.isNotBlank() }?.cover ?: sources.first().cover
            val bestAuthor = authors.firstOrNull { it.isNotBlank() && it != "佚名" }
                ?: sources.map { it.author }.firstOrNull { it.isNotBlank() && it != "佚名" }
                ?: sources.first().author
            val bestIntro = sources.maxByOrNull { it.intro.length }?.intro ?: sources.first().intro
            val best = sources.firstOrNull { it.cover.isNotBlank() } ?: sources.first()
            return best.copy(
                author = bestAuthor,
                cover = bestCover,
                intro = bestIntro,
                altSources = sources,
            )
        }
}

/** 搜索界面状态持久容器：页面离开/跳转详情返回时保留关键词与结果 */
class SearchState {
    var keyword by mutableStateOf("")
    var results by mutableStateOf<Map<String, SourceResult>>(emptyMap())
    var running by mutableStateOf(false)
    var searched by mutableStateOf("")
    var showStates by mutableStateOf(false)
    var filterSource by mutableStateOf<String?>(null)
    var searchSeq by mutableIntStateOf(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    store: Store,
    enabled: Set<String>,
    onOpenBook: (Book) -> Unit,
    onOpenDrawer: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    searchState: SearchState = remember { SearchState() },
) {
    var keyword by searchState::keyword
    var results by searchState::results
    var running by searchState::running
    var searched by searchState::searched
    var history by remember { mutableStateOf(store.history()) }
    var showStates by searchState::showStates
    var filterSource by searchState::filterSource
    var searchSeq by searchState::searchSeq
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var verifyTarget by remember { mutableStateOf<Source?>(null) }

    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            searchJob?.cancel()
        }
    }

    val activeSources = remember(enabled, Sources.customVersion) { Sources.all.filter { it.id in enabled } }

    fun doSearch(k: String) {
        val key = k.trim()
        if (key.isEmpty()) return
        focus.clearFocus()
        keyword = key
        searched = key
        store.pushHistory(key)
        history = store.history()
        results = emptyMap()
        filterSource = null
        running = true
        val prevSeq = searchSeq
        BookRepo.cancelSearch("search_$prevSeq")
        searchJob?.cancel()
        val currentSeq = ++searchSeq
        val searchTag = "search_$currentSeq"
        searchJob = scope.launch {
            val acc = HashMap<String, SourceResult>()
            withContext(Dispatchers.IO) {
                BookRepo.searchAll(activeSources, key, tag = searchTag) { r ->
                    if (searchSeq == currentSeq) {
                        val snapshot = synchronized(acc) {
                            acc[r.sourceId] = r
                            acc.toMap()
                        }
                        scope.launch(Dispatchers.Main) {
                            if (searchSeq == currentSeq) {
                                results = snapshot
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (searchSeq == currentSeq) {
                    running = false
                }
            }
        }
    }

    /** 单个源单独重搜：哪个不通点哪个，不用整轮重来 */
    fun retrySource(src: Source) {
        if (searched.isBlank()) return
        running = true
        val currentSeq = searchSeq
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { BookRepo.searchSource(src, searched) }
                    .getOrElse { SourceResult(src.id, src.name, false, error = Net.friendlyError(it)) }
            }
            if (searchSeq == currentSeq) {
                results = results + (src.id to r)
                running = false
            }
        }
    }

    // 聚合
    val merged = remember(results, filterSource, searched) {
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
            .sortedWith(
                compareByDescending<MergedBook> { mb ->
                    if (searched.isNotBlank()) {
                        NovelAliasHelper.matchScore(mb.name, mb.authors.firstOrNull().orEmpty(), searched)
                    } else 0.0
                }.thenByDescending { it.sources.size }
                .thenBy { it.name }
            )
    }

    val okCount = results.values.count { it.ok }
    val failCount = results.values.count { !it.ok }
    val noHitCount = results.values.count { !it.ok && isNoHit(it.error) }
    val errCount = failCount - noHitCount
    val totalBooks = results.values.filter { it.ok }.sumOf { it.books.size }

    if (verifyTarget != null) {
        CloudflareVerifyDialog(
            url = verifyTarget!!.base,
            sourceName = verifyTarget!!.name,
            store = store,
            onDismiss = { verifyTarget = null },
            onVerified = {
                val s = verifyTarget ?: return@CloudflareVerifyDialog
                verifyTarget = null
                retrySource(s)
            }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {

        // ── 搜索框 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
            } else {
                DrawerButton(onClick = onOpenDrawer)
            }
            Spacer(Modifier.width(8.dp))
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
                        IconButton(onClick = {
                            keyword = ""
                            searched = ""
                            results = emptyMap()
                            filterSource = null
                        }) {
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

        // 搜索进度轻量指示（纯净无多余面板）
        if (running) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
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
                    listOf("败犬女主角太多了", "古见同学有社交障碍", "邻家天使", "魔王", "斗破苍穹", "诡秘之主", "十日终焉", "凡人修仙传", "三体", "大奉打更人", "剑来", "遮天")
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
        } else if (merged.isEmpty() && running) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                items(merged, key = { it.name + it.primary.sourceId }) { m ->
                    Box(Modifier.animateItem()) { BookRow(m, onOpenBook) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            // 混合显示所有书源，支持单独点击切换
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                m.sources.forEach { s ->
                    val isVerified = Sources.byId(s.sourceId)?.status == "verified"
                    val tagColor = if (isVerified) Color_OK else MaterialTheme.colorScheme.primary
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = tagColor.copy(alpha = 0.12f),
                        onClick = {
                            val target = s.copy(
                                cover = s.cover.ifBlank { b.cover },
                                author = s.author.ifBlank { b.author },
                                intro = s.intro.ifBlank { b.intro },
                                altSources = listOf(s) + m.sources.filter { it.sourceId != s.sourceId },
                            )
                            onOpen(target)
                        }
                    ) {
                        Text(
                            s.sourceName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = tagColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
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

private fun srcOrder(id: String): Int = Sources.all.indexOfFirst { it.id == id }.let { if (it < 0) 99 else it }

/** 「这个站没这本书」不算故障，别跟连不上混成一个红叉 */
private fun isNoHit(err: String): Boolean =
    err.contains("没搜到") || err.contains("没有这本书")
