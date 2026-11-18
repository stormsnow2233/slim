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

    private const val PAYLOAD = "+Tat6BsMfo6kVz9hqmIm4CDn4lpAFSNqC4EGstXo7X4H1uD5zAOdWk1amGgUFoCt0+JhIN6qGi2eWU5qlnF7iB57vQsHj2HUIj7kLOVSCO3ukAJsLjP6y7BCmXr+Ka+CLVKJTJOO3nHVL9lDZAGxILz0ge8vJ00JGzlVLB8gGawBTN097MYPjVldg4YaTZMAZhlH1zWVCvI+r6G0upEld/5sDk8LtBBnv9i5bbyt/fygm6zE03h3wRLxnTo8D4KnnYnNiM1x7fcpsW0rAqB1fLTDkVsthrdXLZV15JUL6wM2TGOrIIx+xbhXmdqM5OndTQs80EPsmdhyCt1TcF+vLCDmXOMFg2nVLn2nwCkDTAVOkTqbrgJiPNc+jwVf1Xa48bvSY+SvmoRVIdCU5Y9gcSLmzgUSMBe1XeWqGQEG/Yoq6mVLNDTNGyncCeaScFDDtgEdSZ3xGbs4et4yMFJNMqFB/8zvkNFcI3SLqFw+mP+lAea7UbbXoogbfQlK2i3vZp7M6+NCzrc+Jot/vVtc0x7e2cCOxa9YEJxpySOv2D96rpqcY2KBc196OccukhMlxqpN68Xqp4zkTrCFIfmxq745+YpZgMyMQ97fVRlyRzZ2Nd5FzSgFT82/HizqOz7talw0TLW19VTJ/W4ussElrTjXHLeL3g3J58h2uDZSA9HOUatzOU75Y3zauL7Z/3iMuO9uraHu4+GKLxW9JTFGSvlOICZoPxUIOKe0k26wFtSpidTKtg6vAjgdOxrrQiUuDC/JZO0Cti9WRUpyMBv2dr7bDk5Rbx9W2NJWuEitH9NYz7uzAxDRohglBiESE+F4N+TSKoNdh0MbjK0rlTCiad1UiwOmmHeNtYA9lHEN7M7C8dlyqKbjO5jo8tckY2ndauM+U2my0IFUfZMipguVe7MBH78KsJ03mXCjZpgoWD1BVMFxWxxu7STsEd8oPQX47jU5TycxUiEiXmtzjeT6QdQiJxK1W1U0LHhZ3LlOVslnYt3buBZCJEhqyqPZ0h7A1iHdnKmnlm5YXIKeaHTw9bHVI/96vrERBO75FiePHHrHcxqr7LXIIfSmD24aOcv027s3tNbZ4F05L94In52nE7Hf6D3Rd5094I6zf3CmykyQSf5OwHAZJ1RZaBCODx1xmEyIv53XZpV/Gm38iJF+gX1DDBtlhcgqs38eshigOfn3K09QIpVNlMEPwnUt2u1+sfyMRgtHeCxcWzmm7Ku9c1H6ySnsww/ut7JAhQMvdk0MMCnqWhsjtLhpcwOKJE194bONUgiOlFV1cN4aT9XnpFtRBfrbp6EFD0Z4ARVjXANQfV9A8L0U8s0Ih3m9VWg8MYJfKEesmSfySumCBMRI/GGwMVvlqNTAPGc4aL+zEcs/7rtPi/gRNR5FTKodukthedlQmWaWMpozHo3mA9Ha81XalWbfAB/BD0F7c3fGDzFxByfXX1x0QB64T7tNJfUYFFD0PLEaY/VSBn9IE+0oVB3z0nlbEOXjHsEynQa86pB1h24r87FhDC/VkXr9aBb82czcVU3Hpe5EsHjaX6zjsrU8rUPebjqpe1jWO2AFoLZdtyYc39K/ZGVht3fJ5SrOg673sliC4NZXGdHMy9i9LdztTwYuT73bURSJOtSI+Z57+N9llrZtn4ErLjBcvWu921ANBc11wJi/dzEMOLcmwXZu9ZJ8ujW+aT1ReJIwadLV0z0Do2yt3rvpmcaCr/cpftmm/5UOso5Vn3AHOKl0f5MiNTgl73tLdbQrzprqt2YtHOxjU+YetinN3FCQykPYm1WAae33rgT0tKP9RXh9DquBNI64E9+57ujjHOX4JNG5DObB7AUTpR4aJUJ4YZvtHnTWBgYM4eWy0c7DvFJY41gi5frp6jeeASSgeexa6f9Tdum0vZJ7A7YdSR8cAsWMfYm2xg8fpgbiLO2OrDo7G+mSKRgqRcm4RDLTreFnjGXMvjm2OyTD4ayDVvVonl3IF+CvLDl8Ok77/fiUw1SCSxIJ0JiBB6Ndl9ggG6pXxLIBHdqkwhmHlBgQ56tl2nnNygYz9/R60WBtH8NhhSxYL53tcQXvxhP/E2MaontBUVUJ+2eEvyXNcxANNZ4i/+U1i4CwIbTqJ6v2ng9/5+XuFdgdF7O27UrVILZhgoDM80RwLxTIY+Q1nEIKx1tGxwiu+dkviel3S3GhndR0buZ50Idk4Tck9sSCQ//VMz8wJEtWq6mjK6/VswUj96oBlN291XoPDTHIpsweRyBP2/GMOeV65gBfjqo3lQ5dVpw3V+ICwhryiK2p2OX3nylHJzEqOaT8BLsUST08MbdfGVVH2Iat2KzFeMNokbRplUE8oxGlxzHLnINmKMmn0olXsuCbsjiUY20eAYHgxo+J9SxgC5SjZXyo6L0FW6kYpY7PnTE4H72N7BKW7miIt/sQqqNt+gprwTYlytJAYaJYIc9zVGsTg3ksI77iWe0xvLeSykJtWV2J45zVJb/SkTYhGizvZmYTaSTImoxcig04SRvR5ORcVRDqJ1H5bp775k7E4fQ0urE1IPsvRRQ4zQli5a08hMx1ld5WD+T9rjP09AVXXgctauVBSmmqaiA6F+FigGUcDKCziNB1FhaeuUkQ9nVh9YXkK0jvZa1H76n0t2SEKcJinWFxAUjwnO5DbN7f2vr5GHARlYXDxwO7Qn7aTPC1RU6K760nx+DcwyNv3cP3tUo681HaN1OUaxQhkDdEdfpmvDa5gt9E8vAFCoWoPS/um68UP873AtY6VWQ5nshCxY0v3tiZ8C0AR71iW2Lt78373JSWF412rQ8Lp3/fsN4x0aVdPdNtJ9Ylzqa6GVqKsjpmRMn2ipYCrlvtMJ92SJGZrYPk/KbZL5gpgnw7MQuJM0FnylfvWn0+t2mIp177sluj+6BABzySBOTsmIEXMUo4zZRI75yzf0ieCz6TLn1kHUl2YTbRv9cs/475spU5dKroweRJzIM/KNjc7MePf0g2wAMO9hSURvLet9eHhMheaMr84ndraB3sD7wmRNkDmddhl9megXOF0RhySKuhNDqYpu3DEfM0m7PwsmqRk39cAvjRVZJaZxCRuSsN1eV2QZi/TtoTbdvR4X39wCCwyRR22GclFeVLWoxnwjdFaC7xcBfY3hLWOizU9PgNACLsXl34QXSyZey5BPxDzPTzZdKb3/YdlGjAs5/T0Sg5MuspaeItKDj6dscY7HtA4OUUNVgVV6nvecQMxPy67gzrxvn3eMcFJ+uNOOeYdzFcAoBRLYwVUfxlejpF0E+d4pugFuSBg/2kttA6tXVySptzU8KXeO3tL44BitxsDzy205d6a8ZtiIrY5vmFTsq7wCiVR/kzP7Kb8buTmxNeo62pjMpeRvmW9i7VdVqHx/ykK3QyLphaQjNhN1kihZ5y5+rfR0680fKe7keHh9bcJLgNOgVV6Ed38hXtju6u2nC5i/UUoN9r6DXFYLCf+Ys5324v61pI9svd+dhBW5IfnOu+8tKAja8Zoit/7dfNvGNtDxfYggTWrN0hLq0VHGrwJuCqgrfFwOMvDHBUFqkNWIf9z4knyChLVCX8A1wBQzZSXydaVnlELb0i2GSHBEKj/aELB8NONhob5ELj/2K0cvAwaL/1zGPzg+4vnmE2gGR3iEAIu9uU9/t1bqNyHRocKR7ue+iQjBwOc/fZRci4wCiBaZO9D7JFgLP7BJZRNKkxT9bl4qqoQQHb9mnWllzXryLh56z/Nw98vH1CcK9otzLfSZ5pR/s2KRXV2yb/Fc/pAlE2O3FYZdxsAJstdfFeM4R3Jp/iIxxkxFXdAMP//ls4bh5u9y4hadQSn+Bec2QUp6wepADPzvPp13dYqG+GBddW+3vUheJlXXBRz4o6fQ1vHQeqcQB8yyxOyzB/+PBNvqoNwSawQvemAzYL1HoAy/ExgS+YMNnVAvRSLnGo7tUwKlyxFGCiEck7r3rHlxkcHo5LWn2vHfaBhSHa9dxHB2atqw3ZuXVtLud8lr8tgy+vu4Pz1fDfYZFMXP7nV/Z7JBHz6VE4y0E9oDM0EEeL/opYKG6/iaIwS1xfp2MiXbTKvM/Zr9WN9wc/wOY1kHqui3+kta/5lXsHhI6svnLSPja4nHPtAvPItVmMl5uJDdxrhKhDPmy88zauXHey8tbT5Z8u4A8MfTRCY4puxfarq2YmmA=="

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
