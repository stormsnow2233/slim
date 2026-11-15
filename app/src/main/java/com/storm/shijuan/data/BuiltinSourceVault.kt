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
