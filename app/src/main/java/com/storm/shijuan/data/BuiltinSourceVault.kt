package com.storm.shijuan.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 内置官方书源加密保险箱。
 * 对精心适配逆向挖掘出的源站规则、防屏蔽头与直通路径进行 AES-128-CBC 强力混淆加密，
 * 防止被反编译逆向工具提取明文规则与域名。
 */
object BuiltinSourceVault {

    private val K_PARTS = byteArrayOf(
        0x53, 0x68, 0x69, 0x6a, 0x75, 0x61, 0x6e, 0x53,
        0x65, 0x63, 0x72, 0x65, 0x74, 0x32, 0x30, 0x32,
    )
    private val IV_PARTS = byteArrayOf(
        0x53, 0x74, 0x6f, 0x72, 0x6d, 0x53, 0x6e, 0x6f,
        0x77, 0x49, 0x56, 0x5f, 0x32, 0x30, 0x32, 0x36,
    )

    private const val PAYLOAD = "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"

    fun load(): List<Source> {
        return runCatching {
            val encryptedBytes = runCatching {
                java.util.Base64.getDecoder().decode(PAYLOAD)
            }.getOrElse {
                android.util.Base64.decode(PAYLOAD, android.util.Base64.DEFAULT)
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(K_PARTS, "AES")
            val ivSpec = IvParameterSpec(IV_PARTS)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val compressed = cipher.doFinal(encryptedBytes)

            val jsonStr = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(jsonStr)
            val list = ArrayList<Source>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(parseSource(o))
            }
            list
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
    }

    private fun parseSource(o: JSONObject): Source {
        val searchObj = o.optJSONObject("search")
        val searchRule = if (searchObj != null) {
            val fieldsMap = mutableMapOf<String, String>()
            searchObj.optJSONObject("fields")?.let { fo ->
                fo.keys().forEach { k -> fieldsMap[k] = fo.optString(k) }
            }
            val extraUrlsList = mutableListOf<String>()
            searchObj.optJSONArray("extraUrls")?.let { eu ->
                for (j in 0 until eu.length()) extraUrlsList.add(eu.optString(j))
            }
            SearchRule(
                url = searchObj.optString("url"),
                method = searchObj.optString("method", "GET"),
                body = searchObj.optString("body"),
                list = searchObj.optString("list"),
                fields = fieldsMap,
                auto = searchObj.optBoolean("auto", false),
                extraUrls = extraUrlsList,
                keyCharset = searchObj.optString("keyCharset", "utf-8"),
            )
        } else null

        val detailObj = o.optJSONObject("detail")
        val detailRule = if (detailObj != null) {
            DetailRule(
                name = detailObj.optString("name"),
                author = detailObj.optString("author"),
                cover = detailObj.optString("cover"),
                intro = detailObj.optString("intro"),
            )
        } else null

        val tocObj = o.optJSONObject("toc")
        val tocRule = if (tocObj != null) {
            TocRule(
                list = tocObj.optString("list"),
                name = tocObj.optString("name", "@text"),
                url = tocObj.optString("url", "@href"),
                page = tocObj.optString("page"),
                link = tocObj.optString("link"),
                skip = tocObj.optString("skip"),
                volume = tocObj.optString("volume"),
                skipVolume = tocObj.optString("skipVolume"),
                nextPage = tocObj.optString("nextPage"),
                maxPages = tocObj.optInt("maxPages", 50),
            )
        } else null

        val contentObj = o.optJSONObject("content")
        val contentRule = if (contentObj != null) {
            ContentRule(
                body = contentObj.optString("body"),
                next = contentObj.optString("next"),
            )
        } else null

        val reqObj = o.optJSONObject("requestProfile")
        val reqProfile = if (reqObj != null) {
            val ehMap = mutableMapOf<String, String>()
            reqObj.optJSONObject("extraHeaders")?.let { eho ->
                eho.keys().forEach { k -> ehMap[k] = eho.optString(k) }
            }
            SourceRequestProfile(
                userAgent = reqObj.optString("userAgent"),
                accept = reqObj.optString("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                referer = reqObj.optString("referer"),
                cookie = reqObj.optString("cookie"),
                deviceType = reqObj.optString("deviceType", "AUTO"),
                extraHeaders = ehMap,
            )
        } else null

        val mirrorsList = mutableListOf<String>()
        o.optJSONArray("mirrors")?.let { ma ->
            for (j in 0 until ma.length()) mirrorsList.add(ma.optString(j))
        }

        return Source(
            id = o.optString("id"),
            name = o.optString("name"),
            group = o.optString("group"),
            base = o.optString("base"),
            mirrors = mirrorsList,
            charset = o.optString("charset", "utf-8"),
            status = o.optString("status", "local"),
            note = o.optString("note"),
            search = searchRule,
            detail = detailRule,
            toc = tocRule,
            content = contentRule,
            apiKind = o.optString("apiKind"),
            enabledByDefault = o.optBoolean("enabledByDefault", false),
            probeKeyword = o.optString("probeKeyword"),
            probeUrl = o.optString("probeUrl"),
            probeMode = o.optString("probeMode", "SEARCH"),
            userAgent = o.optString("userAgent"),
            renderMode = o.optString("renderMode", "HTTP"),
            requestProfile = reqProfile,
        )
    }
}
