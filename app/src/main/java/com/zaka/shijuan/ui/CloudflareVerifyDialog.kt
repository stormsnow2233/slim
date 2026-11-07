package com.zaka.shijuan.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zaka.shijuan.data.Net
import com.zaka.shijuan.data.Store

/**
 * 针对 Cloudflare 浏览器质询（5秒盾 / 人机验证 / 403）及需要登录的书源站点提供的应用内 WebView 验证对话框。
 *
 * 用户在内嵌浏览器中完成交互或质询后，对话框自动监听 Cookie 变化并提取 cf_clearance、session 等有效 Token，
 * 实时同步回 Net.cookieJar 与本地持久化存储，使后台纯 HTTP 请求恢复畅通。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareVerifyDialog(
    url: String = "https://www.wenku8.cc",
    sourceName: String = "轻小说文库",
    store: Store,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
) {
    val ctx = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("正在加载验证页…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var verifiedFound by remember { mutableStateOf(false) }

    fun doSync(currentUrl: String) {
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(currentUrl).orEmpty()
        val host = runCatching { java.net.URI(currentUrl).host }.getOrNull() ?: "wenku8.cc"
        if (cookies.isNotBlank()) {
            Net.syncCookies(host, cookies)
            store.saveHostCookies(host, cookies)
            // 如果是 wenku8，顺便双向同步 .cc 和 .net 及其 www 域名
            if (host.contains("wenku8")) {
                listOf("wenku8.cc", "www.wenku8.cc", "wenku8.net", "www.wenku8.net", "wenku8.com", "www.wenku8.com", "wap.wenku8.cc").forEach { h ->
                    Net.syncCookies(h, cookies)
                    store.saveHostCookies(h, cookies)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, "关闭")
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$sourceName 登录与安全验证",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Rounded.Refresh, "刷新页面")
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = {
                            val cur = webViewRef?.url ?: url
                            doSync(cur)
                            Toast.makeText(ctx, "验证凭证已同步！", Toast.LENGTH_SHORT).show()
                            onVerified()
                            onDismiss()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("完成", fontSize = 13.sp)
                    }
                }

                if (progress in 0.01f..0.99f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                // 提示栏
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "提示：若页面出现 Cloudflare「确认您是真人」复选框或登录页，请在下方点击完成。验证通过后点击右上角「完成」即可同步凭证。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }

                // 内置浏览器
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { c ->
                        WebView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                userAgentString = Net.UA
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                }
                                override fun onReceivedTitle(view: WebView?, newTitle: String?) {
                                    if (!newTitle.isNullOrBlank()) title = newTitle
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    val h = request?.url?.host.orEmpty().lowercase()
                                    // 阻断流氓第三方广告网站劫持重定向（只允许目标源站及其正规跳转）
                                    val allowedHost = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase().orEmpty()
                                    val isAllowed = h == allowedHost ||
                                        (allowedHost.contains("wenku8") && (h.contains("wenku8.cc") || h.contains("wenku8.net") || h.contains("wenku8.com"))) ||
                                        h.endsWith(".$allowedHost")
                                    if (!isAllowed && target.startsWith("http")) {
                                        return true // 拦截恶意广告重定向
                                    }
                                    return false
                                }

                                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                                    val h = request?.url?.host.orEmpty().lowercase()
                                    // 拦截常见流氓广告与弹窗劫持脚本域名
                                    if (h.contains("609999.xyz") || h.contains("clarity.ms") || h.contains("hm.baidu.com") ||
                                        h.contains("google-analytics") || h.contains("doubleclick") || h.contains("cpro.baidu.com")) {
                                        return android.webkit.WebResourceResponse("text/javascript", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                    }
                                    return null
                                }

                                override fun onPageStarted(view: WebView?, startUrl: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, startUrl, favicon)
                                    startUrl?.let { doSync(it) }
                                }
                                override fun onPageFinished(view: WebView?, finishUrl: String?) {
                                    super.onPageFinished(view, finishUrl)
                                    val cur = finishUrl ?: url
                                    doSync(cur)
                                    val t = view?.title.orEmpty()
                                    title = t.ifBlank { cur }
                                    val cm = CookieManager.getInstance()
                                    val cookies = cm.getCookie(cur).orEmpty()
                                    val isChallenge = t.contains("Just a moment", ignoreCase = true) ||
                                        t.contains("Cloudflare", ignoreCase = true)
                                    if (cookies.contains("cf_clearance") || (!isChallenge && t.isNotBlank())) {
                                        verifiedFound = true
                                    }
                                }
                            }
                            webViewRef = this
                            loadUrl(url)
                        }
                    },
                )
            }
        }
    }
}
