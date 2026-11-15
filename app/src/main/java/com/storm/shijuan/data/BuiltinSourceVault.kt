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
