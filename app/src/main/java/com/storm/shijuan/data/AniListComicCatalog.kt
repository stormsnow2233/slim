package com.storm.shijuan.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object AniListComicCatalog {
    private const val ENDPOINT = "https://graphql.anilist.co"
    private val query = """
        query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String) {
          Page(page: ${'$'}page, perPage: ${'$'}perPage) {
            media(type: MANGA, isAdult: false, sort: POPULARITY_DESC, search: ${'$'}search) {
              title { userPreferred romaji english native }
              coverImage { large }
              description(asHtml: false)
              siteUrl
            }
          }
        }
    """.trimIndent()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun recommendations(): List<Comic> = fetch(null)

    fun search(keyword: String): List<Comic> = fetch(keyword)

    private fun fetch(search: String?): List<Comic> {
        val variables = JSONObject()
            .put("page", 1)
            .put("perPage", 24)
        if (!search.isNullOrBlank()) variables.put("search", search)
        val body = JSONObject()
            .put("query", query)
            .put("variables", variables)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("AniList 请求失败（HTTP ${response.code}）")
            val body = response.body?.string() ?: throw IOException("AniList 未返回内容")
            return parseResponse(body)
        }
    }

    internal fun parseResponse(json: String): List<Comic> {
        val root = JSONObject(json)
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            throw IOException("AniList：${errors.optJSONObject(0)?.optString("message").orEmpty()}")
        }
        val media = root.optJSONObject("data")
            ?.optJSONObject("Page")
            ?.optJSONArray("media")
            ?: throw IOException("AniList 返回的数据无法解析")

        return (0 until media.length()).mapNotNull { index ->
            val item = media.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optJSONObject("title")
            val name = sequenceOf("userPreferred", "english", "romaji", "native")
                .mapNotNull { title?.optString(it)?.takeIf(String::isNotBlank) }
                .firstOrNull()
                ?: return@mapNotNull null
            val url = item.optString("siteUrl").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            Comic(
                name = name,
                cover = item.optJSONObject("coverImage")?.optString("large").orEmpty(),
                intro = item.optString("description"),
                url = url,
                sourceId = "anilist",
                sourceName = "AniList",
            )
        }
    }

}
