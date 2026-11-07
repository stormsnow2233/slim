package com.storm.shijuan.data

/**
 * 解漫画站页面里的「JS 打包壳」。
 *
 * 这类站点（动漫屋 / MangaBZ 一族）把章节图片地址塞进一段
 * `eval(function(p,a,c,k,e,d){…}('payload',a,c,'dict'.split('|'),0,{}))` 里，
 * 直接看 HTML 抓不到图。这里复刻那段解包逻辑：
 *   1. 从 `}(` 后面按顺序读 payload / a / c / 字典（自己解析，不靠正则猜边界）
 *   2. 按 base=a 把 0..c-1 编成 token（与 JS 的 e() 完全一致）
 *   3. 把 payload 里所有 \w+ 按 token→字典项 替换
 *
 * 纯本地字符串运算，不执行任何 JS。
 */
object JsPacker {

    /** 从整页 HTML 里把所有打包块解开，返回其中第一组图片地址 */
    fun imagesFromHtml(html: String): List<String> {
        var from = 0
        while (true) {
            val at = html.indexOf("}(", from)
            if (at < 0) return emptyList()
            from = at + 2
            val call = parseCall(html, at + 2) ?: continue
            val imgs = extractNewImgs(call)
            if (imgs.isNotEmpty()) return imgs
        }
    }

    private class Call(val payload: String, val a: Int, val c: Int, val dict: List<String>) {
        var dictLen = 0
    }

    /** 解析 }('payload',a,c,'dict'… 这一段；不是打包调用就返回 null */
    private fun parseCall(s: String, start: Int): Call? {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != '\'') return null

        val payload = readString(s, i) ?: return null
        i = payload.second

        i = skipComma(s, i) ?: return null
        val a = readInt(s, i) ?: return null
        i = skipComma(s, a.second) ?: return null
        val c = readInt(s, i) ?: return null
        i = skipComma(s, c.second) ?: return null

        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != '\'') return null
        val dict = readString(s, i) ?: return null

        // 后面应该紧跟 .split(
        val tail = s.substring(dict.second, minOf(s.length, dict.second + 24)).trimStart()
        if (!tail.startsWith(".split")) return null

        return Call(payload.first, a.first, c.first, dict.first.split('|'))
    }

    private fun extractNewImgs(call: Call): List<String> {
        if (call.c <= 0 || call.a <= 1) return emptyList()
        if (call.dict.size < call.c) return emptyList()

        val map = HashMap<String, String>(call.c * 2)
        for (n in 0 until call.c) {
            val t = token(n, call.a)
            val v = call.dict[n]
            map[t] = if (v.isEmpty()) t else v
        }
        val text = unescape(WORD.replace(unescape(call.payload)) { m -> map[m.value] ?: m.value })
        val body = NEW_IMGS.find(text)?.groupValues?.get(1) ?: return emptyList()

        val out = ArrayList<String>()
        QUOTED.findAll(body).forEach { m ->
            val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@forEach
            val url = when {
                v.startsWith("http://") || v.startsWith("https://") -> v
                v.startsWith("//") -> "https:$v"
                else -> null
            }
            if (url != null) out.add(url)
        }
        return out
    }

    private fun readString(s: String, at: Int): Pair<String, Int>? {
        if (at >= s.length || s[at] != '\'') return null
        val sb = StringBuilder()
        var i = at + 1
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                sb.append(ch).append(s[i + 1]); i += 2; continue
            }
            if (ch == '\'') return sb.toString() to (i + 1)
            sb.append(ch); i++
        }
        return null
    }

    private fun readInt(s: String, at: Int): Pair<Int, Int>? {
        var i = at
        while (i < s.length && s[i].isWhitespace()) i++
        val b = StringBuilder()
        while (i < s.length && s[i].isDigit()) { b.append(s[i]); i++ }
        val v = b.toString().toIntOrNull() ?: return null
        return v to i
    }

    private fun skipComma(s: String, at: Int): Int? {
        var i = at
        while (i < s.length && s[i].isWhitespace()) i++
        if (i >= s.length || s[i] != ',') return null
        return i + 1
    }

    private fun digit(c: Int): String = when {
        c < 10 -> c.toString()
        c < 36 -> ('a' + (c - 10)).toString()
        else -> (c + 29).toChar().toString()
    }

    private fun token(i: Int, a: Int): String =
        if (i < a) digit(i) else token(i / a, a) + digit(i % a)

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\' || i == s.length - 1) { sb.append(ch); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                '\'' -> { sb.append('\''); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                'u' -> {
                    if (i + 6 <= s.length) {
                        val hex = runCatching { s.substring(i + 2, i + 6).toInt(16).toChar() }.getOrNull()
                        if (hex != null) { sb.append(hex); i += 6 } else { sb.append(n); i += 2 }
                    } else { sb.append(n); i += 2 }
                }
                'x' -> {
                    if (i + 4 <= s.length) {
                        val hex = runCatching { s.substring(i + 2, i + 4).toInt(16).toChar() }.getOrNull()
                        if (hex != null) { sb.append(hex); i += 4 } else { sb.append(n); i += 2 }
                    } else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    private val WORD = Regex("""\w+""")

    private val NEW_IMGS = Regex("""(?s)newImgs\s*=\s*\[(.*?)]""")

    private val QUOTED = Regex("""'((?:[^'\\]|\\.)*)'""" + "|" + """"((?:[^"\\]|\\.)*)"""")
}
