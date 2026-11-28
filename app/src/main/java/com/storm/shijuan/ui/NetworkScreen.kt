package com.storm.shijuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.ClashDetector
import com.storm.shijuan.data.Net
import com.storm.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OK_GREEN = Color(0xFF4C7A54)

/**
 * 网络设置：自定义代理（HTTP/SOCKS5）、站点反代与连通性体检。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(store: Store, onBack: () -> Unit) {
    var cfg by remember { mutableStateOf(store.proxy()) }
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(if (cfg.port > 0) cfg.port.toString() else "") }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.pass) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }
    val scope = rememberCoroutineScope()

    var clashResult by remember { mutableStateOf<ClashDetector.Result?>(null) }
    var clashDetecting by remember { mutableStateOf(false) }


    fun detectClash() {
        if (clashDetecting) return
        clashDetecting = true
        scope.launch {
            clashResult = ClashDetector.detect(store)
            clashDetecting = false
        }
    }

    LaunchedEffect(Unit) {
        detectClash()
    }

    fun current(): Net.ProxyCfg = cfg.copy(
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        user = user.trim(),
        pass = pass,
    )

    fun save() {
        val c = current()
        store.saveProxy(c)
        cfg = c
        saved = true
    }

    fun runTest() {
        if (testing) return
        if (cfg.enabled) {
            save()
        }
        testing = true
        checks = emptyList()
        scope.launch {
            val list = listOf(
                "轻小说文库" to {
                    val url = store.wenku8ProxyUrl().ifBlank { "https://www.wenku8.cc" }.trimEnd('/') + "/index.php"
                    Net.get(url, useCache = false, fast = true, timeoutMs = 8000L)
                },
                "得奇小说" to {
                    Net.get("https://www.deqixs.org/", useCache = false, fast = true, timeoutMs = 8000L)
                },
                "速读谷" to {
                    Net.get("https://www.sudugu.cc/", useCache = false, fast = true, timeoutMs = 8000L)
                },
                "笔趣书阁" to {
                    Net.get("https://www.biqugese.com/", useCache = false, fast = true, timeoutMs = 8000L)
                },
                "轻小说百科" to {
                    Net.get("https://lnovel.tw/", useCache = false, fast = true, timeoutMs = 8000L)
                },
                "SF轻小说" to {
                    Net.get("https://m.sfacg.com/", useCache = false, fast = true, timeoutMs = 8000L)
                },
                "Z-Library" to {
                    val base = store.zlibProxyUrl().ifBlank { "https://zlib-proxy.us.ci" }.trimEnd('/')
                    Net.post(
                        "$base/eapi/book/search",
                        mapOf("message" to "ping", "limit" to "1"),
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        fast = true,
                        timeoutMs = 8000L,
                    )
                },
                "ESJ Zone" to {
                    val base = store.esjProxyUrl().ifBlank { "https://esj-proxy.us.ci" }.trimEnd('/')
                    Net.get("$base/", useCache = false, fast = true, timeoutMs = 8000L)
                },
                "漫画图片线路" to {
                    Net.get("https://s1.bzcdn.net/scomic/yirenzhixia-dongmantang/0/0-a1k5/1.jpg", useCache = false, fast = true, timeoutMs = 8000L)
                },
            )
            val resultsMap = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Boolean, String>>()
            withContext(Dispatchers.IO) {
                coroutineScope {
                    list.map { (name, probe) ->
                        async {
                            val t0 = System.currentTimeMillis()
                            val r = probe()
                            val ms = System.currentTimeMillis() - t0
                            val row = if (r.ok) Triple(name, true, "${ms}ms")
                            else Triple(name, false, r.error.ifBlank { "连接失败" })
                            resultsMap[name] = row
                            checks = list.mapNotNull { resultsMap[it.first] }
                        }
                    }.awaitAll()
                }
            }
            testing = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("网络", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "平时看书直连即可；漫画图片若加载慢可配置本地代理",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── 自定义代理卡片 ──
            LiquidGlassCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("自定义网络代理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (cfg.enabled) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            "已开启",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (cfg.usable && cfg.enabled) "已启用 · ${if (cfg.socks) "SOCKS5" else "HTTP"}://${cfg.host}:${cfg.port}"
                                else "平时使用 Clash 或梯子时，可填入本地端口进行代理转发",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AdaptiveSwitch(
                            checked = cfg.enabled,
                            onCheckedChange = { on ->
                                val newCfg = current().copy(enabled = on)
                                store.saveProxy(newCfg)
                                cfg = newCfg
                                saved = true
                            },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("代理协议与参数", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to "HTTP", true to "SOCKS5").forEach { (socks, label) ->
                            val on = cfg.socks == socks
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { cfg = cfg.copy(socks = socks); saved = false }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
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

                    Spacer(Modifier.height(12.dp))
                    ProxyField("主机", host, "127.0.0.1") { host = it; saved = false }
                    Spacer(Modifier.height(8.dp))
                    ProxyField("端口", port, "7890", number = true) { port = it; saved = false }
                    Spacer(Modifier.height(8.dp))
                    ProxyField("用户名（可留空）", user, "") { user = it; saved = false }
                    Spacer(Modifier.height(8.dp))
                    ProxyField("密码（可留空）", pass, "") { pass = it; saved = false }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = {
                                host = "127.0.0.1"; port = "7890"; saved = false
                            },
                            label = { Text("填本机 Clash 端口 (7890)", fontSize = 12.sp) },
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {
                                cfg = Net.ProxyCfg(enabled = false)
                                host = ""; port = ""; user = ""; pass = ""; saved = false
                                store.saveProxy(cfg)
                            },
                            label = { Text("清空", fontSize = 12.sp) },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    // ── 自动识别当前 Clash 节点 ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("当前 Clash 节点", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(3.dp))
                                when {
                                    clashDetecting -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp))
                                            Text("正在识别当前节点...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    clashResult != null && clashResult!!.success -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("🚀 ", fontSize = 13.sp)
                                            Text(
                                                clashResult!!.nodeName,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = OK_GREEN,
                                                maxLines = 1,
                                            )
                                        }
                                        if (clashResult!!.detail.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                clashResult!!.detail,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    else -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("⚠️ ", fontSize = 13.sp)
                                            Text(
                                                "识别失败",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                        if (clashResult != null && clashResult!!.detail.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                clashResult!!.detail,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            IconButton(
                                onClick = { detectClash() },
                                enabled = !clashDetecting,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = "重新识别",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { save() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (saved) "已保存，立即生效" else "保存配置")
                    }
                    if (saved) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "保存后直接生效，不用重启 App",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 连通性体检 ──
            LiquidGlassCard(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("连通性体检", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "检测常用站点的连通情况与时延",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(onClick = { runTest() }, enabled = !testing) {
                            if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Bolt, "开始检测")
                        }
                    }
                    if (checks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        checks.forEach { (name, ok, info) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (ok) OK_GREEN else MaterialTheme.colorScheme.error)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                                Text(
                                    info,
                                    fontSize = 12.sp,
                                    color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (!testing && checks.any { !it.second }) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "提示：部分源偶发网络波动或拦截属于正常现象，可换其他源看书。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "提示：默认推荐直连看书；如果个别漫画源图片无法载入，再开启自定义代理。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyField(
    label: String,
    value: String,
    hint: String,
    number: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(hint, fontSize = 13.sp) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text
        ),
    )
}
