package com.storm.shijuan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 智能 Clash / FlClash / Clash Meta / CFA 节点与出口识别器
 * 全兼容本地外部控制器 API (RESTful) 与海外代理通道出口探测
 */
object ClashDetector {

    data class Result(
        val success: Boolean,
        val nodeName: String,
        val detail: String = "",
        val isController: Boolean = false,
    )

    private val directClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val egressClient = OkHttpClient.Builder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .build()

    private val countryNames = mapOf(
        "HK" to "香港",
        "TW" to "台湾",
        "JP" to "日本",
        "SG" to "新加坡",
        "US" to "美国",
        "KR" to "韩国",
        "GB" to "英国",
        "UK" to "英国",
        "DE" to "德国",
        "FR" to "法国",
        "CA" to "加拿大",
        "AU" to "澳大利亚",
        "RU" to "俄罗斯",
        "NL" to "荷兰",
        "MY" to "马来西亚",
        "TH" to "泰国",
        "VN" to "越南",
        "PH" to "菲律宾",
        "IN" to "印度",
        "TR" to "土耳其",
        "AR" to "阿根廷"
    )

    private val coloNames = mapOf(
        "HKG" to "香港机房",
        "NRT" to "东京机房",
        "HND" to "羽田机房",
        "KIX" to "大阪机房",
        "SIN" to "新加坡机房",
        "TPE" to "台北机房",
        "ICN" to "首尔机房",
        "SJC" to "硅谷机房",
        "LAX" to "洛杉矶机房",
        "SEA" to "西雅图机房",
        "FRA" to "法兰克福",
        "LHR" to "伦敦机房"
    )

    /**
     * 将两位的国家代码转为对应的国旗 Emoji (如 HK -> 🇭🇰, JP -> 🇯🇵)
     */
    fun countryCodeToEmoji(countryCode: String): String {
        val code = countryCode.trim().uppercase()
        if (code.length != 2) return "🌐"
        val c1 = code[0]
        val c2 = code[1]
        if (c1 !in 'A'..'Z' || c2 !in 'A'..'Z') return "🌐"
        val first = Character.toChars(0x1F1E6 + (c1 - 'A'))
        val second = Character.toChars(0x1F1E6 + (c2 - 'A'))
        return String(first) + String(second)
    }

    /**
     * 自动探测识别当前 Clash 节点（全兼容 FlClash、Clash Meta、Clash for Android 等）
     */
    suspend fun detect(store: Store): Result = withContext(Dispatchers.IO) {
        val proxyCfg = store.proxy()
        val candidateHosts = mutableListOf("127.0.0.1", "localhost")
        if (proxyCfg.host.isNotBlank() && proxyCfg.host !in candidateHosts) {
            candidateHosts.add(proxyCfg.host.trim())
        }

        // ── 阶段 1：尝试与本地 Clash/FlClash/Meta 控制器 REST API 通信 ──
        val candidatePorts = listOf(9090, 9097, 8188, 5678, 10191, 9091)
        for (host in candidateHosts) {
            for (port in candidatePorts) {
                val ctrlResult = probeController(host, port)
                if (ctrlResult != null) {
                    return@withContext ctrlResult
                }
            }
        }

        // ── 阶段 2：强制通过本地 Clash 代理端口 7890 探测（确保突破 VPN 规则分流直连限制） ──
        val localProxyPort = if (proxyCfg.port > 0) proxyCfg.port else 7890
        val proxyEgress = probeEgressViaProxy("127.0.0.1", localProxyPort)
        if (proxyEgress != null) {
            return@withContext proxyEgress
        }

        // ── 阶段 3：通过系统默认网络（VPN 虚拟网卡通道）探测真实出口 ──
        val directEgress = probeEgressDirect()
        if (directEgress != null) {
            return@withContext directEgress
        }

        // 无法识别
        Result(
            success = false,
            nodeName = "识别失败",
            detail = "未检测到运行中的 Clash 节点（请确认代理已开启）",
            isController = false,
        )
    }

    private fun probeController(host: String, port: Int): Result? {
        val url = "http://$host:$port/proxies"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()
            directClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                parseProxiesJson(body)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseProxiesJson(jsonStr: String): Result? {
        return try {
            val root = JSONObject(jsonStr)
            val proxies = root.optJSONObject("proxies") ?: return null

            // 优先搜索常见的主策略选择组（针对 Clash / FlClash / Clash Meta）
            val priorityKeys = listOf(
                "PROXY", "Proxy", "GLOBAL", "Global", "节点选择", "🚀 节点选择",
                "手动选择", "选择节点", "Auto", "自动选择"
            )

            for (key in priorityKeys) {
                val group = proxies.optJSONObject(key) ?: continue
                val now = group.optString("now").trim()
                if (now.isNotBlank() && now != "DIRECT" && now != "REJECT") {
                    return Result(
                        success = true,
                        nodeName = now,
                        detail = "Clash 策略组 [$key]",
                        isController = true,
                    )
                }
            }

            // 遍历所有 Selector 或 URLTest 组
            val keys = proxies.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val group = proxies.optJSONObject(key) ?: continue
                val type = group.optString("type")
                if (type.equals("Selector", ignoreCase = true) || type.equals("URLTest", ignoreCase = true)) {
                    val now = group.optString("now").trim()
                    if (now.isNotBlank() && now != "DIRECT" && now != "REJECT") {
                        return Result(
                            success = true,
                            nodeName = now,
                            detail = "Clash 策略组 [$key]",
                            isController = true,
                        )
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 强行指定走本地 127.0.0.1:port 代理通道探测（针对 FlClash 规则分流导致本地被 DIRECT 的场景）
     */
    private fun probeEgressViaProxy(proxyHost: String, proxyPort: Int): Result? {
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            val proxyClient = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(3000, TimeUnit.MILLISECONDS)
                .build()

            // 1. Cloudflare trace 必定被分流进代理链
            val req = Request.Builder()
                .url("https://cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", "curl/7.88.1")
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseCloudflareTrace(body)
                    if (parsed != null) return parsed
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 通过常规网络出口探测
     */
    private fun probeEgressDirect(): Result? {
        // 1. Cloudflare Trace (受保护海外域名，FlClash 规则模式必定走 Proxy)
        try {
            val req = Request.Builder()
                .url("https://cloudflare.com/cdn-cgi/trace")
                .header("User-Agent", "curl/7.88.1")
                .build()
            egressClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = parseCloudflareTrace(body)
                    if (parsed != null) return parsed
                }
            }
        } catch (_: Throwable) {
        }

        // 2. ip-api.com
        try {
            val req = Request.Builder()
                .url("http://ip-api.com/json?fields=status,message,country,countryCode,regionName,city,isp,query")
                .header("User-Agent", "curl/7.88.1")
                .build()
            egressClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val obj = JSONObject(body)
                    if (obj.optString("status") == "success") {
                        val countryCode = obj.optString("countryCode").trim().uppercase()
                        val country = obj.optString("country")
                        val city = obj.optString("city")
                        val isp = obj.optString("isp")
                        val ip = obj.optString("query")

                        if (countryCode.isNotBlank() && countryCode != "CN") {
                            val flag = countryCodeToEmoji(countryCode)
                            val cName = countryNames[countryCode] ?: country
                            val name = "$flag $cName ($countryCode)"
                            val detail = listOfNotNull(
                                city.takeIf { it.isNotBlank() },
                                isp.takeIf { it.isNotBlank() },
                                ip.takeIf { it.isNotBlank() }
                            ).joinToString(" · ")
                            return Result(
                                success = true,
                                nodeName = name,
                                detail = detail,
                                isController = false,
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun parseCloudflareTrace(body: String): Result? {
        val lines = body.lines()
        val map = mutableMapOf<String, String>()
        for (line in lines) {
            val idx = line.indexOf('=')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                map[k] = v
            }
        }
        val loc = map["loc"].orEmpty().uppercase()
        val ip = map["ip"].orEmpty()
        val colo = map["colo"].orEmpty().uppercase()

        if (loc.isNotBlank() && loc != "CN") {
            val flag = countryCodeToEmoji(loc)
            val cName = countryNames[loc] ?: loc
            val coloName = coloNames[colo] ?: if (colo.isNotBlank()) "$colo 机房" else ""
            val nodeName = listOfNotNull(
                "$flag $cName ($loc)",
                coloName.takeIf { it.isNotBlank() }
            ).joinToString(" · ")

            val detail = if (ip.isNotBlank()) "出口 IP: $ip" else "已连接代理节点"
            return Result(
                success = true,
                nodeName = nodeName,
                detail = detail,
                isController = false,
            )
        }
        return null
    }
}
