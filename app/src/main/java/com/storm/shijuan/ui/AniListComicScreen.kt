package com.storm.shijuan.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.AniListComicCatalog
import com.storm.shijuan.data.Comic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniListComicScreen(
    onOpenDrawer: () -> Unit = {},
    offline: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Comic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(refreshKey, offline) {
        if (offline) {
            list = emptyList()
            error = "离线模式：漫画目录需要联网加载"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = ""
        val keyword = query.trim()
        try {
            list = withContext(Dispatchers.IO) {
                if (keyword.isEmpty()) AniListComicCatalog.recommendations()
                else AniListComicCatalog.search(keyword)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "AniList 请求失败"
            list = emptyList()
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerButton(onClick = onOpenDrawer)
            Spacer(Modifier.width(10.dp))
            Text(
                "漫画推荐",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { refreshKey++ }, enabled = !loading && !offline) {
                Icon(Icons.Rounded.Refresh, "刷新目录")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("搜索漫画名，回车开始") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { refreshKey++ }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
        )
        Text(
            "AniList 作品目录 · 点击作品可打开条目。此处不提供章节阅读。",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (error.isNotBlank() && list.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { refreshKey++ }, enabled = !offline) {
                    Text("重试")
                }
            }
        } else if (loading && list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有找到作品", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.url }) { comic ->
                    Column(
                        Modifier.clickable {
                            val page = Uri.parse(comic.url)
                            if (page.scheme !in setOf("http", "https")) {
                                Toast.makeText(context, "AniList 条目链接无效", Toast.LENGTH_SHORT).show()
                            } else {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, page))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "没有可打开网页的应用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) {
                        BookCover(
                            cover = comic.cover,
                            name = comic.name,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.74f),
                            corner = 10.dp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            comic.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (comic.intro.isNotBlank()) {
                            Text(
                                comic.intro,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
