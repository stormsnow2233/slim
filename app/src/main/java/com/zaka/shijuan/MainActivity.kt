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
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var shelf by remember { mutableStateOf(store.shelf()) }
    var enabled by remember { mutableStateOf(store.enabledSources()) }
    var records by remember { mutableStateOf(store.records()) }

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
    val openDrawer = { uiScope.launch { drawerState.open() }; Unit }

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
            )
        },
    ) {

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
                )
            } else {
                TabHost(
                    store = store,
                    shelf = shelf,
                    records = records,
                    enabled = enabled,
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
                onProgress = { ci, cn, p, total ->
                    store.updateProgress(r.book, ci, cn, p, total)
                    refreshShelf()
                },
            )
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
    }
}

/**
 * 四个主页面：左右滑动切换（跟手），点底栏也会滑过去。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabHost(
    store: Store,
    shelf: List<ShelfItem>,
    records: List<ReadingRecord>,
    enabled: Set<String>,
    onOpenDrawer: () -> Unit,
    onOpenBook: (Book) -> Unit,
    onRemoveShelf: (ShelfItem) -> Unit,
    onOpenRecord: (ReadingRecord) -> Unit,
    onRemoveRecord: (Book) -> Unit,
    onOpenComic: (Comic) -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { Tab.entries.size })
    val scope = rememberCoroutineScope()
    val tabs = Tab.entries

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            pageSpacing = 0.dp,
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (tabs[page]) {
                    Tab.Shelf -> Box(Modifier.fillMaxSize().padding(bottom = 92.dp)) {
                        ShelfScreen(
                            shelf = shelf,
                            records = records,
                            onOpen = { item -> onOpenBook(item.book) },
                            onRemove = { item -> onRemoveShelf(item) },
                            onOpenRecord = onOpenRecord,
                            onRemoveRecord = onRemoveRecord,
                            onBrowse = { scope.launch { pager.animateScrollToPage(1) } },
                            onOpenDrawer = onOpenDrawer,
                        )
                    }

                    Tab.Search -> Box(Modifier.fillMaxSize().padding(bottom = 92.dp)) {
                        SearchScreen(
                            store = store,
                            enabled = enabled,
                            onOpenBook = { onOpenBook(it) },
                            onOpenDrawer = onOpenDrawer,
                        )
                    }

                    Tab.Sources -> Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                        SourcesScreen(
                            enabled = enabled,
                            onToggle = { id, on -> onToggleSource(id, on) },
                            onOpenDrawer = onOpenDrawer,
                        )
                    }

                    Tab.Mine -> Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                        MineScreen(
                            store = store,
                            shelfCount = shelf.size,
                            enabledCount = enabled.size,
                            onOpenDrawer = onOpenDrawer,
                        )
                    }
                }
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
            pageFraction = (pager.currentPage + pager.currentPageOffsetFraction),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .padding(bottom = 4.dp),
        )
    }
}
