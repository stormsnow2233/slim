package com.storm.shijuan.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.BuildConfig
import com.storm.shijuan.data.Net
import com.storm.shijuan.data.ReleaseInfo
import com.storm.shijuan.data.Store
import com.storm.shijuan.data.UpdateManager
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: Store,
    onBack: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenCache: () -> Unit,
    onOpenSources: () -> Unit,
    onShowUpdate: (ReleaseInfo) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(store.readerPrefs()) }
    var autoCheck by remember { mutableStateOf(store.autoCheckUpdate()) }
    var comicMode by remember { mutableStateOf(store.comicMode()) }
    var comicZoom by remember { mutableStateOf(store.comicZoom()) }

    var checking by remember { mutableStateOf(false) }
    var checkStatusText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── 书源管理 ──
            SettingsGroupHeader("书源与内容")

            val enabledCount = remember { store.enabledSources().size }
            SettingsNavigationItem(
                title = "书源管理",
                subtitle = "已启用 $enabledCount 个书源 · 包含轻小说文库、笔趣阁等 18+ 源与自定义源",
                icon = Icons.Rounded.Layers,
                onClick = onOpenSources,
            )

            Spacer(Modifier.height(24.dp))

            // ── 小说阅读偏好 ──
            SettingsGroupHeader("小说阅读偏好")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                Text(
                    "正文字号 ${prefs.fontSize.toInt()} sp",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                CleanSlider(
                    value = prefs.fontSize,
                    onValueChange = {
                        val rounded = it.roundToInt().toFloat()
                        if (rounded != prefs.fontSize) {
                            prefs = prefs.copy(fontSize = rounded)
                            store.saveReaderPrefs(prefs)
                        }
                    },
                    valueRange = 14f..30f,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "正文行距 ${String.format(java.util.Locale.US, "%.2f", prefs.lineHeight)} 倍",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                CleanSlider(
                    value = prefs.lineHeight,
                    onValueChange = {
                        val rounded = ((it * 20).roundToInt() / 20f)
                        if (rounded != prefs.lineHeight) {
                            prefs = prefs.copy(lineHeight = rounded)
                            store.saveReaderPrefs(prefs)
                        }
                    },
                    valueRange = 1.2f..2.4f,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "翻页模式",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (prefs.pageMode == 0) "上下连续平滑滚动" else "左右仿真点击/滑动翻页",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                            .padding(3.dp)
                    ) {
                        val modes = listOf(0 to "上下滚动", 1 to "左右翻页")
                        modes.forEach { (mode, label) ->
                            val isSelected = prefs.pageMode == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable {
                                        if (prefs.pageMode != mode) {
                                            prefs = prefs.copy(pageMode = mode)
                                            store.saveReaderPrefs(prefs)
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 软件更新 ──
            SettingsGroupHeader("版本与更新")

            // 自动检查更新开关
            SettingsSwitchItem(
                title = "自动获取更新",
                subtitle = "App 启动时在后台静默检查 GitHub 最新发布版本并提醒",
                icon = Icons.Rounded.Sync,
                checked = autoCheck,
                onCheckedChange = { checked ->
                    autoCheck = checked
                    store.setAutoCheckUpdate(checked)
                }
            )

            SettingsDivider()

            // 检查更新按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        if (checking) return@clickable
                        checking = true
                        checkStatusText = "正在检查更新…"
                        scope.launch {
                            UpdateManager.checkUpdate().fold(
                                onSuccess = { info ->
                                    checking = false
                                    if (info.hasUpdate) {
                                        checkStatusText = "发现新版本 ${info.tagName}"
                                        onShowUpdate(info)
                                    } else {
                                        checkStatusText = "当前已是最新版本 (v${BuildConfig.VERSION_NAME})"
                                        Toast.makeText(ctx, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onFailure = {
                                    checking = false
                                    checkStatusText = "检查失败：${it.message}"
                                    Toast.makeText(ctx, "检查更新失败：${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            ) {
                Icon(
                    Icons.Rounded.Update,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("检查更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            checking -> "正在连接 GitHub 最新 Release…"
                            checkStatusText.isNotBlank() -> checkStatusText
                            else -> "当前版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 网络与存储 ──
            SettingsGroupHeader("网络与存储")

            SettingsNavigationItem(
                title = "网络代理设置",
                subtitle = if (Net.proxy().usable) "代理已启用 · ${Net.proxy().host}:${Net.proxy().port}" else "直连网络 · 支持配置 HTTP/SOCKS 代理与网络超时",
                icon = Icons.Rounded.Wifi,
                onClick = onOpenNetwork
            )

            SettingsDivider()

            SettingsNavigationItem(
                title = "缓存管理",
                subtitle = "管理已离线的小说正文与漫画本地缓存，一键清理释放存储空间",
                icon = Icons.Rounded.CleaningServices,
                onClick = onOpenCache
            )

            SettingsDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        store.clearHistory()
                        Toast.makeText(ctx, "搜索历史已清空", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 12.dp, horizontal = 4.dp)
            ) {
                Icon(
                    Icons.Rounded.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("清空搜索历史", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text("一键清除小说和漫画搜索框的历史关键词记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 漫画阅读偏好 ──
            SettingsGroupHeader("漫画阅读偏好")

            SettingsSwitchItem(
                title = "上下滚动浏览",
                subtitle = if (comicMode == "scroll") "当前为上下瀑布流滚动，类似条漫" else "当前为左右章内翻页",
                icon = Icons.Rounded.SwapVert,
                checked = comicMode == "scroll",
                onCheckedChange = { isScroll ->
                    val newMode = if (isScroll) "scroll" else "page"
                    comicMode = newMode
                    store.setComicMode(newMode)
                }
            )

            SettingsDivider()

            SettingsSwitchItem(
                title = "手势缩放支持",
                subtitle = "阅读漫画与轻小说插图时允许双指捏合缩放与双击放大",
                icon = Icons.Rounded.ZoomIn,
                checked = comicZoom,
                onCheckedChange = { zoom ->
                    comicZoom = zoom
                    store.setComicZoom(zoom)
                }
            )

            Spacer(Modifier.height(24.dp))

            // ── 关于 ──
            SettingsGroupHeader("关于")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("拾卷 Shijuan", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "纯原生轻小说与漫画阅读器，找书快，排版舒服，无 WebView 闪退卡顿。完全开源，纯净无广告。",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                FilledTonalButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stormsnow2233/slim"))
                        ctx.startActivity(intent)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("查看 GitHub 开源仓库")
                }
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        modifier = Modifier.padding(start = 38.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsNavigationItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

/**
 * 全局统一更新弹窗：启动自动检查与设置中手动检查完全复用同一个弹窗。
 * 完整 Markdown 格式化更新日志，支持上下滑动与富文本展示。
 */
@Composable
fun UpdateDialog(
    info: ReleaseInfo,
    onDismissRequest: () -> Unit,
    onIgnoreVersion: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadError by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            if (!downloading) onDismissRequest()
        },
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "发现新版本 ${info.tagName}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (info.title.isNotBlank() && info.title != info.tagName) {
                        Text(
                            info.title,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "更新内容：",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))

                // 完整 Markdown 格式化更新日志，支持上下滑动与富文本展示
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                ) {
                    val scroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scroll)
                            .padding(14.dp)
                    ) {
                        MarkdownContent(
                            markdown = info.changelog.ifBlank { "本次版本包含常规体验优化与问题修复。" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (downloading) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("安装包下载中…", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Text("${(downloadProgress * 100).toInt()}%", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (downloadError.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        downloadError,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (downloading) {
                // 下载中静止
            } else if (downloadedFile != null) {
                Button(
                    onClick = { UpdateManager.installApk(ctx, downloadedFile!!) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("立即安装")
                }
            } else {
                Button(
                    onClick = {
                        downloading = true
                        downloadError = ""
                        scope.launch {
                            UpdateManager.downloadApk(ctx, info.downloadUrl) { bytes, total ->
                                if (total > 0) downloadProgress = (bytes.toFloat() / total).coerceIn(0f, 1f)
                            }.fold(
                                onSuccess = { file ->
                                    downloading = false
                                    downloadedFile = file
                                    UpdateManager.installApk(ctx, file)
                                },
                                onFailure = {
                                    downloading = false
                                    downloadError = "下载失败：${it.message}"
                                }
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("立即更新")
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                Row {
                    TextButton(
                        onClick = {
                            onIgnoreVersion(info.versionName)
                            onDismissRequest()
                        }
                    ) {
                        Text("不再提示", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismissRequest) {
                        Text("稍后")
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        thumb = {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
                modifier = Modifier.size(20.dp)
            ) {}
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(4.dp),
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null
            )
        }
    )
}
