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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalDensity
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

sealed class Route {
    data object Tabs : Route()
    data class Detail(val book: Book) : Route()
    data class Reader(val book: Book, val chapters: List<Chapter>, val index: Int) : Route()
}

@Composable
fun ShijuanApp() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var shelf by remember { mutableStateOf(store.shelf()) }
    var enabled by remember { mutableStateOf(store.enabledSources()) }

    val refreshShelf = { shelf = store.shelf() }

    BackHandler(enabled = route !is Route.Tabs) {
        route = Route.Tabs
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(260)) { it / 18 }) togetherWith
                (fadeOut(tween(170)) + slideOutVertically(tween(200)) { -it / 26 })
        },
        label = "route",
    ) { r ->
        when (r) {
            is Route.Tabs -> TabHost(
                store = store,
                shelf = shelf,
                enabled = enabled,
                onOpenBook = { route = Route.Detail(it) },
                onRemoveShelf = { item ->
                    store.removeFromShelf(item.book.url, item.book.sourceId)
                    refreshShelf()
                },
                onToggleSource = { id, on -> enabled = store.setSourceEnabled(id, on) },
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

/**
 * 四个主页面：左右滑动切换（跟手），点底栏也会滑过去。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabHost(
    store: Store,
    shelf: List<ShelfItem>,
    enabled: Set<String>,
    onOpenBook: (Book) -> Unit,
    onRemoveShelf: (ShelfItem) -> Unit,
    onToggleSource: (String, Boolean) -> Unit,
) {
    val pager = rememberPagerState(pageCount = { Tab.entries.size })
    val scope = rememberCoroutineScope()
    val tabs = Tab.entries

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // 底栏宽度：4 个 68dp 项目 + 内边距
        val barWidthDp = 68.dp * tabs.size + 12.dp
        val dragRange = with(density) {
            ((maxWidth - barWidthDp - 24.dp).coerceAtLeast(0.dp) / 2).toPx()
        }

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
                            onOpen = { item -> onOpenBook(item.book) },
                            onRemove = { item -> onRemoveShelf(item) },
                            onBrowse = { scope.launch { pager.animateScrollToPage(1) } },
                        )
                    }

                    Tab.Search -> Box(Modifier.fillMaxSize().padding(bottom = 92.dp)) {
                        SearchScreen(
                            store = store,
                            enabled = enabled,
                            onOpenBook = { onOpenBook(it) },
                        )
                    }

                    Tab.Sources -> Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                        SourcesScreen(
                            enabled = enabled,
                            onToggle = { id, on -> onToggleSource(id, on) },
                        )
                    }

                    Tab.Mine -> Box(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
                        MineScreen(
                            store = store,
                            shelfCount = shelf.size,
                            enabledCount = enabled.size,
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
            maxDragPx = dragRange,
            maxLiftPx = with(density) { 220.dp.toPx() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        )
    }
}
