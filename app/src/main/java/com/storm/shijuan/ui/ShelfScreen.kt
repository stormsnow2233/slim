package com.storm.shijuan.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.Book
import com.storm.shijuan.data.Comic
import com.storm.shijuan.data.ComicDownloader
import com.storm.shijuan.data.ComicRecord
import com.storm.shijuan.data.DetectionResult
import com.storm.shijuan.data.ImportType
import com.storm.shijuan.data.LocalImporter
import com.storm.shijuan.data.ReadingRecord
import com.storm.shijuan.data.ShelfItem
import com.storm.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 综合书架（默认首页）：
 *  - 顶部小说书架
 *  - 底部漫画书架
 *  - 右下角浮动导入按钮（支持 EPUB、TXT、PDF）
 *  - 智能类型识别与选择弹窗
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    store: Store,
    shelf: List<ShelfItem>,
    records: List<ReadingRecord>,
    comicShelf: List<Comic>,
    comicRecords: List<ComicRecord>,
    onOpenNovel: (ShelfItem) -> Unit,
    onRemoveNovel: (ShelfItem) -> Unit,
    onOpenNovelRecord: (ReadingRecord) -> Unit,
    onRemoveNovelRecord: (Book) -> Unit,
    onBrowseNovel: () -> Unit,
    onOpenComic: (Comic) -> Unit,
    onRemoveComic: (Comic) -> Unit,
    onOpenComicRecord: (ComicRecord) -> Unit,
    onBrowseComic: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    offline: Boolean = false,
    onImportSuccess: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDetection by remember { mutableStateOf<DetectionResult?>(null) }
    var importingTitle by remember { mutableStateOf<String?>(null) }

    fun startImport(uri: Uri, fileName: String, type: ImportType) {
        importingTitle = fileName
        scope.launch(Dispatchers.IO) {
            val result = if (type == ImportType.COMIC) {
                LocalImporter.importComic(context, uri, fileName, store)
            } else {
                LocalImporter.importNovel(context, uri, fileName, store)
            }
            withContext(Dispatchers.Main) {
                importingTitle = null
                result.onSuccess {
                    val label = if (type == ImportType.COMIC) "漫画书架" else "小说书架"
                    Toast.makeText(context, "《$fileName》已成功导入并加入 $label！", Toast.LENGTH_SHORT).show()
                    onImportSuccess()
                }.onFailure { err ->
                    Toast.makeText(context, "导入失败: ${err.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val det = LocalImporter.detectType(context, uri)
                withContext(Dispatchers.Main) {
                    if (det.type == ImportType.UNSURE) {
                        pendingUri = uri
                        pendingDetection = det
                    } else {
                        startImport(uri, det.fileName, det.type)
                    }
                }
            }
        }
    }

    // 小说：不在书架上的记忆
    val offShelfNovels = remember(shelf, records) {
        val keys = shelf.map { it.book.url + it.book.sourceId }.toSet()
        records.filter { (it.book.url + it.book.sourceId) !in keys }
    }

    // 漫画：不在书架上的记忆
    val offShelfComics = remember(comicShelf, comicRecords) {
        val keys = comicShelf.map { it.url + it.sourceId }.toSet()
        comicRecords.filter { (it.comic.url + it.comic.sourceId) !in keys }
    }

    var menuForNovel by remember { mutableStateOf<ShelfItem?>(null) }
    var menuForComic by remember { mutableStateOf<Comic?>(null) }
    var confirmRemoveNovel by remember { mutableStateOf<ShelfItem?>(null) }
    var confirmRemoveComic by remember { mutableStateOf<Comic?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // 顶部总标题与抽屉按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawerButton(onClick = onOpenDrawer)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("书架", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (offline) "离线模式 · 本地书架"
                        else "${shelf.size} 本小说 · ${comicShelf.size} 部漫画",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                /* ════════════════ 上半部：小说书架 ════════════════ */
                item(key = "novel_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "小说书架",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "${shelf.size} 本",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onBrowseNovel,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("找小说", fontSize = 12.sp)
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(14.dp))
                        }
                    }
                }

                // 小说最近在读
                if (offShelfNovels.isNotEmpty()) {
                    item(key = "novel_recent") {
                        Column(Modifier.padding(bottom = 10.dp)) {
                            Text(
                                "最近在读",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(offShelfNovels, key = { it.book.url + it.book.sourceId }) { rec ->
                                    Column(
                                        modifier = Modifier
                                            .width(88.dp)
                                            .combinedClickable(
                                                onClick = { onOpenNovelRecord(rec) },
                                                onLongClick = { onRemoveNovelRecord(rec.book) },
                                            )
                                    ) {
                                        BookCover(
                                            cover = rec.book.cover,
                                            name = rec.book.name,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                                            corner = 8.dp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            rec.book.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            rec.chapterName.ifBlank { "第 ${rec.chapterIndex + 1} 章" },
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 小说书架书籍
                if (shelf.isEmpty()) {
                    item(key = "novel_empty") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "小说书架暂无藏书",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点右上角「找小说」在线搜索，或从右下角「导入」本地 TXT/EPUB/PDF",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                } else {
                    // 分行展示小说卡片（每行 3 本）
                    val novelChunks = shelf.chunked(3)
                    items(novelChunks.size, key = { "novel_row_$it" }) { rowIdx ->
                        val chunk = novelChunks[rowIdx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (item in chunk) {
                                Box(modifier = Modifier.weight(1f)) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onOpenNovel(item) },
                                                onLongClick = { menuForNovel = item },
                                            )
                                    ) {
                                        Box {
                                            BookCover(
                                                cover = item.book.cover,
                                                name = item.book.name,
                                                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                                                corner = 9.dp,
                                            )
                                            if (item.totalChapters > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(4.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                                            RoundedCornerShape(4.dp),
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        "${item.chapterIndex + 1}/${item.totalChapters}",
                                                        fontSize = 9.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            item.book.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            item.chapterName.ifBlank { item.book.author.ifBlank { "未开始" } },
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            // 补齐空白卡位
                            repeat(3 - chunk.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                /* ════════════════ 中间分割线 ════════════════ */
                item(key = "divider") {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }

                /* ════════════════ 下半部：漫画书架 ════════════════ */
                item(key = "comic_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "漫画书架",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                "${comicShelf.size} 部",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = onBrowseComic,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("看漫画", fontSize = 12.sp)
                            Spacer(Modifier.width(2.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(14.dp))
                        }
                    }
                }

                // 漫画最近在看
                if (offShelfComics.isNotEmpty()) {
                    item(key = "comic_recent") {
                        Column(Modifier.padding(bottom = 10.dp)) {
                            Text(
                                "最近在看",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(offShelfComics, key = { it.comic.url + it.comic.sourceId }) { rec ->
                                    Column(
                                        modifier = Modifier
                                            .width(88.dp)
                                            .clickable { onOpenComicRecord(rec) }
                                    ) {
                                        BookCover(
                                            cover = rec.comic.cover,
                                            name = rec.comic.name,
                                            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                                            corner = 8.dp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            rec.comic.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            rec.chapterName.ifBlank { "第 ${rec.chapterIndex + 1} 话" },
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 漫画书架书籍
                if (comicShelf.isEmpty()) {
                    item(key = "comic_empty") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "漫画书架暂无藏书",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "点右上角「看漫画」在线浏览，或从右下角「导入」本地漫画 (EPUB/PDF)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                )
                            }
                        }
                    }
                } else {
                    // 分行展示漫画卡片（每行 3 部）
                    val comicChunks = comicShelf.chunked(3)
                    items(comicChunks.size, key = { "comic_row_$it" }) { rowIdx ->
                        val chunk = comicChunks[rowIdx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            for (c in chunk) {
                                Box(modifier = Modifier.weight(1f)) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onOpenComic(c) },
                                                onLongClick = { menuForComic = c },
                                            )
                                    ) {
                                        Box {
                                            BookCover(
                                                cover = c.cover,
                                                name = c.name,
                                                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                                                corner = 9.dp,
                                            )
                                            val cached = ComicDownloader.cachedChapters(c).size
                                            if (cached > 0) {
                                                Box(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(4.dp)
                                                        .background(
                                                            Color(0xCC000000),
                                                            RoundedCornerShape(4.dp),
                                                        )
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text("已存 $cached", fontSize = 9.sp, color = Color.White)
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            c.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            c.sourceName,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            repeat(3 - chunk.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // 底部留白，避免右下角悬浮导入按钮遮挡内容
                item(key = "bottom_space") {
                    Spacer(Modifier.height(84.dp))
                }
            }
        }

        /* ════════════════ 右下角：导入悬浮按钮 ════════════════ */
        ExtendedFloatingActionButton(
            onClick = {
                pickerLauncher.launch(
                    arrayOf(
                        "application/epub+zip",
                        "text/plain",
                        "application/pdf",
                        "application/octet-stream",
                        "*/*",
                    )
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = { Icon(Icons.Rounded.Add, contentDescription = "导入") },
            text = { Text("导入书籍", fontWeight = FontWeight.SemiBold) },
        )

        /* ════════════════ 类型未确认提示弹窗 ════════════════ */
        if (pendingDetection != null && pendingUri != null) {
            val det = pendingDetection!!
            val uri = pendingUri!!
            AlertDialog(
                onDismissRequest = {
                    pendingDetection = null
                    pendingUri = null
                },
                title = { Text("选择书籍类型", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "检测到本地文件：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(det.fileName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(det.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Text("请选择将其加入哪个书架：", style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val u = uri
                                val fn = det.fileName
                                pendingDetection = null
                                pendingUri = null
                                startImport(u, fn, ImportType.COMIC)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导入为漫画")
                        }
                        FilledTonalButton(
                            onClick = {
                                val u = uri
                                val fn = det.fileName
                                pendingDetection = null
                                pendingUri = null
                                startImport(u, fn, ImportType.NOVEL)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.MenuBook, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("导入为小说")
                        }
                        TextButton(
                            onClick = {
                                pendingDetection = null
                                pendingUri = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("取消")
                        }
                    }
                },
                dismissButton = null,
            )
        }

        /* ════════════════ 导入中进度弹窗 ════════════════ */
        if (importingTitle != null) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在导入书籍", fontWeight = FontWeight.Bold) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("正在解析《${importingTitle}》...")
                            Text(
                                "提取章节与图文数据中，请稍候",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        }

        /* ════════════════ 小说长按操作菜单 ════════════════ */
        menuForNovel?.let { item ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuForNovel = null },
            ) {
                DropdownMenuItem(
                    text = { Text("查看详情") },
                    onClick = {
                        menuForNovel = null
                        onOpenNovel(item)
                    },
                )
                DropdownMenuItem(
                    text = { Text("从书架移除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuForNovel = null
                        confirmRemoveNovel = item
                    },
                )
            }
        }

        /* ════════════════ 漫画长按操作菜单 ════════════════ */
        menuForComic?.let { comic ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuForComic = null },
            ) {
                DropdownMenuItem(
                    text = { Text("查看详情") },
                    onClick = {
                        menuForComic = null
                        onOpenComic(comic)
                    },
                )
                DropdownMenuItem(
                    text = { Text("从书架移除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuForComic = null
                        confirmRemoveComic = comic
                    },
                )
            }
        }

        /* ════════════════ 移除确认对话框 ════════════════ */
        confirmRemoveNovel?.let { item ->
            AlertDialog(
                onDismissRequest = { confirmRemoveNovel = null },
                title = { Text("从书架移除《${item.book.name}》？") },
                text = { Text("从书架移除后，本地文件与阅读记录仍然保留。") },
                confirmButton = {
                    TextButton(onClick = {
                        onRemoveNovel(item)
                        confirmRemoveNovel = null
                    }) { Text("移除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemoveNovel = null }) { Text("取消") }
                },
            )
        }

        confirmRemoveComic?.let { comic ->
            AlertDialog(
                onDismissRequest = { confirmRemoveComic = null },
                title = { Text("从书架移除《${comic.name}》？") },
                text = { Text("从书架移除后，本地下载与阅读记录仍然保留。") },
                confirmButton = {
                    TextButton(onClick = {
                        onRemoveComic(comic)
                        confirmRemoveComic = null
                    }) { Text("移除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemoveComic = null }) { Text("取消") }
                },
            )
        }
    }
}
