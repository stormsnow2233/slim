package com.storm.shijuan.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.storm.shijuan.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val hasUpdate: Boolean,
)

object UpdateManager {

    private const val GITHUB_API = "https://api.github.com/repos/stormsnow2233/slim/releases/latest"
    private const val GITHUB_RELEASES_PAGE = "https://github.com/stormsnow2233/slim/releases/latest"

    /** 检查 GitHub 最新 Release */
    suspend fun checkUpdate(currentVersion: String = BuildConfig.VERSION_NAME): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 优先尝试直连 GitHub API
                val resp = Net.get(
                    GITHUB_API,
                    headers = mapOf(
                        "Accept" to "application/vnd.github.v3+json",
                        "User-Agent" to "slim-android",
                    ),
                )
                if (!resp.ok) {
                    // 若 API 无法访问（如国内网络阻断或触发 GitHub API 60次/小时频控），回退解析 Releases 跳转页
                    val pageResp = Net.get(GITHUB_RELEASES_PAGE)
                    if (pageResp.ok) {
                        val finalUrl = pageResp.via
                        val tag = if (finalUrl.contains("/tag/")) finalUrl.substringAfterLast('/') else ""
                        if (tag.isNotBlank()) {
                            val cleanVer = tag.removePrefix("v").removePrefix("V").trim()
                            val apkUrl = "https://github.com/stormsnow2233/slim/releases/download/$tag/_$cleanVer.apk"
                            val hasUp = isNewer(cleanVer, currentVersion.removePrefix("v").removePrefix("V").trim())
                            return@runCatching ReleaseInfo(
                                tagName = tag,
                                versionName = cleanVer,
                                title = tag,
                                changelog = "拾卷最新发布版本 $tag",
                                downloadUrl = apkUrl,
                                hasUpdate = hasUp,
                            )
                        }
                    }
                    throw IllegalStateException(resp.error.ifBlank { "检查更新失败 (HTTP ${resp.code})" })
                }
                val json = JSONObject(resp.body)
                val tagName = json.optString("tag_name").trim()
                val title = json.optString("name").ifBlank { tagName }
                val body = json.optString("body")
                val cleanVersion = tagName.removePrefix("v").removePrefix("V").trim()

                // 寻找 .apk 下载链接
                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apkUrl.isBlank()) {
                    apkUrl = json.optString("html_url")
                }

                val hasUpdate = isNewer(cleanVersion, currentVersion.removePrefix("v").removePrefix("V").trim())
                ReleaseInfo(
                    tagName = tagName,
                    versionName = cleanVersion,
                    title = title,
                    changelog = body,
                    downloadUrl = apkUrl,
                    hasUpdate = hasUpdate,
                )
            }
        }

    /** 比较语义化版本号：v1 > v2 返回 true */
    fun isNewer(v1: String, v2: String): Boolean {
        if (v1.isBlank() || v2.isBlank()) return false
        val p1 = v1.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val p2 = v2.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val num1 = p1.getOrElse(i) { 0 }
            val num2 = p2.getOrElse(i) { 0 }
            if (num1 > num2) return true
            if (num1 < num2) return false
        }
        return false
    }

    /** 下载更新 APK 到应用私有缓存目录，并通过进度回调通知 UI */
    suspend fun downloadApk(
        ctx: Context,
        url: String,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val targetUrl = if (url.startsWith("https://github.com/")) {
                "https://ghproxy.net/$url"
            } else {
                url
            }
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val dest = File(dir, "update.apk")
            if (dest.exists()) dest.delete()

            var ok = downloadFile(targetUrl, dest, onProgress)
            if (!ok && targetUrl != url) {
                ok = downloadFile(url, dest, onProgress)
            }
            if (!ok || !dest.exists() || dest.length() < 1024) {
                dest.delete()
                throw IllegalStateException("APK 下载失败或安装包损坏")
            }
            dest
        }
    }

    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Boolean = runCatching {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", Net.UA)
            .build()
        val clientBuilder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        Net.applyProxy(clientBuilder)
        val client = clientBuilder.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            val total = body.contentLength()
            var downloaded = 0L
            val buf = ByteArray(32 * 1024)
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            true
        }
    }.getOrElse {
        dest.delete()
        false
    }

    /** 唤起系统 PackageInstaller 安装更新 */
    fun installApk(ctx: Context, apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        runCatching {
            ctx.startActivity(intent)
        }
    }
}
