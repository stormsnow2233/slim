package com.zaka.shijuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zaka.shijuan.data.*
import com.zaka.shijuan.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShijuanTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ShijuanApp()
                }
            }
        }
    }
}

enum class Tab(val label: String, val on: ImageVector, val off: ImageVector) {
    Shelf("书架", Icons.Rounded.Book, Icons.Outlined.Book),
    Search("搜索", Icons.Rounded.Search, Icons.Outlined.Search),
    Sources("书源", Icons.Rounded.Layers, Icons.Outlined.Layers),
    Mine("我的", Icons.Rounded.Person, Icons.Outlined.Person),
}

/** 侧边栏里的两件事：小说 / 漫画 */
enum class Section(val label: String, val desc: String, val icon: ImageVector) {
    Novel("小说", "多源搜索 · 书架 · 书源", Icons.Rounded.Book),
    Comic("漫画", "动漫屋 · 漫画人 · 拷贝漫画", Icons.Rounded.PhotoLibrary),
}

sealed class Route {
    data object Tabs : Route()
    data object Cache : Route()
    data object Network : Route()
    /** 自定义书源编辑；id 为 null 表示新建 */
    data class CustomSourceEdit(val id: String? = null) : Route()
    data class Detail(val book: Book) : Route()
    data class Reader(val book: Book, val chapters: List<Chapter>, val index: Int) : Route()
    data class ComicDetail(val comic: Comic) : Route()
    data class ComicRead(
        val comic: Comic,
        val chapters: List<ComicChapter>,
        val index: Int,
    ) : Route()
}

@Composable
fun ShijuanApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    remember { ComicDownloader.init(ctx.applicationContext, store); 0 }
    remember { Offline.init(ctx.applicationContext) }
    // 自定义书源：启动时从本地读出来注册，跟内置源一起参与搜索
    remember { CustomSources.init(ctx.applicationContext, store); 0 }
    val online by Offline.online.collectAsState()
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var shelf by remember { mutableStateOf(store.shelf()) }
    var enabled by remember { mutableStateOf(store.enabledSources()) }
    var records by remember { mutableStateOf(store.records()) }

    // 离线：书架上只留本地有正文缓存的，最近读的也一样
    val shelfShown = remember(online, shelf) {
        if (online) shelf else shelf.filter { store.cachedChaptersOf(it.book) > 0 }
    }
    val recordsShown = remember(online, records) {
        if (online) records else records.filter { store.cachedChaptersOf(it.book) > 0 }
    }

    val refreshShelf = {
        shelf = store.shelf()
        records = store.records()
    }

    BackHandler(enabled = route !is Route.Tabs) {
        route = when (val r = route) {
            is Route.ComicRead -> Route.ComicDetail(r.comic)
            is Route.Reader -> Route.Detail(r.book)
            else -> Route.Tabs
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val uiScope = rememberCoroutineScope()
    var section by remember { mutableStateOf(Section.Novel) }
    var showSettings by remember { mutableStateOf(false) }
    // 页签状态提到这一层：进详情、加书源回来时不会跳回第一页
    val pager = rememberPagerState(pageCount = { Tab.entries.size })
    val openDrawer = { uiScope.launch { drawerState.open() }; Unit }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onOpenNetwork = { route = Route.Network },
            onOpenCache = { route = Route.Cache },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 只有主界面允许边缘滑动拉出，进了详情/阅读器就别抢手势
        gesturesEnabled = route is Route.Tabs,
        drawerContent = {
            SectionDrawer(
                current = section,
                onPick = { s ->
                    section = s
                    route = Route.Tabs
                    uiScope.launch { drawerState.close() }
                },
                onOpenCache = {
                    route = Route.Cache
                    uiScope.launch { drawerState.close() }
                },
                onOpenNetwork = {
                    route = Route.Network
                    uiScope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    showSettings = true
                    uiScope.launch { drawerState.close() }
                },
            )
        },
    ) {

    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (!online) Modifier.consumeWindowInsets(WindowInsets.statusBars)
                else Modifier
            )
    ) {
        if (!online) {
            OfflineBar(onRetry = { Offline.refresh(ctx.applicationContext) })
        }

    Box(Modifier.weight(1f)) {
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(260)) { it / 18 }) togetherWith
                (fadeOut(tween(170)) + slideOutVertically(tween(200)) { -it / 26 })
        },
        label = "route",
    ) { r ->
        when (r) {
            is Route.Tabs -> if (section == Section.Comic) {
                ComicScreen(
                    store = store,
                    onOpen = { route = Route.ComicDetail(it) },
                    onOpenDrawer = openDrawer,
                    offline = !online,
                    onOpenCache = { route = Route.Cache },
                )
            } else {
                TabHost(
                    pager = pager,
                    store = store,
                    shelf = shelfShown,
                    records = recordsShown,
                    enabled = enabled,
                    online = online,
                    onOpenDrawer = openDrawer,
                    onOpenBook = { route = Route.Detail(it) },
                    onRemoveShelf = { item ->
                        store.removeFromShelf(item.book.url, item.book.sourceId)
                        refreshShelf()
                    },
                    onOpenRecord = { rec -> route = Route.Detail(rec.book) },
                    onRemoveRecord = { book ->
                        store.removeRecord(book)
                        refreshShelf()
                    },
                    onOpenComic = { route = Route.ComicDetail(it) },
                    onToggleSource = { id, on -> enabled = store.setSourceEnabled(id, on) },
                    onAddSource = { route = Route.CustomSourceEdit(null) },
                    onEditSource = { src -> route = Route.CustomSourceEdit(src.id) },
                )
            }

            is Route.ComicDetail -> ComicDetailScreen(
                comic = r.comic,
                store = store,
                onBack = { route = Route.Tabs },
                onRead = { c, chs, idx -> route = Route.ComicRead(c, chs, idx) },
            )

            is Route.ComicRead -> ComicReaderScreen(
                comic = r.comic,
                chapters = r.chapters,
                startIndex = r.index,
                store = store,
                onBack = { route = Route.ComicDetail(r.comic) },
            )

            is Route.Detail -> DetailScreen(
                book = r.book,
                store = store,
                onBack = { route = Route.Tabs },
                onRead = { book, chapters, idx ->
                    route = Route.Reader(book, chapters, idx)
                },
                onShelfChanged = { refreshShelf() },
            )

            is Route.Reader -> ReaderScreen(
                book = r.book,
                chapters = r.chapters,
                startIndex = r.index,
                store = store,
                onBack = { route = Route.Detail(r.book) },
                onSwitchBook = { other -> route = Route.Detail(other) },
                onProgress = { ci, cn, p, total ->
                    store.updateProgress(r.book, ci, cn, p, total)
                    refreshShelf()
                },
            )

            is Route.Cache -> CacheScreen(
                store = store,
                onBack = { route = Route.Tabs },
                onOpenComic = { c -> route = Route.ComicDetail(c) },
            )

            is Route.Network -> NetworkScreen(
                store = store,
                onBack = { route = Route.Tabs },
            )

            is Route.CustomSourceEdit -> CustomSourceEditScreen(
                initial = r.id?.let { Sources.byId(it) },
                onBack = { route = Route.Tabs },
                onSaved = {
                    enabled = store.enabledSources()
                    route = Route.Tabs
                    // 加完/改完停在书源页，能立刻看到刚加的源
                    uiScope.launch { pager.animateScrollToPage(Tab.Sources.ordinal) }
                },
                onDeleted = {
                    enabled = store.enabledSources()
                    route = Route.Tabs
                    uiScope.launch { pager.animateScrollToPage(Tab.Sources.ordinal) }
                },
            )
        }
    }
    }
    }
    }
}

/** 断网时的常驻提示条：说清楚现在是离线、能看到什么，并给一个手动重测 */
@Composable
private fun OfflineBar(onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "📴 离线模式 · 只显示本机存过的内容",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("重新检测", fontSize = 12.sp)
            }
        }
    }
}

/**
 * 侧边栏：就两件事，小说和漫画。
 */
@Composable
private fun SectionDrawer(
    current: Section,
    onPick: (Section) -> Unit,
    onOpenCache: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
        modifier = Modifier.width(268.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(
                "拾卷",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "看小说，也看漫画",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))

        Section.entries.forEach { s ->
            val on = s == current
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onPick(s) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = s.icon,
                    contentDescription = s.label,
                    tint = if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        s.desc,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (on) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(10.dp))

        // 缓存管理：哪本存了多少、存全没有、不要了怎么删
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenCache() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = "缓存管理",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("缓存管理", style = MaterialTheme.typography.titleMedium)
                Text(
                    "占用 · 体检 · 删除",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 网络：直连还是走代理，外加一个连通性体检
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenNetwork() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Wifi,
                contentDescription = "网络",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("网络", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (Net.proxy().usable) "代理已开 · ${Net.proxy().host}" else "直连 · 可设代理",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(Modifier.padding(horizontal = 22.dp))
        Spacer(Modifier.height(8.dp))

        // 设置入口
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenSettings() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "检查更新 · 关于",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenCache: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var releaseInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateMsg by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<java.io.File?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 检查更新
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (checking || downloading) return@clickable
                        checking = true
                        updateMsg = ""
                        scope.launch {
                            UpdateManager.checkUpdate().fold(
                                onSuccess = { info ->
                                    checking = false
                                    releaseInfo = info
                                    if (!info.hasUpdate) {
                                        updateMsg = "已是最新版本 (v${BuildConfig.VERSION_NAME})"
                                    }
                                },
                                onFailure = {
                                    checking = false
                                    updateMsg = "检查失败：${it.message}"
                                }
                            )
                        }
                    }.padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Update, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("检查更新", style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    checking -> "正在连接 GitHub…"
                                    updateMsg.isNotBlank() -> updateMsg
                                    releaseInfo?.hasUpdate == true -> "发现新版本 ${releaseInfo?.tagName}"
                                    else -> "当前版本 v${BuildConfig.VERSION_NAME}"
                                },
                                fontSize = 11.sp,
                                color = if (releaseInfo?.hasUpdate == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                // 若发现新版本，展开 Release Notes 与下载安装按钮
                releaseInfo?.takeIf { it.hasUpdate }?.let { rel ->
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("新版本 ${rel.tagName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (rel.changelog.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    rel.changelog,
                                    fontSize = 12.sp,
                                    maxLines = 6,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (downloading) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("下载中 ${(downloadProgress * 100).toInt()}%", fontSize = 10.sp)
                            } else if (downloadedFile != null) {
                                Button(
                                    onClick = { UpdateManager.installApk(ctx, downloadedFile!!) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("立即安装")
                                }
                            } else {
                                Button(
                                    onClick = {
                                        downloading = true
                                        scope.launch {
                                            UpdateManager.downloadApk(ctx, rel.downloadUrl) { bytes, total ->
                                                if (total > 0) downloadProgress = (bytes.toFloat() / total).coerceIn(0f, 1f)
                                            }.fold(
                                                onSuccess = { file ->
                                                    downloading = false
                                                    downloadedFile = file
                                                    UpdateManager.installApk(ctx, file)
                                                },
                                                onFailure = {
                                                    downloading = false
                                                    updateMsg = "下载失败：${it.message}"
                                                }
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("下载并更新")
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 网络代理快捷入口
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().clickable {
                        onDismiss()
                        onOpenNetwork()
                    }.padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("网络代理配置", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (Net.proxy().usable) "代理已开 · ${Net.proxy().host}" else "直连 · 可配置 HTTP/SOCKS 代理",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 缓存清理快捷入口
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().clickable {
                        onDismiss()
                        onOpenCache()
                    }.padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("缓存管理与清理", style = MaterialTheme.typography.titleSmall)
                            Text("小说与漫画离线内容占用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "关于：拾卷 (Slim) 开源阅读器\n项目地址：github.com/stormsnow2233/slim",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 四个主页面：左右滑动切换（跟手），点底栏也会滑过去。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabHost(
    pager: androidx.compose.foundation.pager.PagerState,
    store: Store,
    shelf: List<ShelfItem>,
    records: List<ReadingRecord>,
    enabled: Set<String>,
    online: Boolean = true,
    onOpenDrawer: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onRemoveShelf: (ShelfItem) -> Unit,
    onOpenRecord: (ReadingRecord) -> Unit,
    onRemoveRecord: (Book) -> Unit,
    onOpenComic: (Comic) -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
    onAddSource: () -> Unit = {},
    onEditSource: (Source) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val tabs = Tab.entries

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = 0.dp,
        ) { page ->
            when (tabs[page]) {
                Tab.Shelf -> ShelfScreen(
                    shelf = shelf,
                    records = records,
                    onOpen = { item -> onOpenBook(item.book) },
                    onRemove = { item -> onRemoveShelf(item) },
                    onOpenRecord = onOpenRecord,
                    onRemoveRecord = onRemoveRecord,
                    onBrowse = { scope.launch { pager.animateScrollToPage(1) } },
                    onOpenDrawer = onOpenDrawer,
                    offline = !online,
                )

                Tab.Search -> SearchScreen(
                    store = store,
                    enabled = enabled,
                    onOpenBook = { onOpenBook(it) },
                    onOpenDrawer = onOpenDrawer,
                )

                Tab.Sources -> SourcesScreen(
                    enabled = enabled,
                    onToggle = { id, on -> onToggleSource(id, on) },
                    onOpenDrawer = onOpenDrawer,
                    onAddSource = onAddSource,
                    onEditSource = onEditSource,
                )

                Tab.Mine -> MineScreen(
                    store = store,
                    shelfCount = shelf.size,
                    enabledCount = enabled.size,
                    onOpenDrawer = onOpenDrawer,
                )
            }
        }

        // 液态玻璃底栏
        LiquidGlassBar(
            labels = tabs.map { it.label },
            iconsOn = tabs.map { it.on },
            iconsOff = tabs.map { it.off },
            selected = pager.currentPage,
            onSelect = { i ->
                scope.launch {
                    pager.animateScrollToPage(i)
                }
            },
            pageFraction = { pager.currentPage + pager.currentPageOffsetFraction },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = 4.dp),
        )
    }
}
