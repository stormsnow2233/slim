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
