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
