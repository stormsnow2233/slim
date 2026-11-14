package com.storm.shijuan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NetworkCheck
import com.storm.shijuan.data.CustomSources
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.BookRepo
import com.storm.shijuan.data.Source
import com.storm.shijuan.data.SourceResult
import com.storm.shijuan.data.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    store: com.storm.shijuan.data.Store = com.storm.shijuan.data.Store(androidx.compose.ui.platform.LocalContext.current),
    enabled: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onOpenDrawer: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    onAddSource: () -> Unit = {},
    onEditSource: (Source) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var checks by remember { mutableStateOf<Map<String, SourceResult>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    var verifyTarget by remember { mutableStateOf<Source?>(null) }

    var showImportSheet by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("") }

    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val (count, msg) = withContext(Dispatchers.IO) {
                    CustomSources.importJsonUri(context, uri)
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 自定义源增删改后要跟着刷新
    val customVersion = Sources.customVersion
    val allSources = remember(customVersion) { Sources.all }
    val customCount = remember(customVersion) { Sources.custom.size }
    val allCustomIds = remember(customVersion) { Sources.custom.map { it.id }.toSet() }

    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedIds = emptySet()
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("批量删除自定义书源") },
            text = { Text("确定要删除选中的 ${selectedIds.size} 个自定义书源吗？删除后无法恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        val count = selectedIds.size
                        CustomSources.removeAll(selectedIds)
                        showDeleteConfirmDialog = false
                        isSelectionMode = false
                        selectedIds = emptySet()
                        Toast.makeText(context, "已删除 $count 个自定义书源", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    val groups = remember(customVersion) {
        val builtin = Sources.ALL.groupBy { it.group }
        if (Sources.custom.isEmpty()) builtin
        else linkedMapOf("我的书源" to Sources.custom) + builtin
    }

    if (verifyTarget != null) {
        CloudflareVerifyDialog(
            url = verifyTarget!!.base,
            sourceName = verifyTarget!!.name,
            store = store,
            onDismiss = { verifyTarget = null },
            onVerified = {
                val s = verifyTarget ?: return@CloudflareVerifyDialog
                verifyTarget = null
                scope.launch {
                    val rep = withContext(Dispatchers.IO) {
                        runCatching { BookRepo.healthDeep(s) }
                            .getOrElse { com.storm.shijuan.data.DeepHealthResult(false, "检测异常：${it.message}", com.storm.shijuan.data.SourceStatus.SITE_ERROR) }
                    }
                    checks = checks + (s.id to SourceResult(s.id, s.name, rep.ok, emptyList(), rep.detail, status = rep.status, httpCode = rep.httpCode))
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    isSelectionMode = false
                    selectedIds = emptySet()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "取消选择")
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (selectedIds.isEmpty()) "选择要删除的书源" else "已选中 ${selectedIds.size} 项",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "长按或点击勾选自定义书源进行批量删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val isAllSelected = allCustomIds.isNotEmpty() && selectedIds.size == allCustomIds.size
                TextButton(onClick = {
                    selectedIds = if (isAllSelected) emptySet() else allCustomIds
                }) {
                    Text(if (isAllSelected) "全不选" else "全选")
                }
                IconButton(
                    onClick = {
                        if (selectedIds.isNotEmpty()) {
                            showDeleteConfirmDialog = true
                        }
                    },
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "批量删除",
                        tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                } else {
                    DrawerButton(onClick = onOpenDrawer)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("书源", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "已启用 ${enabled.size} / ${allSources.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { showImportSheet = true }) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "添加或导入书源",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(
                    onClick = {
                        if (checking) return@FilledTonalButton
                        checking = true
                        scope.launch {
                            val targets = allSources.filter { it.apiKind != "zlib" }
                            val got = withContext(Dispatchers.IO) {
                                coroutineScope {
                                    targets.map { s ->
                                        async {
                                            val rep = runCatching { BookRepo.healthDeep(s) }
                                                .getOrElse { com.storm.shijuan.data.DeepHealthResult(false, "检测异常：${it.message}", com.storm.shijuan.data.SourceStatus.SITE_ERROR) }
                                            s.id to SourceResult(s.id, s.name, rep.ok, emptyList(), rep.detail, status = rep.status, httpCode = rep.httpCode)
                                        }
                                    }.awaitAll()
                                }
                            }
                            checks = got.toMap()
                            checking = false
                        }
                    },
                    enabled = !checking,
                ) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (checking) "检测中" else "全部体检")
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            groups.forEach { (group, list) ->
                item {
                    Text(
                        group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.id }) { src ->
                    val isCustom = src.status == "custom"
                    SourceCard(
                        src = src,
                        on = src.id in enabled,
                        check = checks[src.id],
                        onToggle = { onToggle(src.id, it) },
                        onEdit = if (isCustom && !isSelectionMode) ({ onEditSource(src) }) else null,
                        onVerify = { verifyTarget = src },
                        isSelectionMode = isSelectionMode,
                        isSelected = src.id in selectedIds,
                        onSelectToggle = if (isCustom) ({
                            selectedIds = if (src.id in selectedIds) selectedIds - src.id else selectedIds + src.id
                        }) else null,
                        onStartSelection = if (isCustom) ({
                            isSelectionMode = true
                            selectedIds = setOf(src.id)
                        }) else null,
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：标「已实测」的源在当前网络下验证过可用；标「国内直连」的源只对中国大陆网络开放，" +
                        "海外网络会打不开。点「全部体检」可以自己测一遍，哪张卡转圈失败就换别的源。\n" +
                        "右上角「+」支持自制书源与一键导入（本地 JSON、剪贴板及在线订阅）；长按任意自定义书源即可触发多选批量删除。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(onDismissRequest = { showImportSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)
            ) {
                Text("添加与导入书源", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "支持手动自制、阅读 3.0 / JSON 合集文件、剪贴板及在线链接",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        showImportSheet = false
                        onAddSource()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Edit, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("手动新建书源", style = MaterialTheme.typography.titleMedium)
                            Text("从零自制单本书源，只填名称和网址即可智能识别", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        showImportSheet = false
                        fileLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("本地 JSON 文件导入", style = MaterialTheme.typography.titleMedium)
                            Text("从手机存储选取 .json 书源配置文件直接导入", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        showImportSheet = false
                        pasteText = ""
                        showPasteDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("粘贴 JSON 文本导入", style = MaterialTheme.typography.titleMedium)
                            Text("从剪贴板粘贴单本或批量书源 JSON 内容", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    onClick = {
                        showImportSheet = false
                        urlText = ""
                        showUrlDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("网络链接订阅导入", style = MaterialTheme.typography.titleMedium)
                            Text("输入 http:// 或 https:// 网络书源地址在线拉取", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (customCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        onClick = {
                            showImportSheet = false
                            isSelectionMode = true
                            selectedIds = emptySet()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("批量管理自定义书源", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                                Text("多选并批量删除已导入的自定义书源（长按书源卡片亦可直接触发）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("粘贴 JSON 书源") },
            text = {
                Column {
                    Text("直接粘贴单本源或合集数组 JSON 格式：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pasteText,
                        onValueChange = { pasteText = it },
                        placeholder = { Text("在此粘贴 JSON 内容...") },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) pasteText = clip
                        }
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("从剪贴板粘贴")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPasteDialog = false
                        if (pasteText.isNotBlank()) {
                            scope.launch {
                                val (count, msg) = withContext(Dispatchers.IO) {
                                    CustomSources.importJsonText(pasteText)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text("立即导入") }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("网络书源订阅") },
            text = {
                Column {
                    Text("输入在线书源 JSON 网址：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        placeholder = { Text("https://example.com/sources.json") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUrlDialog = false
                        if (urlText.isNotBlank()) {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    CustomSources.importSubscription(urlText)
                                }
                                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text("拉取并导入") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceCard(
    src: Source,
    on: Boolean,
    check: SourceResult?,
    onToggle: (Boolean) -> Unit,
    onEdit: (() -> Unit)? = null,
    onVerify: (() -> Unit)? = null,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: (() -> Unit)? = null,
    onStartSelection: (() -> Unit)? = null,
) {
    val isCustom = src.status == "custom"
    val haptic = LocalHapticFeedback.current

    val needsVerify = src.id == "wenku8" ||
        check?.status == com.storm.shijuan.data.SourceStatus.ACCESS_RESTRICTED ||
        check?.status == com.storm.shijuan.data.SourceStatus.NEEDS_LOGIN ||
        check?.error?.contains("Cloudflare") == true ||
        check?.error?.contains("安全质询") == true ||
        check?.error?.contains("登录") == true

    val cardModifier = if (isSelectionMode) {
        if (isCustom) {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onSelectToggle?.invoke() }
        } else {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        }
    } else {
        if (isCustom) {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = { /* 正常点击保持卡片内控件响应 */ },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStartSelection?.invoke()
                    }
                )
        } else {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
        }
    }

    val cardBg = when {
        isSelectionMode && isCustom && isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        isSelectionMode && !isCustom -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }

    val border = if (isSelectionMode && isCustom && isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else null

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = border,
        tonalElevation = 0.dp,
        modifier = cardModifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).then(
                    if (isSelectionMode && !isCustom) Modifier.alpha(0.38f) else Modifier
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(src.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    when (src.status) {
                        "verified" -> TinyTag("已实测", androidx.compose.ui.graphics.Color(0xFF4C7A54))
                        "local" -> TinyTag("国内直连", MaterialTheme.colorScheme.primary)
                        "api" -> TinyTag("接口", androidx.compose.ui.graphics.Color(0xFF456B8A))
                        "custom" -> TinyTag("自定义", androidx.compose.ui.graphics.Color(0xFF8A5A2B))
                        else -> {}
                    }
                    if (check != null && !check.ok) {
                        Spacer(Modifier.width(6.dp))
                        when (check.status) {
                            com.storm.shijuan.data.SourceStatus.ACCESS_RESTRICTED -> TinyTag("访问受限", androidx.compose.ui.graphics.Color(0xFFE65100))
                            com.storm.shijuan.data.SourceStatus.NEEDS_LOGIN -> TinyTag("需登录", androidx.compose.ui.graphics.Color(0xFF1565C0))
                            com.storm.shijuan.data.SourceStatus.SITE_ERROR -> TinyTag("站点异常", MaterialTheme.colorScheme.error)
                            com.storm.shijuan.data.SourceStatus.RULE_INVALID -> TinyTag("规则失效", androidx.compose.ui.graphics.Color(0xFF6A1B9A))
                            else -> {}
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    src.note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
                val metrics = com.storm.shijuan.data.BookRepo.sourceMetrics[src.id]
                if (check != null) {
                    Spacer(Modifier.height(5.dp))
                    val bg = when (check.status) {
                        com.storm.shijuan.data.SourceStatus.NORMAL -> androidx.compose.ui.graphics.Color(0xFF4C7A54).copy(alpha = 0.1f)
                        com.storm.shijuan.data.SourceStatus.ACCESS_RESTRICTED -> androidx.compose.ui.graphics.Color(0xFFE65100).copy(alpha = 0.12f)
                        com.storm.shijuan.data.SourceStatus.NEEDS_LOGIN -> androidx.compose.ui.graphics.Color(0xFF1565C0).copy(alpha = 0.12f)
                        com.storm.shijuan.data.SourceStatus.SITE_ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.09f)
                        com.storm.shijuan.data.SourceStatus.RULE_INVALID -> androidx.compose.ui.graphics.Color(0xFF6A1B9A).copy(alpha = 0.12f)
                        else -> if (check.ok) androidx.compose.ui.graphics.Color(0xFF4C7A54).copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.09f)
                    }
                    val fg = when (check.status) {
                        com.storm.shijuan.data.SourceStatus.NORMAL -> androidx.compose.ui.graphics.Color(0xFF4C7A54)
                        com.storm.shijuan.data.SourceStatus.ACCESS_RESTRICTED -> androidx.compose.ui.graphics.Color(0xFFE65100)
                        com.storm.shijuan.data.SourceStatus.NEEDS_LOGIN -> androidx.compose.ui.graphics.Color(0xFF1565C0)
                        com.storm.shijuan.data.SourceStatus.SITE_ERROR -> MaterialTheme.colorScheme.error
                        com.storm.shijuan.data.SourceStatus.RULE_INVALID -> androidx.compose.ui.graphics.Color(0xFF6A1B9A)
                        else -> if (check.ok) androidx.compose.ui.graphics.Color(0xFF4C7A54) else MaterialTheme.colorScheme.error
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        val rateStr = if (metrics != null && metrics.totalRequests > 0) " · 成功率 ${(metrics.overallSuccessRate * 100).toInt()}%" else ""
                        Text(
                            (if (check.ok) "可用 · ${check.error.ifBlank { "${check.books.size} 本 · ${check.ms}ms" }}"
                            else "${check.status.label} · ${check.error}") + rateStr,
                            fontSize = 10.sp,
                            color = fg,
                        )
                    }
                } else if (metrics != null && metrics.totalRequests > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        val searchRate = if (metrics.searchTotal > 0) "搜 ${(metrics.searchSuccessRate * 100).toInt()}% " else ""
                        val tocRate = if (metrics.tocTotal > 0) "目 ${(metrics.tocSuccessRate * 100).toInt()}% " else ""
                        val contentRate = if (metrics.contentTotal > 0) "文 ${(metrics.contentSuccessRate * 100).toInt()}%" else ""
                        val subRates = "$searchRate$tocRate$contentRate".trim()
                        val extra = if (subRates.isNotBlank()) " ($subRates)" else ""
                        val failWarn = if (metrics.consecutiveFailures > 0) " · 连败 ${metrics.consecutiveFailures}" else ""
                        Text(
                            "请求 ${metrics.totalRequests} 次 · 成功率 ${(metrics.overallSuccessRate * 100).toInt()}%$extra$failWarn",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (isSelectionMode) {
                if (isCustom) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectToggle?.invoke() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            } else {
                if (needsVerify && onVerify != null) {
                    val isLogin = check?.status == com.storm.shijuan.data.SourceStatus.NEEDS_LOGIN || check?.error?.contains("登录") == true
                    OutlinedButton(
                        onClick = onVerify,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(if (isLogin) "网页登录" else "网页验证", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (onEdit != null) {
                    TextButton(
                        onClick = onEdit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("编辑", fontSize = 12.sp) }
                }
                AdaptiveSwitch(checked = on, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
fun MineScreen(
    store: com.storm.shijuan.data.Store,
    shelfCount: Int,
    enabledCount: Int,
    onOpenDrawer: () -> Unit = {},
) {
    var prefs by remember { mutableStateOf(store.readerPrefs()) }
    var showClear by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawerButton(onClick = onOpenDrawer)
            Spacer(Modifier.width(12.dp))
            Text("我的", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("书架", "$shelfCount", Modifier.weight(1f))
            StatCard("书源", "$enabledCount", Modifier.weight(1f))
            StatCard("版本", com.storm.shijuan.BuildConfig.VERSION_NAME, Modifier.weight(1f))
        }

        Spacer(Modifier.height(22.dp))
        Text("阅读偏好", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("字号 ${prefs.fontSize.toInt()}", style = MaterialTheme.typography.bodyMedium)
                AdaptiveSlider(
                    value = prefs.fontSize,
                    onValueChange = {
                        prefs = prefs.copy(fontSize = it)
                        store.saveReaderPrefs(prefs)
                    },
                    valueRange = 14f..30f,
                    steps = 15,
                )
                Spacer(Modifier.height(4.dp))
                Text("行距 ${String.format("%.2f", prefs.lineHeight)}", style = MaterialTheme.typography.bodyMedium)
                AdaptiveSlider(
                    value = prefs.lineHeight,
                    onValueChange = {
                        prefs = prefs.copy(lineHeight = it)
                        store.saveReaderPrefs(prefs)
                    },
                    valueRange = 1.2f..2.4f,
                    steps = 11,
                )
                Spacer(Modifier.height(8.dp))
                Text("翻页方式", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                MiuixSegmentedPicker(
                    items = listOf(0 to "上下滚动", 1 to "左右翻页"),
                    selectedItem = prefs.pageMode,
                    onSelect = {
                        prefs = prefs.copy(pageMode = it)
                        store.saveReaderPrefs(prefs)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("数据", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                ListRow("清空搜索历史") { store.clearHistory() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ListRow("清除离线缓存") { showClear = true }
            }
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "拾卷 ${com.storm.shijuan.BuildConfig.VERSION_NAME} · 原生安卓版\n书源内容来自各公开站点，仅供个人阅读使用",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(104.dp))
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清除离线缓存？") },
            text = { Text("看过的章节正文会被清掉，下次阅读需要重新联网加载。书架和进度不受影响。") },
            confirmButton = {
                TextButton(onClick = { store.clearAll(); showClear = false }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.padding(vertical = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SegmentedButton(selected: Boolean, onClick: () -> Unit, label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ListRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
