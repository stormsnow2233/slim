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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.Color
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
        val store = Store(this)
        setContent {
            val style = store.currentThemeStyle
            val mode = store.currentThemeMode
            val customSeedColor = store.customThemeSeedColor
            val dark = when (mode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            val isLiquidGlass = store.isLiquidGlassEnabled
            ShijuanTheme(style = style, dark = dark, customSeedColor = customSeedColor, isLiquidGlass = isLiquidGlass) {
                LiquidGlassAtmosphere(modifier = Modifier.fillMaxSize(), enabled = isLiquidGlass) {
                    ShijuanApp(store)
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
    Catalog("漫画目录", "AniList 热门作品与搜索", Icons.Rounded.AutoAwesome),
}

sealed class Route {
    data object Main : Route()
    data object Search : Route()
    data class Sources(val fromSettings: Boolean = false) : Route()
    data object Settings : Route()
    data class Cache(val fromSettings: Boolean = false) : Route()
    data class Network(val fromSettings: Boolean = true) : Route()
    /** 自定义书源编辑；id 为 null 表示新建 */
    data class CustomSourceEdit(val id: String? = null, val fromSettings: Boolean = false) : Route()
    data class Detail(val book: Book, val fromSearch: Boolean = false) : Route()
    data class Reader(val book: Book, val chapters: List<Chapter>, val index: Int, val initialPage: Int = 0, val fromSearch: Boolean = false) : Route()
    data class ComicDetail(val comic: Comic, val fromCache: Boolean = false) : Route()
    data class ComicRead(
        val comic: Comic,
        val chapters: List<ComicChapter>,
        val index: Int,
        val initialPage: Int = 0,
        val fromCache: Boolean = false,
    ) : Route()
}

private fun routeDepth(r: Route): Int = when (r) {
    is Route.Main -> 0
    is Route.Search -> 1
    is Route.Settings -> 1
    is Route.Detail -> 1
    is Route.ComicDetail -> if (r.fromCache) 3 else 1
    is Route.Cache -> if (r.fromSettings) 2 else 1
    is Route.Network -> if (r.fromSettings) 2 else 1
    is Route.Sources -> if (r.fromSettings) 2 else 1
    is Route.Reader -> 2
    is Route.ComicRead -> if (r.fromCache) 4 else 2
    is Route.CustomSourceEdit -> if (r.fromSettings) 3 else 2
}

@Composable
fun ShijuanApp(store: Store) {
    val ctx = LocalContext.current
    remember { ComicDownloader.init(ctx.applicationContext, store); 0 }
    remember { NovelDownloader.init(ctx.applicationContext, store); 0 }
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
    val shelf = remember(store.shelfTick) { store.shelf() }
    var enabled by remember { mutableStateOf(store.enabledSources()) }
    val records = remember(store.shelfTick) { store.records() }
    val comicShelf = remember(store.comicTick) { store.comicShelf() }
    val comicRecords = remember(store.comicTick) { store.comicRecords() }
    var catalogEnabled by remember { mutableStateOf(store.isComicCatalogEnabled()) }

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
        store.notifyShelfChanged()
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val uiScope = rememberCoroutineScope()
    var section by remember { mutableStateOf(Section.Shelf) }

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
            catalogEnabled = store.isComicCatalogEnabled()
            if (!catalogEnabled && section == Section.Catalog) {
                section = Section.Shelf
            }
        }
    }

    BackHandler(enabled = route !is Route.Main || section != Section.Shelf) {
        if (route !is Route.Main) {
            val prev = route
            route = when (val r = route) {
                is Route.ComicRead -> Route.ComicDetail(r.comic, r.fromCache)
                is Route.Reader -> Route.Detail(r.book, r.fromSearch)
                is Route.Detail -> Route.Main
                is Route.ComicDetail -> if (r.fromCache) Route.Cache(fromSettings = true) else Route.Main
                is Route.CustomSourceEdit -> Route.Sources(fromSettings = r.fromSettings)
                is Route.Sources -> if (r.fromSettings) Route.Settings else Route.Main
                is Route.Network -> if (r.fromSettings) Route.Settings else Route.Main
                is Route.Cache -> if (r.fromSettings) Route.Settings else Route.Main
                is Route.Settings -> Route.Main
                else -> Route.Main
            }
            if (prev is Route.Settings) {
                catalogEnabled = store.isComicCatalogEnabled()
                if (!catalogEnabled && section == Section.Catalog) {
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
                showCatalog = catalogEnabled,
                onPick = { s ->
                    section = s
                    route = Route.Main
                    uiScope.launch { drawerState.close() }
                },
                onOpenCache = {
                    route = Route.Cache()
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
                val isLiquidGlass = LocalLiquidGlassEnabled.current
                AnimatedContent(
                    targetState = route,
                    modifier = Modifier.fillMaxSize().background(if (isLiquidGlass) Color.Transparent else MaterialTheme.colorScheme.background),
                    transitionSpec = {
                        val initialDepth = routeDepth(initialState)
                        val targetDepth = routeDepth(targetState)
                        if (targetDepth > initialDepth) {
                            // 前进（下钻进入二级/三级页面）：新页从右向左推入，旧页向左微移
                            (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth } + fadeIn(tween(260))) togetherWith
                                (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth / 3 } + fadeOut(tween(200)))
                        } else if (targetDepth < initialDepth) {
                            // 后退（返回上一层）：旧页向右滑出，上一层从左边回弹推入
                            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth / 3 } + fadeIn(tween(240))) togetherWith
                                (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth } + fadeOut(tween(200)))
                        } else {
                            // 同级平级页面切换：微弱横向位移 + 平滑淡入淡出
                            (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth / 4 } + fadeIn(tween(240))) togetherWith
                                (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth / 4 } + fadeOut(tween(200)))
                        }
                    },
                    label = "route",
                ) { r ->
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        when (r) {
                        is Route.Main -> AnimatedContent(
                            targetState = section,
                            transitionSpec = {
                                if (targetState.ordinal > initialState.ordinal) {
                                    // 往右侧 Tab 切换（书架 -> 小说 -> 漫画 -> JM）：从右向左推进
                                    (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth } + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth } + fadeOut(tween(180)))
                                } else {
                                    // 往左侧 Tab 切换（JM -> 漫画 -> 小说 -> 书架）：从左向右推进
                                    (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { fullWidth -> -fullWidth } + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { fullWidth -> fullWidth } + fadeOut(tween(180)))
                                }
                            },
                            label = "section",
                        ) { sec ->
                            when (sec) {
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
                                    onOpenCache = { route = Route.Cache() },
                                    state = comicSearchState,
                                )
                                Section.Catalog -> AniListComicScreen(
                                    onOpenDrawer = openDrawer,
                                    offline = !online,
                                )
                            }
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
                            onBack = { route = if (r.fromSettings) Route.Settings else Route.Main },
                            onAddSource = { route = Route.CustomSourceEdit(null, fromSettings = r.fromSettings) },
                            onEditSource = { src -> route = Route.CustomSourceEdit(src.id, fromSettings = r.fromSettings) },
                        )

                        is Route.Settings -> SettingsScreen(
                            store = store,
                            onBack = {
                                catalogEnabled = store.isComicCatalogEnabled()
                                if (!catalogEnabled && section == Section.Catalog) {
                                    section = Section.Shelf
                                }
                                route = Route.Main
                            },
                            onOpenNetwork = { route = Route.Network(fromSettings = true) },
                            onOpenCache = { route = Route.Cache(fromSettings = true) },
                            onOpenSources = { route = Route.Sources(fromSettings = true) },
                            onShowUpdate = { activeUpdateInfo = it },
                            onCatalogEnabledChanged = { enabled ->
                                catalogEnabled = enabled
                                if (!enabled && section == Section.Catalog) {
                                    section = Section.Shelf
                                }
                            },
                        )

                        is Route.Cache -> CacheScreen(
                            store = store,
                            onBack = { route = if (r.fromSettings) Route.Settings else Route.Main },
                            onOpenComic = { c -> route = Route.ComicDetail(c, fromCache = r.fromSettings) },
                        )

                        is Route.Network -> NetworkScreen(
                            store = store,
                            onBack = { route = if (r.fromSettings) Route.Settings else Route.Main },
                        )

                        is Route.CustomSourceEdit -> CustomSourceEditScreen(
                            initial = r.id?.let { Sources.byId(it) },
                            onBack = { route = Route.Sources(fromSettings = r.fromSettings) },
                            onSaved = {
                                enabled = store.enabledSources()
                                route = Route.Sources(fromSettings = r.fromSettings)
                            },
                            onDeleted = {
                                enabled = store.enabledSources()
                                route = Route.Sources(fromSettings = r.fromSettings)
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
                            onBack = { route = if (r.fromCache) Route.Cache(fromSettings = true) else Route.Main },
                            onRead = { c, chs, idx, initPage -> route = Route.ComicRead(c, chs, idx, initPage, fromCache = r.fromCache) },
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
                            onBack = { route = Route.ComicDetail(r.comic, fromCache = r.fromCache) },
                            initialPage = r.initialPage,
                        )
                    }
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
    showCatalog: Boolean = false,
    onPick: (Section) -> Unit,
    onOpenCache: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val isMiuix = LocalAppThemeStyle.current == AppThemeStyle.MiuiX
    val sections = remember(showCatalog) {
        if (showCatalog) Section.entries else Section.entries.filter { it != Section.Catalog }
    }
    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
        modifier = Modifier.width(312.dp),
    ) {
        Spacer(Modifier.statusBarsPadding())
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(start = 28.dp, end = 20.dp)) {
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
                    .padding(horizontal = 14.dp, vertical = 3.dp)
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
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = s.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = s.desc,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (on) {
                    if (isMiuix) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(10.dp))

        // 缓存管理：哪本存了多少、存全没有、不要了怎么删
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 3.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenCache() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = "缓存管理",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "缓存管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "占用 · 体检 · 删除",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))

        // 设置入口
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 3.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenSettings() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "阅读偏好 · 自动更新 · 关于",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(8.dp))
    }
}
