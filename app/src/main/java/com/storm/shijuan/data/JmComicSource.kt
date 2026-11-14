package com.storm.shijuan.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import coil.size.Size
import coil.transform.Transformation
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 禁漫天堂图片切片混淆还原算法
 */
object JmImageDescrambler {
    const val SCRAMBLE_ID = 220980

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val b = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(b.size * 2)
        for (byte in b) {
            val h = (byte.toInt() and 0xFF).toString(16)
            if (h.length == 1) sb.append('0')
            sb.append(h)
        }
        return sb.toString()
    }

    fun parseSrc(src: String): Pair<Int, String>? {
        val s = src.substringBefore("?")
        val m = Regex("""/media/photos/(\d+)/([^/]+)$""").find(s) ?: return null
        val aid = m.groupValues[1].toIntOrNull() ?: return null
        val fnWithExt = m.groupValues[2]
        val fn = fnWithExt.substringBeforeLast(".")
        return Pair(aid, fn)
    }

    fun segNum(aid: Int, fn: String): Int {
        if (aid < SCRAMBLE_ID) return 0
        if (aid < 268850) return 10
        val x = if (aid < 421926) 10 else 8
        val h = md5("$aid$fn")
        val c = h.last().code
        return (c % x) * 2 + 2
    }

    fun descramble(bmp: Bitmap, src: String): Bitmap {
        val parsed = parseSrc(src) ?: return bmp
        val num = segNum(parsed.first, parsed.second)
        if (num < 2) return bmp

        val w = bmp.width
        val h = bmp.height
        if (h < num) return bmp

        return try {
            val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dest)
            val paint = Paint().apply {
                isAntiAlias = false
                isFilterBitmap = false
            }

            val move = h / num
            val over = h % num
            for (i in 0 until num) {
                var step = move
                val ys = h - (move * (i + 1)) - over
                var yd = move * i
                if (i == 0) {
                    step = move + over
                } else {
                    yd += over
                }
                val sRect = Rect(0, ys, w, ys + step)
                val dRect = Rect(0, yd, w, yd + step)
                canvas.drawBitmap(bmp, sRect, dRect, paint)
            }
            dest
        } catch (e: Throwable) {
            bmp
        }
    }

    fun descrambleFile(file: java.io.File, src: String) {
        if (!file.exists() || file.length() <= 0) return
        val parsed = parseSrc(src) ?: return
        val num = segNum(parsed.first, parsed.second)
        if (num < 2) return
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return
        val fixed = descramble(bmp, src)
        if (fixed !== bmp) {
            runCatching {
                file.outputStream().use { out ->
                    fixed.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
            bmp.recycle()
            fixed.recycle()
        }
    }
}

/**
 * Coil 图片切片解密变换
 */
class JmDescrambleTransformation(private val src: String) : Transformation {
    override val cacheKey: String = "jm_descramble_$src"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        return JmImageDescrambler.descramble(input, src)
    }
}

/**
 * 禁漫天堂 (JMcomic) 漫画源实现
 */
class JmComicSource : ComicSource {
    override val id: String = "jm"
    override val name: String = "JMcomic"

    private val hosts = listOf(
        "https://jm-proxy.us.ci",
        "https://jm-proxy.storm-snow.workers.dev",
        "https://www.cdnhth.club",
        "https://www.jmapiproxy1.cc",
        "https://www.jmapiproxy2.cc"
    )
    @Volatile
    private var liveHost = hosts.first()

    private val key = "880c64833265ad47a928afcf1b1220f5".toByteArray(Charsets.UTF_8)
    private val token = "1c6fa345eea2e10d5a30880ec2a7e0b3"
    private val tokenParam = "1700000000,2.0.20"
    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val fallbackImgHosts = listOf(
        "https://cdn-msp3.jmapiproxy1.cc",
        "https://cdn-msp.jmdanjonproxy.vip",
        "https://cdn-msp.jmapiproxy1.cc"
    )
    @Volatile
    private var cachedImgHost: String = fallbackImgHosts[0]

    override fun headers(): Map<String, String> = mapOf(
        "token" to token,
        "tokenparam" to tokenParam,
        "User-Agent" to userAgent,
        "Referer" to "$liveHost/",
        "X-JM" to "1",
    )

    fun imageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "$liveHost/",
        "X-JM" to "1",
    )

    private fun decryptAes(b64: String): String {
        return runCatching {
            val data = runCatching {
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            }.getOrElse {
                java.util.Base64.getDecoder().decode(b64)
            }
            val keySpec = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun unwrap(body: String): String {
        if (body.isBlank()) return ""
        val trimmed = body.trim()
        val jsonStart = trimmed.indexOf('{')
        if (jsonStart < 0) return ""
        val jsonStr = trimmed.substring(jsonStart)
        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return ""
        if (obj.optInt("code", 200) != 200) return ""
        val data = obj.opt("data") ?: return ""
        return when (data) {
            is String -> decryptAes(data)
            is JSONObject -> data.toString()
            is JSONArray -> data.toString()
            else -> data.toString()
        }
    }

    private fun getApi(path: String): String {
        val order = listOf(liveHost) + hosts.filter { it != liveHost }
        for (h in order) {
            val url = if (path.startsWith("http")) path else "$h$path"
            val resp = Net.get(url, headers = headers(), referer = "$h/", useCache = false, fast = true)
            if (resp.ok && resp.body.isNotBlank()) {
                val unwrapped = unwrap(resp.body)
                if (unwrapped.isNotBlank()) {
                    liveHost = h
                    return unwrapped
                }
            }
        }
        return ""
    }

    fun getImgHost(): String {
        if (cachedImgHost.isNotBlank() && cachedImgHost != fallbackImgHosts[0]) {
            return cachedImgHost
        }
        val setting = getApi("/setting")
        if (setting.isNotBlank()) {
            val obj = runCatching { JSONObject(setting) }.getOrNull()
            val host = obj?.optString("img_host").orEmpty()
            if (host.isNotBlank()) {
                cachedImgHost = host
                return host
            }
        }
        return cachedImgHost.ifBlank { fallbackImgHosts[0] }
    }

    override fun home(): List<Comic> {
        return exploreCategory("mr")
    }

    /** 探索分类，例如 "doujin", "single", "short", "hanman", "meiman", "another", "3D", "another_cosplay", "mr", "mv", "tf" */
    fun exploreCategory(catKey: String, page: Int = 1): List<Comic> {
        val path = when (catKey) {
            "mv" -> "/categories/filter?page=$page&order=&c=&o=mv"
            "tf" -> "/categories/filter?page=$page&order=&c=&o=tf"
            "mr" -> "/categories/filter?page=$page&order=&c=&o=mr"
            else -> "/categories/filter?page=$page&order=&c=$catKey&o=mr"
        }
        val raw = getApi(path)
        return parseComicList(raw)
    }

    override fun search(keyword: String): List<Comic> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        val path = "/search?page=1&o=mr&search_query=$kw&main_tag=0"
        val raw = getApi(path)
        return parseComicList(raw)
    }

    private fun parseComicList(jsonStr: String): List<Comic> {
        if (jsonStr.isBlank()) return emptyList()
        val imgHost = getImgHost()
        val out = mutableListOf<Comic>()
        runCatching {
            val arr: JSONArray = if (jsonStr.trim().startsWith("[")) {
                JSONArray(jsonStr)
            } else {
                val obj = JSONObject(jsonStr)
                obj.optJSONArray("content") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val name = item.optString("name").trim()
                var author = item.optString("author").trim()
                if (author == "N/A" || author == "null") author = ""
                val cat = item.optJSONObject("category")?.optString("title").orEmpty()
                val subCat = item.optJSONObject("category_sub")?.optString("title").orEmpty()
                val kind = listOf(cat, subCat).filter { it.isNotBlank() }.joinToString("/")
                val intro = item.optString("description").trim()
                val cover = "$imgHost/media/albums/${id}_3x4.jpg"
                val latest = if (kind.isNotBlank()) kind else "JMcomic"
                out.add(
                    Comic(
                        name = name,
                        cover = cover,
                        author = author.ifBlank { "JM" },
                        intro = intro,
                        latest = latest,
                        url = "$liveHost/album?id=$id",
                        sourceId = "jm",
                        sourceName = "JMcomic",
                        status = "完结",
                        pathWord = id,
                        detailed = false,
                    )
                )
            }
        }
        return out
    }

    override fun detail(comic: Comic): Comic {
        val aid = comic.pathWord.ifBlank {
            Regex("""(?:id=|album/)(\d+)""").find(comic.url)?.groupValues?.get(1).orEmpty()
        }
        if (aid.isBlank()) return comic
        val raw = getApi("/chapter?id=$aid")
        if (raw.isBlank()) return comic
        return runCatching {
            val obj = JSONObject(raw)
            val name = obj.optString("name").ifBlank { comic.name }
            val authorArr = obj.optJSONArray("author")
            val author = if (authorArr != null && authorArr.length() > 0) {
                (0 until authorArr.length()).joinToString("/") { authorArr.optString(it) }
            } else comic.author
            val intro = obj.optString("description").ifBlank { comic.intro }
            val series = obj.optJSONArray("series")
            val totalPhotos = obj.optInt("total_photos", 0)
            val latest = if (series != null && series.length() > 0) {
                "共 ${series.length()} 话"
            } else if (totalPhotos > 0) {
                "共 $totalPhotos 页"
            } else comic.latest
            comic.copy(
                name = name,
                author = author,
                intro = intro,
                latest = latest,
                detailed = true,
            )
        }.getOrDefault(comic)
    }

    override fun chapters(comic: Comic): List<ComicChapter> {
        val aid = comic.pathWord.ifBlank {
            Regex("""(?:id=|album/)(\d+)""").find(comic.url)?.groupValues?.get(1).orEmpty()
        }
        if (aid.isBlank()) return emptyList()
        val raw = getApi("/chapter?id=$aid")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val obj = JSONObject(raw)
            val series = obj.optJSONArray("series")
            val out = mutableListOf<ComicChapter>()
            if (series != null && series.length() > 0) {
                for (i in 0 until series.length()) {
                    val s = series.optJSONObject(i) ?: continue
                    val sid = s.optString("id")
                    if (sid.isBlank()) continue
                    val sname = s.optString("name").ifBlank { "第 ${s.optString("sort", "${i + 1}")} 话" }
                    out.add(
                        ComicChapter(
                            name = sname,
                            url = "$liveHost/chapter?id=$sid",
                            index = i,
                            sourceId = "jm",
                        )
                    )
                }
            } else {
                val sname = obj.optString("name").ifBlank { "全一话" }
                out.add(
                    ComicChapter(
                        name = sname,
                        url = "$liveHost/chapter?id=$aid",
                        index = 0,
                        sourceId = "jm",
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val cid = Regex("""(?:id=|chapter/)(\d+)""").find(chapter.url)?.groupValues?.get(1)
            ?: chapter.url.filter { it.isDigit() }.ifBlank { comic.pathWord }
        val raw = getApi("/chapter?id=$cid")
        if (raw.isBlank()) return ComicPages(chapter.name, emptyList(), "$liveHost/")
        return runCatching {
            val obj = JSONObject(raw)
            val imagesArr = obj.optJSONArray("images") ?: JSONArray()
            val imgHost = getImgHost()
            val imgs = mutableListOf<String>()
            for (i in 0 until imagesArr.length()) {
                val fn = imagesArr.optString(i)
                if (fn.isNotBlank()) {
                    imgs.add("$imgHost/media/photos/$cid/$fn")
                }
            }
            ComicPages(
                title = chapter.name,
                images = imgs,
                referer = "$liveHost/",
                headers = imageHeaders(),
            )
        }.getOrDefault(ComicPages(chapter.name, emptyList(), "$liveHost/"))
    }
}
