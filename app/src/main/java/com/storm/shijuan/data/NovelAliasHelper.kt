package com.storm.shijuan.data

/**
 * 轻小说与动漫别名映射及智能模糊匹配引擎。
 * 解决轻小说不同译名（如 台版《败北女角太多了！》 vs 大陆网络《败犬女主太多了！》）、
 * 读者简称（如 春物、实教、骨王、路人女主、转生史莱姆、兔女郎学姐）以及
 * 漫画近义词（如《古见同学有社交障碍》 vs 《古见同学有交流障碍症》）检索匹配问题。
 */
object NovelAliasHelper {

    // 核心别名同义词库（全部双向互通）
    private val ALIAS_GROUPS = listOf(
        // 败犬女主 / 败北女角
        listOf("败犬女主角太多了", "败犬女主太多了", "败犬女主", "败犬女主角", "败北女角太多了", "败北女角", "败犬", "负けヒロインが多すぎる"),
        // 古见同学
        listOf("古见同学有社交障碍", "古见同学有交流障碍症", "古见同学有交流障碍", "古见同学", "古见同学交流障碍", "古见同学社交障碍"),
        // 春物 / 大老师
        listOf("春物", "大老师", "果然我的青春恋爱喜剧搞错了", "我的青春恋爱物语果然有问题", "我的青春恋爱喜剧果然有问题"),
        // 路人女主
        listOf("路人女主", "路人女主的养成方法", "不起眼女主角培育法", "路人女主培育法"),
        // 转生史莱姆
        listOf("转生史莱姆", "关于我转生变成史莱姆这档事", "关于我转生后成为史莱姆的那件事", "史莱姆这档事"),
        // 兔女郎学姐
        listOf("兔女郎学姐", "青春猪头少年不会梦到兔女郎学姐", "青春猪头少年不会梦到兔女郎学姊", "青春猪头少年系列", "青春猪头"),
        // 欢迎来到实力至上主义的教室
        listOf("实教", "实力至上主义的教室", "实力至上主义教室", "欢迎来到实力至上主义的教室", "实力至上"),
        // OVERLORD / 骨王
        listOf("骨王", "骨傲天", "不死者之王", "OVERLORD", "overlord"),
        // 盾娘 / 怕痛
        listOf("盾娘", "怕痛的我把防御力点满就对了", "因为太怕痛就全点防御力了", "防振"),
        // 剃须
        listOf("剃须", "捡到女高中生", "剃须。然后捡到女高中生。", "剃须然后捡到女高中生"),
        // 魔禁 / 超炮
        listOf("魔禁", "魔法禁书目录"),
        listOf("超炮", "某科学的超电磁炮"),
        // 魔劣 / 劣等生
        listOf("魔劣", "魔法科高中的劣等生", "劣等生"),
        // 无职转生
        listOf("无职转生", "无职转生~到了异世界就拿出真本事~", "无职转生～到了异世界就拿出真本事～"),
        // 邻家天使
        listOf("邻家天使", "天使大人", "关于邻家的天使大人不知不觉把我惯成了废人这档事", "邻家天使大人"),
        // 间谍过家家
        listOf("间谍过家家", "间谍家家酒", "SPY×FAMILY", "SPYxFAMILY"),
        // 义妹生活
        listOf("义妹生活", "义妹"),
        // 地下城 / 地错
        listOf("地错", "在地下城寻求邂逅是否搞错了什么", "地下城寻求邂逅"),
        // 刀剑神域
        listOf("刀剑神域", "SAO", "sao"),
        // Re:从零开始
        listOf("从零开始的异世界生活", "Re0", "re0", "从零开始"),
    )

    // 构建快速查找映射表
    private val ALIAS_MAP = buildMap<String, Set<String>> {
        for (group in ALIAS_GROUPS) {
            for (name in group) {
                val others = group.filter { it != name }.toSet()
                val norm = normalize(name)
                put(norm, (get(norm) ?: emptySet()) + others)
            }
        }
    }

    /** 标准化清洗：去除空格、标点、转小写 */
    fun normalize(s: String): String =
        s.replace(Regex("[\\s·:：\\-—_()（）\\[\\]！!？?~～,，.。\"'“”]"), "").lowercase()

    /**
     * 扩展搜索词列表：包含原词清洗版、别名映射、核心主体词提取。
     * 保证传入 "败犬女主角太多了" 时，返回包含 "败北女角太多了"、"败北女角" 等高命中词。
     */
    fun expandQueries(query: String): List<String> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        result.add(q)

        val norm = normalize(q)
        // 1. 查找完全匹配或子串包含的别名
        ALIAS_MAP[norm]?.let { result.addAll(it) }

        for ((key, aliases) in ALIAS_MAP) {
            if (norm.contains(key) || key.contains(norm)) {
                result.addAll(aliases)
            }
        }

        // 2. 词素替换规则（常见通用别名/异构词替换）
        val replacements = listOf(
            "女主角" to "女主",
            "女主" to "女角",
            "女主角" to "女角",
            "败犬" to "败北",
            "社交障碍" to "交流障碍症",
            "社交障碍" to "交流障碍",
            "家家酒" to "过家家",
            "学姊" to "学姐",
            "学姐" to "学姊",
        )
        for ((from, to) in replacements) {
            if (q.contains(from)) {
                val replaced = q.replace(from, to)
                result.add(replaced)
                normalize(replaced).let { ALIAS_MAP[it]?.let { a -> result.addAll(a) } }
            }
        }

        // 3. 提取主体前缀（去除末尾冗余词如 "太多了"、"这档事" 等）
        val stripped = q.replace(Regex("(太多了|这档事|果然有问题|的养成方法|不会梦到|的故事|同人).*$"), "").trim()
        if (stripped.length >= 2 && stripped != q) {
            result.add(stripped)
            normalize(stripped).let { ALIAS_MAP[it]?.let { a -> result.addAll(a) } }
        }

        return result.filter { it.isNotBlank() }.toList()
    }

    /**
     * 计算输入搜索词与目标条目（书名/作者）的匹配得分。
     * 得分范围：0.0 ~ 2.0+。
     * >= 1.0 为强匹配（完全包含或别名精确命中），0.5 ~ 0.99 为高置信度模糊匹配，< 0.5 为不匹配。
     */
    fun matchScore(targetTitle: String, targetAuthor: String, rawQuery: String): Double {
        val q = rawQuery.trim()
        if (q.isBlank()) return 0.0

        val nTitle = normalize(targetTitle)
        val nAuthor = normalize(targetAuthor)
        val nQuery = normalize(q)

        // 1. 绝对精确包含（原词直接命中）
        if (nTitle == nQuery) return 3.0
        if (nTitle.contains(nQuery)) return 2.0 + (nQuery.length.toDouble() / nTitle.length)
        if (nQuery.contains(nTitle)) return 1.8 + (nTitle.length.toDouble() / nQuery.length)

        // 2. 作者命中
        if (nAuthor.isNotBlank() && (nAuthor == nQuery || nAuthor.contains(nQuery))) return 1.9

        // 3. 扩展别名库匹配
        val candidateQueries = expandQueries(q).map { normalize(it) }.distinct()
        for (cq in candidateQueries) {
            if (nTitle == cq) return 2.8
            if (nTitle.contains(cq)) return 2.0 + (cq.length.toDouble() / nTitle.length)
            if (cq.contains(nTitle)) return 1.7 + (nTitle.length.toDouble() / cq.length)
        }

        // 4. 最长公共子序列 (LCS) 与字符重叠度模糊匹配
        val lcs = longestCommonSubsequence(nTitle, nQuery)
        val lcsRatio = (2.0 * lcs) / (nTitle.length + nQuery.length)

        // 只要 LCS 长度覆盖了查询词的大多数关键字符（如 败犬女主太多了 覆盖 败北女角太多了）
        val queryCoverage = lcs.toDouble() / nQuery.length
        if (lcs >= 3 && queryCoverage >= 0.5) {
            return 0.8 + (queryCoverage * 0.5) + (lcsRatio * 0.2)
        }

        return if (lcsRatio >= 0.6) lcsRatio else 0.0
    }

    /** 动态规划计算最长公共子序列长度 */
    private fun longestCommonSubsequence(text1: String, text2: String): Int {
        val m = text1.length
        val n = text2.length
        if (m == 0 || n == 0) return 0
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                if (text1[i - 1] == text2[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    /** 智能章节匹配：用于自动换源时精准定位新源中对应的章节 */
    fun matchChapterIndex(targetName: String, fallbackIndex: Int, chapters: List<Chapter>): Int {
        if (chapters.isEmpty()) return 0
        // 1. 完全相同
        val exact = chapters.indexOfFirst { it.name == targetName }
        if (exact >= 0) return exact

        // 2. 规范化后相同（去除空格、标点、括号、卷号）
        fun norm(s: String): String = s.replace(Regex("[\\s\\-_（）()《》【】\\[\\]·]+"), "")
            .replace(Regex("^(第[0-9一二三四五六七八九十百]+卷|卷[0-9一二三四五六七八九十百]+)"), "")
            .lowercase()

        val normTarget = norm(targetName)
        val normMatch = chapters.indexOfFirst { norm(it.name) == normTarget }
        if (normMatch >= 0) return normMatch

        // 3. 包含关系（如果标题较长）
        if (normTarget.length >= 4) {
            val containMatch = chapters.indexOfFirst {
                val n = norm(it.name)
                n.contains(normTarget) || normTarget.contains(n)
            }
            if (containMatch >= 0) return containMatch
        }

        // 4. 章节数字序号匹配（第 X 章 / 第 X 话 / X ）
        fun extractNum(s: String): Int? {
            val m = Regex("第\\s*([0-9]+|[零一二三四五六七八九十百千万]+)\\s*[章话回节]").find(s)
            if (m != null) {
                val raw = m.groupValues[1]
                raw.toIntOrNull()?.let { return it }
                return chineseToInt(raw)
            }
            val m2 = Regex("^\\s*([0-9]+)\\b").find(s)
            return m2?.groupValues?.get(1)?.toIntOrNull()
        }

        val targetNum = extractNum(targetName)
        if (targetNum != null && targetNum > 0) {
            val numMatch = chapters.indexOfFirst { extractNum(it.name) == targetNum }
            if (numMatch >= 0) return numMatch
        }

        // 5. 兜底：序号安全对齐
        return fallbackIndex.coerceIn(0, chapters.size - 1)
    }

    private fun chineseToInt(s: String): Int? {
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s.length == 1) return digits[s[0]]
        if (s == "十") return 10
        if (s.startsWith("十") && s.length == 2) return 10 + (digits[s[1]] ?: 0)
        if (s.endsWith("十") && s.length == 2) return (digits[s[0]] ?: 1) * 10
        if (s.length == 3 && s[1] == '十') return (digits[s[0]] ?: 1) * 10 + (digits[s[2]] ?: 0)
        return null
    }
}
