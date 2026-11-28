package com.storm.shijuan.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.BuildConfig
import com.storm.shijuan.data.Net
import com.storm.shijuan.data.ReleaseInfo
import com.storm.shijuan.data.Store
import com.storm.shijuan.data.UpdateManager
import kotlinx.coroutines.delay
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
    jmEnabled: Boolean,
    onJmEnabledChanged: (Boolean) -> Unit,
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
    var jmClickCount by remember { mutableIntStateOf(0) }
    var jmLastClickTime by remember { mutableLongStateOf(0L) }
    var jmUnlockMessage by remember { mutableStateOf<String?>(null) }

    val isMiuix = LocalAppThemeStyle.current == AppThemeStyle.MiuiX
    val isLiquidGlass = LocalLiquidGlassEnabled.current

    LiquidGlassAtmosphere(
        modifier = Modifier.fillMaxSize(),
        enabled = isLiquidGlass,
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                jmUnlockMessage?.let { message ->
                    LaunchedEffect(message) {
                        delay(2_000L)
                        if (jmUnlockMessage == message) jmUnlockMessage = null
                    }
                    Text(
                        text = message,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                            .background(MaterialTheme.colorScheme.inverseSurface, CircleShape)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            topBar = {
                if (isMiuix) {
                    top.yukonga.miuix.kmp.basic.SmallTopAppBar(
                        title = "设置",
                        color = Color.Transparent,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                            }
                        },
                    )
                } else TopAppBar(
                    title = {
                        Text(
                            "设置",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
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
            // ── 外观与主题 ──
            MiuixSectionHeader("外观与主题")
            ThemeSettingsCard(
                currentStyle = store.currentThemeStyle,
                onStyleChange = { store.setAppThemeStyle(it) },
                currentMode = store.currentThemeMode,
                onModeChange = { store.setAppThemeMode(it) },
                customSeedColor = store.customThemeSeedColor,
                onCustomSeedColorChange = { store.saveCustomThemeSeedColor(it) },
                isLiquidGlass = store.isLiquidGlassEnabled,
                onLiquidGlassChange = { store.saveLiquidGlassEnabled(it) }
            )

            // ── 书源管理 ──
            MiuixSectionHeader("书源与内容")
            val enabledCount = remember { store.enabledSources().size }
            MiuixCardGroup {
                MiuixPreferenceItem(
                    title = "书源管理",
                    subtitle = "已启用 $enabledCount 个书源 · 包含轻小说文库、漫画柜、笔趣阁等 20+ 源与自定义源",
                    icon = Icons.Rounded.Layers,
                    onClick = onOpenSources,
                )
                if (jmEnabled) {
                    MiuixDivider(hasIcon = true)
                    MiuixSwitchPreference(
                        title = "JM源",
                        subtitle = "关闭后隐藏侧边栏入口和此开关",
                        icon = Icons.Rounded.AutoAwesome,
                        checked = jmEnabled,
                        onCheckedChange = { enabled ->
                            jmUnlockMessage = null
                            onJmEnabledChanged(enabled)
                        },
                    )
                }
            }

            // ── 小说阅读偏好 ──
            MiuixSectionHeader("小说阅读偏好")
            MiuixCardGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正文字号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${prefs.fontSize.toInt()} sp",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    MiuixSlider(
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正文行距",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${String.format(java.util.Locale.US, "%.2f", prefs.lineHeight)} 倍",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    MiuixSlider(
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
                }

                MiuixDivider(hasIcon = false)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                    MiuixSegmentedPicker(
                        items = listOf(0 to "上下滚动", 1 to "左右翻页"),
                        selectedItem = prefs.pageMode,
                        onSelect = { mode ->
                            if (prefs.pageMode != mode) {
                                prefs = prefs.copy(pageMode = mode)
                                store.saveReaderPrefs(prefs)
                            }
                        }
                    )
                }
            }

            // ── 漫画阅读偏好 ──
            MiuixSectionHeader("漫画阅读偏好")
            MiuixCardGroup {
                MiuixSwitchPreference(
                    title = "上下滚动浏览",
                    subtitle = if (comicMode == "scroll") "当前为上下瀑布流连续滚动" else "当前为左右章内翻页",
                    icon = Icons.Rounded.SwapVert,
                    checked = comicMode == "scroll",
                    onCheckedChange = { isScroll ->
                        val newMode = if (isScroll) "scroll" else "page"
                        comicMode = newMode
                        store.setComicMode(newMode)
                    }
                )
                MiuixDivider(hasIcon = true)
                MiuixSwitchPreference(
                    title = "手势缩放支持",
                    subtitle = "阅读漫画与轻小说插图时允许双指捏合缩放与双击放大",
                    icon = Icons.Rounded.ZoomIn,
                    checked = comicZoom,
                    onCheckedChange = { zoom ->
                        comicZoom = zoom
                        store.setComicZoom(zoom)
                    }
                )
            }

            // ── 网络与存储 ──
            MiuixSectionHeader("网络与存储")
            MiuixCardGroup {
                MiuixPreferenceItem(
                    title = "网络设置",
                    subtitle = if (Net.proxy().usable && Net.proxy().enabled) "已开启自定义代理 · ${Net.proxy().host}:${Net.proxy().port}" else "直连模式 · 支持配置自定义代理与站点反代",
                    icon = Icons.Rounded.Wifi,
                    onClick = onOpenNetwork
                )
                MiuixDivider(hasIcon = true)
                MiuixPreferenceItem(
                    title = "缓存管理",
                    subtitle = "管理已离线的小说正文与漫画本地缓存，一键清理释放存储空间",
                    icon = Icons.Rounded.CleaningServices,
                    onClick = onOpenCache
                )
                MiuixDivider(hasIcon = true)
                MiuixPreferenceItem(
                    title = "清空搜索历史",
                    subtitle = "一键清除小说和漫画搜索框的历史关键词记录",
                    icon = Icons.Rounded.History,
                    onClick = {
                        store.clearHistory()
                        Toast.makeText(ctx, "搜索历史已清空", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // ── 软件更新 ──
            MiuixSectionHeader("版本与更新")
            MiuixCardGroup {
                MiuixSwitchPreference(
                    title = "自动获取更新",
                    subtitle = "App 启动时在后台静默检查 GitHub 最新发布版本并提醒",
                    icon = Icons.Rounded.Sync,
                    checked = autoCheck,
                    onCheckedChange = { checked ->
                        autoCheck = checked
                        store.setAutoCheckUpdate(checked)
                    }
                )
                MiuixDivider(hasIcon = true)
                MiuixPreferenceItem(
                    title = "检查更新",
                    subtitle = when {
                        checking -> "正在连接 GitHub 最新 Release…"
                        checkStatusText.isNotBlank() -> checkStatusText
                        else -> "当前版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
                    },
                    icon = Icons.Rounded.Update,
                    onClick = {
                        if (!jmEnabled) {
                            val now = System.currentTimeMillis()
                            jmClickCount = if (now - jmLastClickTime <= 1800L) jmClickCount + 1 else 1
                            jmLastClickTime = now
                            if (jmClickCount >= 10) {
                                jmClickCount = 0
                                onJmEnabledChanged(true)
                                Toast.makeText(ctx, "JM 源已解锁", Toast.LENGTH_SHORT).show()
                                return@MiuixPreferenceItem
                            }
                        }
                        if (checking) return@MiuixPreferenceItem
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
                    },
                    endAction = if (checking) {
                        { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                    } else null
                )
            }

            // ── 关于 ──
            MiuixSectionHeader("关于")
            MiuixCardGroup {
                MiuixPreferenceItem(
                    title = "拾卷 Shijuan",
                    subtitle = "纯原生轻小说与漫画阅读器，找书快，排版舒服，完全开源无广告。",
                    icon = Icons.Rounded.AutoStories,
                    onClick = {
                        if (jmEnabled) return@MiuixPreferenceItem
                        val now = System.currentTimeMillis()
                        jmClickCount = if (now - jmLastClickTime < 2500L) jmClickCount + 1 else 1
                        jmLastClickTime = now
                        jmUnlockMessage = when {
                            jmClickCount >= 10 -> {
                                jmClickCount = 0
                                onJmEnabledChanged(true)
                                "JM入口已开启"
                            }
                            jmClickCount >= 5 -> "再点 ${10 - jmClickCount} 次开启神秘入口"
                            else -> null
                        }
                    },
                )
                MiuixDivider(hasIcon = true)
                MiuixPreferenceItem(
                    title = "查看 GitHub 开源仓库",
                    subtitle = "获取最新源码、提交 Issue 反馈与参与共建",
                    icon = Icons.Rounded.Code,
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stormsnow2233/slim"))
                        ctx.startActivity(intent)
                    }
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
}

@Composable
private fun ThemeSettingsCard(
    currentStyle: Int,
    onStyleChange: (Int) -> Unit,
    currentMode: Int,
    onModeChange: (Int) -> Unit,
    customSeedColor: Int?,
    onCustomSeedColorChange: (Int?) -> Unit,
    isLiquidGlass: Boolean = true,
    onLiquidGlassChange: (Boolean) -> Unit = {},
) {
    val isClassic = currentStyle == AppThemeStyle.Classic.id
    val effectiveSeed = customSeedColor?.let { Color(it) }
        ?: if (isClassic) Color(0xFF9C4A2F) else Color(0xFF3482FF)
    val selectedPreset = ThemePresets.list.firstOrNull { it.color.toArgb() == effectiveSeed.toArgb() }
    val effectiveHex = remember(effectiveSeed) { ThemeColorGenerator.colorToHex(effectiveSeed) }

    var showCustomPicker by remember { mutableStateOf(false) }

    // 将当前有效种子颜色分解为 HSL
    val currentHsl = remember(effectiveSeed) { ThemeColorGenerator.colorToHsl(effectiveSeed) }
    var hue by remember(effectiveSeed) { mutableFloatStateOf(currentHsl[0]) }
    var saturation by remember(effectiveSeed) { mutableFloatStateOf(currentHsl[1]) }
    var lightness by remember(effectiveSeed) { mutableFloatStateOf(currentHsl[2]) }

    val currentRgb = remember(effectiveSeed) { ThemeColorGenerator.colorToRgbInts(effectiveSeed) }
    var rInput by remember(effectiveSeed) { mutableStateOf(currentRgb.first.toString()) }
    var gInput by remember(effectiveSeed) { mutableStateOf(currentRgb.second.toString()) }
    var bInput by remember(effectiveSeed) { mutableStateOf(currentRgb.third.toString()) }

    var hexInput by remember(effectiveSeed) { mutableStateOf(ThemeColorGenerator.colorToHex(effectiveSeed)) }

    MiuixCardGroup {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "主题风格",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "选择心仪的应用配色方案与组件视觉质感",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            // 主题风格卡片（经典书卷 VS MiuiX极简）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ThemeStyleCard(
                    modifier = Modifier.weight(1f),
                    title = "经典书卷",
                    subtitle = "国风暖玉 · Google Sans",
                    paletteColors = listOf(Color(0xFFF8F3EA), Color(0xFF9C4A2F), Color(0xFF5C7A5A)),
                    selected = isClassic,
                    onClick = { onStyleChange(AppThemeStyle.Classic.id) }
                )
                ThemeStyleCard(
                    modifier = Modifier.weight(1f),
                    title = "MiuiX 极简",
                    subtitle = "澎湃蔚蓝 · MiSans",
                    paletteColors = listOf(Color(0xFFF7F7F7), Color(0xFF3482FF), Color(0xFF00C4B4)),
                    selected = !isClassic,
                    onClick = { onStyleChange(AppThemeStyle.MiuiX.id) }
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(14.dp))

            // ── 调色盘与协议色彩衍生 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "主题调色盘",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isClassic) "Material 协议" else "MiuiX 协议",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isClassic) {
                            "以当前选定色为基准，依照 Google Material 协议自动拓展全套色阶"
                        } else {
                            "以当前选定色为基准，依照小米 MiuiX 规范自动拓展点睛与通透色阶"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (customSeedColor != null) {
                    TextButton(
                        onClick = { onCustomSeedColorChange(null) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认", fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 预设种子色块列表
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemePresets.list.forEach { preset ->
                    val isSelected = effectiveSeed.toArgb() == preset.color.toArgb()
                    val scale by animateFloatAsState(if (isSelected) 1.12f else 1f, label = "presetScale")
                    val borderColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.12f),
                        label = "presetBorder"
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .graphicsLayer(scaleX = scale, scaleY = scale)
                            .clip(CircleShape)
                            .semantics { contentDescription = preset.name }
                            .background(preset.color)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = borderColor,
                                shape = CircleShape
                            )
                            .clickable {
                                onCustomSeedColorChange(preset.color.toArgb())
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (ThemeColorGenerator.calculateLuminance(preset.color) > 0.5f) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 液态玻璃质感全局开关 (方案三 Shu Ding 物理透镜折射算法) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "液态玻璃质感 (Liquid Glass)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Shu Ding 边缘物理透镜折射与高光（兼容 Android 8+）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AdaptiveSwitch(
                    checked = isLiquidGlass,
                    onCheckedChange = { onLiquidGlassChange(it) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // 调色盘展开控制开关
            OutlinedButton(
                onClick = { showCustomPicker = !showCustomPicker },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Palette, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (showCustomPicker) "收起精准调色盘" else "展开精准调色盘 (Canva色轮/RGB/Hex)")
            }

            // 自定义调色盘面板
            AnimatedVisibility(
                visible = showCustomPicker,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(50),
                            color = effectiveSeed.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, effectiveSeed.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(18.dp).clip(CircleShape).background(effectiveSeed))
                                Spacer(Modifier.width(8.dp))
                                Text(effectiveHex, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                selectedPreset?.let {
                                    Spacer(Modifier.width(8.dp))
                                    Text("· ${it.name}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // 1. Canva 可画交互色轮
                        Text(
                            text = "Canva 交互色轮 (触控拖拽选择色相与饱和度)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        ColorWheel(
                            hue = hue,
                            saturation = saturation,
                            lightness = lightness,
                            onHslChange = { h, s ->
                                hue = h
                                saturation = s
                                val newC = ThemeColorGenerator.hslToColor(hue, saturation, lightness)
                                onCustomSeedColorChange(newC.toArgb())
                            },
                            wheelSize = 190.dp
                        )
                        Spacer(Modifier.height(12.dp))

                        // 2. 明度 (Lightness) 微调滑块
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                "明暗度 (Lightness): ${(lightness * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Slider(
                                value = lightness,
                                onValueChange = {
                                    lightness = it
                                    val newC = ThemeColorGenerator.hslToColor(hue, saturation, lightness)
                                    onCustomSeedColorChange(newC.toArgb())
                                },
                                valueRange = 0.2f..0.85f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // 3. 可填入的 RGB 分量数值 (0~255)
                        Column(Modifier.fillMaxWidth()) {
                            Text("RGB 颜色数值 (0~255)：", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = rInput,
                                    onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) rInput = it },
                                    label = { Text("R", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = gInput,
                                    onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) gInput = it },
                                    label = { Text("G", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = bInput,
                                    onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) bInput = it },
                                    label = { Text("B", fontSize = 11.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Button(
                                    onClick = {
                                        val r = rInput.toIntOrNull()?.coerceIn(0, 255) ?: 0
                                        val g = gInput.toIntOrNull()?.coerceIn(0, 255) ?: 0
                                        val b = bInput.toIntOrNull()?.coerceIn(0, 255) ?: 0
                                        val c = ThemeColorGenerator.rgbToColor(r, g, b)
                                        onCustomSeedColorChange(c.toArgb())
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("应用RGB")
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 4. Hex 输入框
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { hexInput = it },
                                 isError = hexInput.isNotBlank() && ThemeColorGenerator.parseHexColorOrNull(hexInput) == null,
                                 supportingText = if (hexInput.isNotBlank() && ThemeColorGenerator.parseHexColorOrNull(hexInput) == null) {
                                     { Text("请输入有效的 Hex 颜色，例如 #3482FF") }
                                 } else null,
                                label = { Text("颜色代码 (Hex)") },
                                placeholder = { Text("#3482FF") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(
                                 enabled = ThemeColorGenerator.parseHexColorOrNull(hexInput) != null,
                                onClick = {
                                    val parsed = ThemeColorGenerator.parseHexColorOrNull(hexInput)
                                    if (parsed != null) {
                                        onCustomSeedColorChange(parsed.toArgb())
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("应用Hex")
                            }
                        }
                    }
                }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 当前协议衍生色彩阶梯动态预览条 ──
            val isDarkCurrent = when (currentMode) {
                1 -> false
                2 -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val previewScheme = remember(effectiveSeed, isClassic, isDarkCurrent) {
                if (isClassic) {
                    ThemeColorGenerator.generateMaterialScheme(effectiveSeed, isDarkCurrent)
                } else {
                    ThemeColorGenerator.generateMiuixScheme(effectiveSeed, isDarkCurrent).first
                }
            }

            Text(
                text = "${if (isClassic) "Google Material 协议" else "MiuiX 极简协议"} 衍生色谱：",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            ) {
                listOf(
                    "主色" to previewScheme.primary,
                    "容器" to previewScheme.primaryContainer,
                    "次级" to previewScheme.secondary,
                    "表面" to previewScheme.surface,
                    "线条" to previewScheme.outline
                ).forEach { (label, color) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (ThemeColorGenerator.calculateLuminance(color) > 0.5f) Color(0xFF1F1F1F) else Color.White
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(14.dp))

            // 深色模式偏好
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "深色模式偏好",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = when (currentMode) {
                            1 -> "始终浅色视觉"
                            2 -> "始终暗夜视觉"
                            else -> "跟随系统自动切换"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MiuixSegmentedPicker(
                    items = listOf(
                        AppThemeMode.System.id to "系统",
                        AppThemeMode.Light.id to "浅色",
                        AppThemeMode.Dark.id to "深色"
                    ),
                    selectedItem = currentMode,
                    onSelect = onModeChange
                )
            }
        }
    }
}

@Composable
private fun ThemeStyleCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    paletteColors: List<Color>,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "styleBorder")
    val containerBg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), label = "styleBackground")

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        color = containerBg
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 色彩胶囊展示
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    paletteColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                        )
                    }
                }
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}

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

    AdaptiveDialog(
        onDismissRequest = {
            if (!downloading) onDismissRequest()
        },
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
                    AdaptiveLinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
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
