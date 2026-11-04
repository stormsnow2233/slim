package com.zaka.shijuan.data

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 统一网络层：处理编码、UA、Referer、重试 */
object Net {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    data class Resp(val ok: Boolean, val code: Int, val body: String, val error: String = "", val via: String = "")

    /** 按站点编码解码（GBK 站点很常见，必须处理） */
    private fun decode(bytes: ByteArray, charset: String, contentType: String?): String {
        var cs = charset
        if (cs == "auto" || cs.isBlank()) cs = "utf-8"
        // 先从 meta 里嗅探
        if (cs == "utf-8") {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val m = Regex("charset=[\"']?([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(
                head + (contentType ?: "")
            )
            if (m != null) {
                val sniff = m.groupValues[1].lowercase()
                if (sniff.contains("gb")) cs = "gbk"
                else if (sniff.contains("big5")) cs = "big5"
            }
        }
        val javaCharset = when {
            cs.contains("gb") -> Charset_gbk
            cs.contains("big5") -> charset("big5")
            else -> charset("utf-8")
        }
        return runCatching { String(bytes, javaCharset) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private val Charset_gbk = charset("gbk")

    fun get(
        url: String,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        val builder = Request.Builder().url(safeUrl(url)).header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return exec(builder.build(), charset)
    }

    fun post(
        url: String,
        body: Map<String, String>,
        charset: String = "utf-8",
        referer: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Resp {
        val fb = FormBody.Builder()
        body.forEach { (k, v) -> fb.add(k, v) }
        val builder = Request.Builder().url(safeUrl(url))
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(fb.build())
        if (referer.isNotBlank()) builder.header("Referer", referer)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return exec(builder.build(), charset)
    }

    fun getBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(safeUrl(url)).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    }.getOrNull()

    private fun exec(request: Request, charset: String, depth: Int = 0): Resp = try {
        client.newCall(request).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val text = decode(bytes, charset, r.header("Content-Type"))
            val finalUrl = r.request.url.toString()
            if (!r.isSuccessful) return Resp(false, r.code, text, "HTTP ${r.code}", finalUrl)

            // 有些站用 meta / JS 做跳转，普通 HTTP 客户端不会跟，这里手动跟一层
            if (depth < 2 && text.length < 3000) {
                val jump = findMetaRedirect(text)
                if (jump != null && jump != finalUrl) {
                    val abs = Rules.absUrl(finalUrl, jump)
                    if (abs.startsWith("http")) {
                        val again = Request.Builder().url(abs)
                            .header("User-Agent", UA)
                            .header("Referer", finalUrl)
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .build()
                        return exec(again, charset, depth + 1)
                    }
                }
            }
            Resp(true, r.code, text, via = finalUrl)
        }
    } catch (e: Exception) {
        Resp(false, 0, "", friendlyError(e))
    }

    /** 识别「文档已移动」「http-equiv=refresh」「location.href=」这类跳转 */
    private fun findMetaRedirect(html: String): String? {
        Regex("(?i)http-equiv=[\"']?refresh[\"']?[^>]*content=[\"'][^;\"']*;\\s*url=([^\"']+)"  )
            .find(html)?.let { return it.groupValues[1].trim() }
        Regex("(?i)url=([^\"'&>]+)").find(html)?.let {
            val v = it.groupValues[1].trim()
            if (v.startsWith("http") || v.startsWith("/")) return v
        }
        Regex("(?i)href=[\"']([^\"']+)[\"']").find(html)?.let { return it.groupValues[1] }
        return null
    }

    fun friendlyError(e: Throwable): String {
        val msg = (e.message ?: "").lowercase()
        return when {
            msg.contains("timeout") || msg.contains("timed out") -> "连接超时"
            msg.contains("unable to resolve") || msg.contains("nodename") -> "域名解析失败"
            msg.contains("ssl") || msg.contains("certificate") -> "证书错误"
            msg.contains("refused") -> "连接被拒绝"
            msg.contains("reset") -> "连接被重置"
            else -> e.javaClass.simpleName
        }
    }

    private fun safeUrl(u: String): String {
        var s = u.trim()
        if (!s.startsWith("http")) s = "https://$s"
        return s.replace(" ", "%20")
    }

    /** 简单并发下载（给体积不大的页面用） */
    fun parallel(urls: List<String>, charset: String = "utf-8"): List<Resp> {
        val out = arrayOfNulls<Resp>(urls.size)
        val threads = urls.mapIndexed { i, u ->
            Thread { out[i] = get(u, charset) }.apply { start() }
        }
        threads.forEach { runCatching { it.join(20000) } }
        return out.map { it ?: Resp(false, 0, "", "未返回") }
    }

    fun readAll(input: java.io.InputStream): ByteArray {
        val bo = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            bo.write(buf, 0, n)
        }
        return bo.toByteArray()
    }
}
