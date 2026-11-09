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
