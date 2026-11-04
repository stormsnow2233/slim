package com.zaka.shijuan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaka.shijuan.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自己添加 / 编辑书源。
 * 只填「名称 + 网址」也能存：搜索、目录、正文都走通用识别兜底。
 * 想更准就展开高级规则，写 CSS 选择器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSourceEditScreen(
    initial: Source?,
    onBack: () -> Unit,
    onSaved: (Source) -> Unit,
    onDeleted: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var base by remember { mutableStateOf(initial?.base ?: "") }
    var group by remember { mutableStateOf(initial?.group ?: "我的书源") }
    var charset by remember { mutableStateOf(initial?.charset ?: "utf-8") }
    var searchUrl by remember { mutableStateOf(initial?.search?.url ?: "") }
    var method by remember { mutableStateOf(initial?.search?.method ?: "GET") }
    var listSel by remember { mutableStateOf(initial?.search?.list ?: "") }
    var nameSel by remember { mutableStateOf(initial?.search?.fields?.get("name") ?: "") }
    var urlSel by remember { mutableStateOf(initial?.search?.fields?.get("bookUrl") ?: "") }
    var coverSel by remember { mutableStateOf(initial?.search?.fields?.get("cover") ?: "") }
    var authorSel by remember { mutableStateOf(initial?.search?.fields?.get("author") ?: "") }
    var tocSel by remember { mutableStateOf(initial?.toc?.list ?: "") }
    var tocPage by remember { mutableStateOf(initial?.toc?.page ?: "") }
    var contentSel by remember { mutableStateOf(initial?.content?.body ?: "") }

    var advanced by remember { mutableStateOf(false) }
    var testKey by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var probeLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    fun fullBase(): String {
        var b = base.trim().trimEnd('/')
        if (b.isBlank()) return ""
        if (!b.startsWith("http", true)) b = "https://$b"
        return b
    }

    /** 把表单拼成一个可搜索、可阅读的书源 */
    fun buildSource(): Source? {
        val b = fullBase()
        if (b.isBlank()) return null
        val nm = name.trim().ifBlank {
            runCatching { java.net.URI(b).host }.getOrNull()?.removePrefix("www.") ?: "自定义源"
        }
        val fields = LinkedHashMap<String, String>()
        if (nameSel.isNotBlank()) fields["name"] = nameSel.trim()
        if (urlSel.isNotBlank()) fields["bookUrl"] = urlSel.trim()
        if (coverSel.isNotBlank()) fields["cover"] = coverSel.trim()
        if (authorSel.isNotBlank()) fields["author"] = authorSel.trim()
        val sUrl = searchUrl.trim().ifBlank { "/search.php?searchkey={key}" }
        return Source(
            id = initial?.id ?: CustomSources.newId(b),
            name = nm,
            group = group.trim().ifBlank { "我的书源" },
            base = b,
            charset = charset,
            status = "custom",
            note = if (listSel.isBlank()) "自己添加的源 · 走通用识别，认不准就在「编辑」里填选择器"
            else "自己添加的源 · 自定义规则",
            search = SearchRule(
                url = sUrl,
                method = method,
                body = if (method == "POST") "searchkey={key}" else "",
                list = listSel.trim(),
                fields = fields,
                auto = listSel.isBlank(),
            ),
            toc = if (tocSel.isBlank() && tocPage.isBlank()) null
            else TocRule(list = tocSel.trim(), name = "@text", url = "@href", page = tocPage.trim()),
            content = if (contentSel.isBlank()) null else ContentRule(body = contentSel.trim()),
        )
    }

    fun save() {
        val src = buildSource()
        if (src == null) {
            result = "先把站点地址填上，比如 www.xxx.com"
            resultOk = false
            return
        }
        CustomSources.upsert(src)
        onSaved(src)
    }

    fun runTest() {
        val src = buildSource()
        if (src == null) {
            result = "先把站点地址填上"
            resultOk = false
            return
        }
        val k = testKey.trim().ifBlank { "小说" }
        if (testing) return
        testing = true
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { BookRepo.searchSource(src, k) } }
            testing = false
            val got = r.getOrNull()
            if (got == null) {
                result = "测试出错：${r.exceptionOrNull()?.message}"
                resultOk = false
                return@launch
            }
            resultOk = got.ok
            result = if (got.ok) {
                val first = got.books.take(3).joinToString("、") { it.name }
                "搜到 ${got.count} 本 · ${got.ms}ms\n$first"
            } else {
                "没搜到 · ${got.error}"
            }
        }
    }

    fun probe() {
        val b = fullBase()
        if (b.isBlank()) {
            result = "先把站点地址填上"
            resultOk = false
            return
        }
        if (probeLoading) return
        probeLoading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { CustomSources.probeSearch(b, charset) }
            }.getOrNull()
            probeLoading = false
            if (r == null) {
                result = "首页上没找到搜索框，手动填搜索地址吧（用 {key} 代替关键词）"
                resultOk = false
            } else {
                searchUrl = r.url
                method = r.method
                result = "探测到了：${r.method} ${r.url}"
                resultOk = true
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(
                    if (initial == null) "添加书源" else "编辑书源",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "填名称和网址就能用，规则可以留空",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showImport = true }) {
                Icon(Icons.Rounded.ContentPaste, "粘贴导入", tint = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { save() }) { Text("保存", fontWeight = FontWeight.SemiBold) }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {

            // ── 基本信息 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("基本信息", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    SrcField("名称", name, "比如 某某小说网") { name = it }
                    Spacer(Modifier.height(8.dp))
                    SrcField("站点地址", base, "www.xxx.com") { base = it }
                    Spacer(Modifier.height(8.dp))
                    SrcField("分组", group, "我的书源") { group = it }
                    Spacer(Modifier.height(10.dp))
                    Text("网页编码", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("utf-8" to "UTF-8（大多数站）", "gbk" to "GBK（老站）").forEach { (v, label) ->
                            val on = charset == v
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { charset = v }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    color = if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 搜索 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("搜索", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "留空就用站点的常规路径；填 {key} 代表关键词",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    SrcField("搜索地址", searchUrl, "/search.php?searchkey={key}") { searchUrl = it }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { probe() },
                            shape = RoundedCornerShape(12.dp),
                            enabled = !probeLoading,
                        ) {
                            if (probeLoading) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("自动探测", fontSize = 12.sp)
                        }
                        listOf("GET" to "GET", "POST" to "POST").forEach { (v, label) ->
                            val on = method == v
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { method = v }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    color = if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))

                    SrcField("测试关键词", testKey, "比如 斗破苍穹") { testKey = it }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { runTest() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !testing,
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                Modifier.size(15.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (testing) "搜索中…" else "测一下能不能搜到")
                    }
                    result?.let { r ->
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (resultOk) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                r,
                                modifier = Modifier.padding(10.dp),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = if (resultOk) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 高级规则 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { advanced = !advanced },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("解析规则（可选）", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "不填就走自动识别；填了更准",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(if (advanced) "收起" else "展开", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }

                    if (advanced) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "选择器写法：CSS 选择器 + @字段\n" +
                                "  .bookbox           列表项\n" +
                                "  h3 a@text          书名\n" +
                                "  h3 a@href          链接\n" +
                                "  img@src            封面\n" +
                                "多个备选写 ||，后处理写 ##正则##替换",
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))

                        SrcField("搜索结果：列表项", listSel, ".bookbox") { listSel = it }
                        Spacer(Modifier.height(8.dp))
                        SrcField("书名", nameSel, "h3 a@text") { nameSel = it }
                        Spacer(Modifier.height(8.dp))
                        SrcField("书籍链接", urlSel, "h3 a@href") { urlSel = it }
                        Spacer(Modifier.height(8.dp))
                        SrcField("封面", coverSel, "img@src") { coverSel = it }
                        Spacer(Modifier.height(8.dp))
                        SrcField("作者", authorSel, ".author@text") { authorSel = it }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(14.dp))

                        SrcField("目录：章节列表", tocSel, "#list dd a") { tocSel = it }
                        Spacer(Modifier.height(8.dp))
                        SrcField("目录页模板（可选）", tocPage, "https://m.xxx.com/i/{id}/") { tocPage = it }
                        Spacer(Modifier.height(8.dp))
                        SrcField("正文容器", contentSel, "#content@html") { contentSel = it }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 分享 / 删除 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(
                        onClick = {
                            val src = buildSource()
                            if (src == null) {
                                Toast.makeText(ctx, "先把站点地址填上", Toast.LENGTH_SHORT).show()
                            } else {
                                copyText(ctx, CustomSources.pretty(src))
                                Toast.makeText(ctx, "书源 JSON 已复制", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("复制这个源（JSON，可分享给别人）", fontSize = 13.sp) }

                    if (initial != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { showDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("删除这个书源", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
        }
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            onImported = { list ->
                showImport = false
                Toast.makeText(ctx, "已导入 ${list.size} 个书源", Toast.LENGTH_SHORT).show()
                onSaved(list.first())
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这个书源？") },
            text = { Text("只会删掉源本身，书架里用这个源加的书不受影响（但那本书可能打不开了）。") },
            confirmButton = {
                TextButton(onClick = {
                    initial?.let { CustomSources.remove(it.id) }
                    showDelete = false
                    onDeleted()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

/** 粘贴 JSON 导入：自家格式和「阅读」的书源格式都认 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImported: (List<Source>) -> Unit,
) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴导入书源") },
        text = {
            Column {
                Text(
                    "把书源 JSON 粘进来，支持单个、多个数组，也支持「阅读」App 的书源格式。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; err = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                    placeholder = { Text("{\"name\":\"...\",\"base\":\"https://...\"}", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = { text = readClip(ctx); err = null }) { Text("从剪贴板读", fontSize = 12.sp) }
                    Spacer(Modifier.weight(1f))
                    if (err != null) {
                        Text(err!!, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val list = runCatching { CustomSources.parseAll(text) }.getOrElse { emptyList() }
                if (list.isEmpty()) {
                    err = "没解析出书源，检查一下 JSON"
                } else {
                    list.forEach { CustomSources.upsert(it) }
                    onImported(list)
                }
            }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SrcField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(12.dp),
    )
}

private fun copyText(ctx: Context, s: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("书源", s))
    }
}

private fun readClip(ctx: Context): String = runCatching {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
}.getOrDefault("")
