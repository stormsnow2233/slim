package com.storm.shijuan

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.storm.shijuan.data.*
import com.storm.shijuan.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        setContent {
            ShijuanTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ShijuanApp()
                }
            }
        }
    }
}

/** 侧边栏导航：书架（综合默认首页） / 小说 / 漫画 */
enum class Section(val label: String, val desc: String, val icon: ImageVector) {
    Shelf("书架", "综合书架 · 本地导入 · 畅读", Icons.Rounded.CollectionsBookmark),
    Novel("小说", "多源搜索 · 极速聚合 · 18+ 源", Icons.Rounded.Book),
    Comic("漫画", "动漫屋 · 漫画人 · 极速聚合", Icons.Rounded.PhotoLibrary),
    JM("JM源", "JMcomic 禁漫 · 单源独享", Icons.Rounded.AutoAwesome),
}

sealed class Route {
    data object Main : Route()
    data object Search : Route()
    data object Sources : Route()
    data object Settings : Route()
    data object Cache : Route()
    data object Network : Route()
    /** 自定义书源编辑；id 为 null 表示新建 */
    data class CustomSourceEdit(val id: String? = null) : Route()
    data class Detail(val book: Book, val fromSearch: Boolean = false) : Route()
    data class Reader(val book: Book, val chapters: List<Chapter>, val index: Int, val initialPage: Int = 0, val fromSearch: Boolean = false) : Route()
    data class ComicDetail(val comic: Comic) : Route()
    data class ComicRead(
        val comic: Comic,
        val chapters: List<ComicChapter>,
        val index: Int,
        val initialPage: Int = 0,
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
    remember { BookRepo.init(store); 0 }
    // 恢复已持久化的各站点凭证（包含 Cloudflare 质询通过后的凭据）
    remember {
        val saved = store.allSavedCookies()
        for ((host, cookies) in saved) {
            Net.syncCookies(host, cookies)
        }
        0
    }
    val online by Offline.online.collectAsState()
    var route by remember { mutableStateOf<Route>(Route.Main) }
    val searchState = remember { SearchState() }
    val comicSearchState = remember { ComicSearchState() }
    var shelf by remember { mutableStateOf(store.shelf()) }
    var enabled by remember { mutableStateOf(store.enabledSources()) }
    var records by remember { mutableStateOf(store.records()) }
    var comicShelf by remember { mutableStateOf(store.comicShelf()) }
    var comicRecords by remember { mutableStateOf(store.comicRecords()) }
    var isJmEnabled by remember { mutableStateOf(store.isJmEnabled()) }

    // 离线：书架上只留本地有正文缓存的，最近读的也一样；JM 漫画永不在书架显示
    val shelfShown = remember(online, shelf) {
        if (online) shelf else shelf.filter { store.cachedChaptersOf(it.book) > 0 }
    }
    val recordsShown = remember(online, records) {
        if (online) records else records.filter { store.cachedChaptersOf(it.book) > 0 }
    }
    val comicShelfShown = remember(online, comicShelf) {
        val list = comicShelf.filter { it.sourceId != "jm" }
        if (online) list else list.filter { ComicDownloader.cachedChapters(it).isNotEmpty() }
    }
    val comicRecordsShown = remember(online, comicRecords) {
        val list = comicRecords.filter { it.comic.sourceId != "jm" }
        if (online) list else list.filter { ComicDownloader.cachedChapters(it.comic).isNotEmpty() }
    }

    val refreshShelf = {
        shelf = store.shelf()
        records = store.records()
        comicShelf = store.comicShelf()
        comicRecords = store.comicRecords()
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val uiScope = rememberCoroutineScope()
    var section by remember { mutableStateOf(Section.Shelf) }

    // 状态流驱动：任何书架增删、进度更新、切页路由均触发毫秒级实时刷新
    LaunchedEffect(store.shelfTick, store.comicTick, route, section) {
        refreshShelf()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshShelf()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            isJmEnabled = store.isJmEnabled()
            if (!isJmEnabled && section == Section.JM) {
                section = Section.Shelf
            }
        }
    }

    BackHandler(enabled = route !is Route.Main || section != Section.Shelf) {
        if (route !is Route.Main) {
            val prev = route
            route = when (val r = route) {
                is Route.ComicRead -> Route.ComicDetail(r.comic)
                is Route.Reader -> Route.Detail(r.book, r.fromSearch)
                is Route.Detail -> Route.Main
                is Route.ComicDetail -> Route.Main
                is Route.CustomSourceEdit -> Route.Sources
                else -> Route.Main
            }
            if (prev is Route.Settings) {
                isJmEnabled = store.isJmEnabled()
                if (!isJmEnabled && section == Section.JM) {
                    section = Section.Shelf
                }
            }
        } else if (section != Section.Shelf) {
            section = Section.Shelf
        }
    }
    var activeUpdateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    val openDrawer = { uiScope.launch { drawerState.open() }; Unit }

    // 启动时还原持久化的各站 Cookie（如文库通过凭证、Cloudflare 验证通过凭据）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val saved = store.allSavedCookies()
            saved.forEach { (host, cookies) ->
                Net.syncCookies(host, cookies)
            }
        }
    }

    // 启动自动检查更新：后台静默检测，有新版本且用户未选择“不再提示”时自动弹出
    LaunchedEffect(Unit) {
        if (store.autoCheckUpdate()) {
            kotlinx.coroutines.delay(1000)
            UpdateManager.checkUpdate().onSuccess { info ->
                if (info.hasUpdate && info.versionName != store.ignoredUpdateVersion()) {
                    activeUpdateInfo = info
                }
            }
        }
    }

    if (activeUpdateInfo != null) {
        UpdateDialog(
            info = activeUpdateInfo!!,
            onDismissRequest = { activeUpdateInfo = null },
            onIgnoreVersion = { ver ->
                store.setIgnoredUpdateVersion(ver)
                activeUpdateInfo = null
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 主界面允许从屏幕左边缘轻划拉出侧边栏
        gesturesEnabled = route is Route.Main,
        drawerContent = {
            SectionDrawer(
                current = section,
                showJm = isJmEnabled,
                onPick = { s ->
                    section = s
                    route = Route.Main
                    uiScope.launch { drawerState.close() }
                },
                onOpenCache = {
                    route = Route.Cache
                    uiScope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    route = Route.Settings
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
                        is Route.Main -> when (section) {
                            Section.Shelf -> ShelfScreen(
                                store = store,
                                shelf = shelfShown,
                                records = recordsShown,
                                comicShelf = comicShelfShown,
                                comicRecords = comicRecordsShown,
                                onOpenNovel = { item -> route = Route.Detail(item.book, fromSearch = false) },
                                onRemoveNovel = { item ->
                                    store.removeFromShelf(item.book.url, item.book.sourceId)
                                    refreshShelf()
                                },
                                onOpenNovelRecord = { rec -> route = Route.Detail(rec.book, fromSearch = false) },
                                onRemoveNovelRecord = { book ->
                                    store.removeRecord(book)
                                    refreshShelf()
                                },
                                onBrowseNovel = {
                                    section = Section.Novel
                                },
                                onOpenComic = { c -> route = Route.ComicDetail(c) },
                                onRemoveComic = { c ->
                                    store.removeComicFromShelf(c)
                                    refreshShelf()
                                },
                                onOpenComicRecord = { rec -> route = Route.ComicDetail(rec.comic) },
                                onBrowseComic = {
                                    section = Section.Comic
                                },
                                onOpenDrawer = openDrawer,
                                offline = !online,
                                onImportSuccess = { refreshShelf() },
                            )
                            Section.Novel -> SearchScreen(
                                store = store,
                                enabled = enabled,
                                onOpenBook = { route = Route.Detail(it, fromSearch = true) },
                                onOpenDrawer = openDrawer,
                                onBack = null,
                                searchState = searchState,
                            )
                            Section.Comic -> ComicScreen(
                                store = store,
                                onOpen = { route = Route.ComicDetail(it) },
                                onOpenDrawer = openDrawer,
                                offline = !online,
                                onOpenCache = { route = Route.Cache },
                                state = comicSearchState,
                            )
                            Section.JM -> JmComicScreen(
                                store = store,
                                onOpen = { route = Route.ComicDetail(it) },
                                onOpenDrawer = openDrawer,
                                offline = !online,
                                onOpenCache = { route = Route.Cache },
                            )
                        }

                        is Route.Search -> SearchScreen(
                            store = store,
                            enabled = enabled,
                            onOpenBook = { route = Route.Detail(it, fromSearch = true) },
                            onOpenDrawer = openDrawer,
                            onBack = { route = Route.Main },
                            searchState = searchState,
                        )

                        is Route.Sources -> SourcesScreen(
                            store = store,
                            enabled = enabled,
                            onToggle = { id, on -> enabled = store.setSourceEnabled(id, on) },
                            onOpenDrawer = openDrawer,
                            onBack = { route = Route.Main },
                            onAddSource = { route = Route.CustomSourceEdit(null) },
                            onEditSource = { src -> route = Route.CustomSourceEdit(src.id) },
                        )

                        is Route.Settings -> SettingsScreen(
                            store = store,
                            onBack = {
                                isJmEnabled = store.isJmEnabled()
                                if (!isJmEnabled && section == Section.JM) {
                                    section = Section.Shelf
                                }
                                route = Route.Main
                            },
                            onOpenNetwork = { route = Route.Network },
                            onOpenCache = { route = Route.Cache },
                            onOpenSources = { route = Route.Sources },
                            onShowUpdate = { activeUpdateInfo = it },
                            onJmEnabledChanged = { enabled ->
                                isJmEnabled = enabled
                                if (!enabled && section == Section.JM) {
                                    section = Section.Shelf
                                }
                            },
                        )

                        is Route.Cache -> CacheScreen(
                            store = store,
                            onBack = { route = Route.Main },
                            onOpenComic = { c -> route = Route.ComicDetail(c) },
                        )

                        is Route.Network -> NetworkScreen(
                            store = store,
                            onBack = { route = Route.Main },
                        )

                        is Route.CustomSourceEdit -> CustomSourceEditScreen(
                            initial = r.id?.let { Sources.byId(it) },
                            onBack = { route = Route.Sources },
                            onSaved = {
                                enabled = store.enabledSources()
                                route = Route.Sources
                            },
                            onDeleted = {
                                enabled = store.enabledSources()
                                route = Route.Sources
                            },
                        )

                        is Route.Detail -> DetailScreen(
                            book = r.book,
                            store = store,
                            onBack = { route = Route.Main },
                            onRead = { book, chapters, idx, initPage ->
                                route = Route.Reader(book, chapters, idx, initPage, fromSearch = r.fromSearch)
                            },
                            onShelfChanged = { refreshShelf() },
                        )

                        is Route.Reader -> ReaderScreen(
                            book = r.book,
                            chapters = r.chapters,
                            startIndex = r.index,
                            initialPage = r.initialPage,
                            store = store,
                            onBack = { route = Route.Detail(r.book, fromSearch = r.fromSearch) },
                            onSwitchBook = { other -> route = Route.Detail(other, fromSearch = r.fromSearch) },
                            onProgress = { ci, cn, p, page, total ->
                                store.updateProgress(r.book, ci, cn, p, page, total)
                                refreshShelf()
                            },
                        )

                        is Route.ComicDetail -> ComicDetailScreen(
                            comic = r.comic,
                            store = store,
                            onBack = { route = Route.Main },
                            onRead = { c, chs, idx, initPage -> route = Route.ComicRead(c, chs, idx, initPage) },
                            onShelfChanged = { refreshShelf() },
                            onSearchSimilar = { kw ->
                                comicSearchState.query = kw
                                comicSearchState.fromRank = false
                                comicSearchState.searchToken++
                                comicSearchState.tab = 0
                                section = Section.Comic
                                route = Route.Main
                            },
                        )

                        is Route.ComicRead -> ComicReaderScreen(
                            comic = r.comic,
                            chapters = r.chapters,
                            startIndex = r.index,
                            store = store,
                            onBack = { route = Route.ComicDetail(r.comic) },
                            initialPage = r.initialPage,
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
 * 侧边栏：小说 / 漫画模式切换、缓存、设置。
 */
@Composable
private fun SectionDrawer(
    current: Section,
    showJm: Boolean = false,
    onPick: (Section) -> Unit,
    onOpenCache: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val sections = remember(showJm) {
        if (showJm) Section.entries else Section.entries.filter { it != Section.JM }
    }
    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
        modifier = Modifier.width(276.dp),
    ) {
        Spacer(Modifier.statusBarsPadding())
        Spacer(Modifier.height(16.dp))
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

        sections.forEach { s ->
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
                    "阅读偏好 · 自动更新 · 关于",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(8.dp))
    }
}
