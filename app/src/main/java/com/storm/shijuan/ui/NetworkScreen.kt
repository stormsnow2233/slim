package com.storm.shijuan.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.storm.shijuan.data.Net
import com.storm.shijuan.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OK_GREEN = Color(0xFF4C7A54)

/**
 * 网络设置：官方代理与自定义代理二选一，分流开关，以及站点连通性测试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(store: Store, onBack: () -> Unit) {
    var officialProxyOn by remember { mutableStateOf(store.officialProxyEnabled()) }
    var vpnSplitOn by remember { mutableStateOf(store.vpnSplitEnabled()) }
    var cfg by remember { mutableStateOf(store.proxy()) }
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(if (cfg.port > 0) cfg.port.toString() else "") }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.pass) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var wenku8Proxy by remember { mutableStateOf(store.wenku8ProxyUrl()) }
    var jmProxy by remember { mutableStateOf(store.jmProxyUrl()) }
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
        if (c.enabled) {
            officialProxyOn = false
        }
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
            val targets = listOf(
                "轻小说文库" to "https://dl.wenku8.com/down.php?type=utf8&node=1&id=1",
                "极速漫画 CDN" to "https://s1.bzcdn.net/scomic/yirenzhixia-dongmantang/0/0-a1k5/1.jpg",
                "漫画人图片" to "https://i.hamreus.com/images/comic/0/1/1.jpg",
                "漫画柜" to "https://m.manhuagui.com/",
                "笔趣书阁" to "https://www.biqugese.com/",
                "无限轻小说" to "https://www.8novel.com/",
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
                    "漫画封面或图片打不开时可以开代理，平时看书直连就行",
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

            // ── 官方代理卡片 ──
            Card(
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (officialProxyOn) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("官方代理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (officialProxyOn) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = OK_GREEN.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            "已开启",
                                            fontSize = 11.sp,
                                            color = OK_GREEN,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "自带海外节点，漫画图片打不开时开它（国内小说会自动直连）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AdaptiveSwitch(
                            checked = officialProxyOn,
                            onCheckedChange = { on ->
                                officialProxyOn = on
                                store.setOfficialProxyEnabled(on)
                                if (on) {
                                    cfg = store.proxy()
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))

                    // ── 分流开关 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("分流开关", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "检测到本地VPN开启的话就不使用自带代理，开了的话生效，不开的话就算手机挂着梯子也强制走（管他呢）",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        AdaptiveSwitch(
                            checked = vpnSplitOn,
                            onCheckedChange = { on ->
                                vpnSplitOn = on
                                store.setVpnSplitEnabled(on)
                            },
                        )
                    }

                    if (officialProxyOn) {
                        Spacer(Modifier.height(10.dp))
                        val isVpn = store.isVpnActive()
                        if (isVpn && vpnSplitOn) {
                            Text(
                                "🟡 检测到手机开了梯子/VPN，自带代理已暂停让路",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                "🟢 自带代理生效中 · 漫画走加速，小说走直连",
                                fontSize = 11.sp,
                                color = OK_GREEN,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 自己填代理卡片 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("自己填代理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                if (cfg.usable && cfg.enabled) "已开启 · ${if (cfg.socks) "SOCKS5" else "HTTP"}://${cfg.host}:${cfg.port}"
                                else "平时自己有开 Clash 或梯子可以填这里",
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
                                if (on) {
                                    officialProxyOn = false
                                }
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
                            label = { Text("填本机 Clash 端口", fontSize = 12.sp) },
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

                    Spacer(Modifier.height(12.dp))
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

            // ── 裸连模式提示 ──
            if (!officialProxyOn && !cfg.enabled) {
                Spacer(Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚡", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("当前是直连模式（没走任何代理）", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                "国内看小说直连最快。如果漫画封面或部分源刷不出来，再打开上面的代理即可。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 轻小说文库反代 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("轻小说文库反代", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "默认走内置加速节点。如果你自己搭了文库反代 Workers，可以填在这里覆盖。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    ProxyField("自定义反代 URL（留空走内置）", wenku8Proxy, "留空默认走内置加速节点") {
                        wenku8Proxy = it.trim()
                        store.setWenku8ProxyUrl(it.trim())
                    }
                    Spacer(Modifier.height(6.dp))
                    if (wenku8Proxy.isNotBlank()) {
                        Text("已切换为你填写的私有反代", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("🟢 默认内置加速生效中", fontSize = 11.sp, color = OK_GREEN)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── JMcomic 反代设置 ──
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("JMcomic 反代设置", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "默认走内置线路。如果你在 Cloudflare 搭建了专属反代，填在这里即可。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    ProxyField("自定义反代 URL（留空走内置）", jmProxy, "留空默认走内置线路") {
                        jmProxy = it.trim()
                        store.setJmProxyUrl(it.trim())
                    }
                    Spacer(Modifier.height(6.dp))
                    if (jmProxy.isNotBlank()) {
                        Text("已切换为你填写的专属反代", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("🟢 默认内置线路生效中", fontSize = 11.sp, color = OK_GREEN)
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
                                "测一下几个常用站，看看当前网络通不通",
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
                                "提示：测不通多半是站点偶尔抽风，只要换别的源能看就不用管。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "提示：官方代理和自己填代理二选一，开了一个会自动关另一个；都关掉就是纯直连。",
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
