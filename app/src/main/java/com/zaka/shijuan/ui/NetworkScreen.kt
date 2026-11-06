package com.zaka.shijuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaka.shijuan.data.Net
import com.zaka.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OK_GREEN = Color(0xFF4C7A54)

/**
 * 网络设置：直连还是走代理，以及一个「现在到底能不能连上」的体检。
 * 国内直连时很多站在 Cloudflare 后面会直接 403，走自己的代理最省事。
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
        // 先落地当前填的代理，不然测的还是旧配置
        save()
        testing = true
        checks = emptyList()
        scope.launch {
            val targets = listOf(
                "笔趣书阁" to "https://www.biqugese.com/",
                "无限轻小说" to "https://www.8novel.com/",
                "17K小说网" to "https://www.17k.com/",
                "SF轻小说" to "https://m.sfacg.com/",
            )
            val acc = ArrayList<Triple<String, Boolean, String>>()
            withContext(Dispatchers.IO) {
                for ((name, url) in targets) {
                    val t0 = System.currentTimeMillis()
                    val r = Net.get(url, useCache = false)
                    val ms = System.currentTimeMillis() - t0
                    val row = if (r.ok) Triple(name, true, "${ms}ms")
                    else Triple(name, false, r.error)
                    acc.add(row)
                    checks = acc.toList()
                }
            }
            testing = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
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
                    "国内直连很多站会 403，走自己的代理最省事",
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

            // ── 代理开关 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("走代理", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (cfg.usable) "已开启 · ${cfg.host}:${cfg.port}"
                                else "关闭时全部直连",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = {
                                cfg = cfg.copy(enabled = it)
                                saved = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 代理参数 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("代理地址", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))

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
                            label = { Text("填本机 Clash 端口", fontSize = 12.sp) },
                        )
                        Spacer(Modifier.width(8.dp))
                        AssistChip(
                            onClick = {
                                cfg = Net.ProxyCfg(enabled = false)
                                host = ""; port = ""; user = ""; pass = ""; saved = false
                            },
                            label = { Text("清空", fontSize = 12.sp) },
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { save() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (saved) "已保存，立即生效" else "保存")
                    }
                    if (saved) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "改完不用重启，下一次搜索就走新设置",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 连通性体检 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("连通性体检", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "打几个代表站，看是网络问题还是书源问题",
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
                                Text(name, fontSize = 12.sp, modifier = Modifier.width(88.dp))
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
                                "测不通的多半是网络或站点拦截，不是书源规则坏了。换个源还能用就先用。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "提示：分流代理里把「国内站」也走代理，比直连稳。只代理国外的话，国内这些站照样吃 403。",
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
