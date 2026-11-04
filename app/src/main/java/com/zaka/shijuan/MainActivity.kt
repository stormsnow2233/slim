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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zaka.shijuan.data.*
import com.zaka.shijuan.ui.*

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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { Store(ctx) }
    var tab by remember { mutableStateOf(Tab.Shelf) }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var shelf by remember { mutableStateOf(store.shelf()) }
    var enabled by remember { mutableStateOf(store.enabledSources()) }

    val refreshShelf = { shelf = store.shelf() }

    BackHandler(enabled = route !is Route.Tabs) {
        route = Route.Tabs
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        label = "route",
    ) { r ->
        when (r) {
            is Route.Tabs -> Scaffold(
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Icon(
                                        if (tab == t) t.on else t.off,
                                        contentDescription = t.label,
                                        modifier = Modifier.size(23.dp),
                                    )
                                },
                                label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                ),
                            )
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (tab) {
                        Tab.Shelf -> ShelfScreen(
                            shelf = shelf,
                            onOpen = { item -> route = Route.Detail(item.book) },
                            onRemove = { item ->
                                store.removeFromShelf(item.book.url, item.book.sourceId)
                                refreshShelf()
                            },
                            onBrowse = { tab = Tab.Search },
                        )
                        Tab.Search -> SearchScreen(
                            store = store,
                            enabled = enabled,
                            onOpenBook = { route = Route.Detail(it) },
                        )
                        Tab.Sources -> SourcesScreen(
                            enabled = enabled,
                            onToggle = { id, on ->
                                enabled = store.setSourceEnabled(id, on)
                            },
                        )
                        Tab.Mine -> MineScreen(
                            store = store,
                            shelfCount = shelf.size,
                            enabledCount = enabled.size,
                        )
                    }
                }
            }

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
