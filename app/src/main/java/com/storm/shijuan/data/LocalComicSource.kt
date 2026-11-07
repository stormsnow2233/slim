package com.storm.shijuan.data

import org.json.JSONObject
import java.io.File

/**
 * 本地导入漫画源（EPUB 漫画 / PDF 漫画 / 本地图片包）
 */
class LocalComicSource : ComicSource {
    override val id: String = "local"
    override val name: String = "本地导入"

    override fun home(): List<Comic> = emptyList()

    override fun search(keyword: String): List<Comic> = emptyList()

    override fun detail(comic: Comic): Comic = comic

    override fun chapters(comic: Comic): List<ComicChapter> {
        val path = comic.url.removePrefix("file://")
        val dir = File(path)
        val manifest = File(dir, "manifest.json")
        if (!manifest.exists()) return emptyList()
        return runCatching {
            val json = JSONObject(manifest.readText())
            val arr = json.optJSONArray("chapters") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ComicChapter(
                    name = o.optString("name", "第 ${i + 1} 话"),
                    url = o.optString("url", "ch_$i"),
                    index = i,
                    sourceId = "local",
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun pages(comic: Comic, chapter: ComicChapter): ComicPages {
        val path = comic.url.removePrefix("file://")
        val dir = File(path)
        val manifest = File(dir, "manifest.json")
        if (!manifest.exists()) return ComicPages(images = emptyList(), referer = "")
        return runCatching {
            val json = JSONObject(manifest.readText())
            val arr = json.optJSONArray("chapters") ?: return ComicPages(images = emptyList(), referer = "")
            val chObj = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("url") == chapter.url }
                ?: arr.optJSONObject(chapter.index)
            val imgArr = chObj?.optJSONArray("images") ?: return ComicPages(images = emptyList(), referer = "")
            val list = (0 until imgArr.length()).map { imgArr.getString(it) }
            ComicPages(images = list, referer = "", headers = emptyMap())
        }.getOrDefault(ComicPages(images = emptyList(), referer = ""))
    }

    override fun headers(): Map<String, String> = emptyMap()
}
