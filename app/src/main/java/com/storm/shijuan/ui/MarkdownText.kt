package com.storm.shijuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 解析行内 Markdown 语法（粗体、斜体、行内代码、超链接）为 Compose AnnotatedString
 */
fun parseInlineMarkdown(text: String, primaryColor: Color = Color(0xFF388E3C)): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        // 正则匹配：**粗体**, `代码`, *斜体*, [超链接](url)
        val pattern = Regex("""(\*\*(.+?)\*\*|__([^_]+)__|`([^`]+)`|\*([^*]+)\*|\[(.+?)\]\((.+?)\))""")
        for (match in pattern.findAll(text)) {
            if (match.range.first > currentIndex) {
                append(text.substring(currentIndex, match.range.first))
            }
            val v = match.value
            when {
                // **粗体** 或 __粗体__
                (v.startsWith("**") && v.endsWith("**")) -> {
                    val inner = v.removeSurrounding("**")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inner)
                    }
                }
                (v.startsWith("__") && v.endsWith("__")) -> {
                    val inner = v.removeSurrounding("__")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inner)
                    }
                }
                // `行内代码`
                (v.startsWith("`") && v.endsWith("`")) -> {
                    val inner = v.removeSurrounding("`")
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            background = Color(0x22808080),
                            color = primaryColor
                        )
                    ) {
                        append(" $inner ")
                    }
                }
                // *斜体*
                (v.startsWith("*") && v.endsWith("*")) -> {
                    val inner = v.removeSurrounding("*")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inner)
                    }
                }
                // [文字](链接)
                (v.startsWith("[") && v.contains("](") && v.endsWith(")")) -> {
                    val linkMatch = Regex("""\[(.+?)\]\((.+?)\)""").find(v)
                    if (linkMatch != null) {
                        val label = linkMatch.groupValues[1]
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF1E88E5),
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(label)
                        }
                    } else {
                        append(v)
                    }
                }
                else -> append(v)
            }
            currentIndex = match.range.last + 1
        }
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

/**
 * 原生 Compose Markdown 渲染组件：
 * 支持标题（# ~ ######）、无序列表（- / *）、有序列表（1. / 2.）、
 * 分割线（---）、引用块（>）及段落，彻底消除纯文本显示 raw md 的粗糙感。
 */
@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val lines = remember(markdown) { markdown.lines() }
    val primary = MaterialTheme.colorScheme.primary

    Column(modifier = modifier) {
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) {
                Spacer(Modifier.height(4.dp))
                continue
            }

            // 分割线 --- 或 ***
            if (line.trim() in listOf("---", "***", "___")) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(6.dp))
                continue
            }

            // 标题: # ~ ######
            if (line.startsWith("#")) {
                val level = line.takeWhile { it == '#' }.length
                val titleText = line.substring(level).trim()
                val (fontSize, topPadding) = when (level) {
                    1 -> 16.sp to 10.dp
                    2 -> 15.sp to 8.dp
                    3 -> 14.sp to 6.dp
                    else -> 13.sp to 4.dp
                }
                Spacer(Modifier.height(topPadding))
                Text(
                    text = parseInlineMarkdown(titleText, primary),
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    lineHeight = (fontSize.value * 1.35f).sp
                )
                Spacer(Modifier.height(3.dp))
                continue
            }

            val trimmedStart = line.trimStart()
            val indentSpaces = line.length - trimmedStart.length
            val indentLevel = (indentSpaces / 2).coerceIn(0, 4)

            // 无序列表: - 或 *
            if (trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ")) {
                val content = trimmedStart.substring(2).trim()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (indentLevel * 12).dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = primary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = parseInlineMarkdown(content, primary),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = color,
                        modifier = Modifier.weight(1f)
                    )
                }
                continue
            }

            // 有序列表: 1. 2. 等
            val orderedMatch = Regex("""^(\d+)\.\s+(.*)$""").find(trimmedStart)
            if (orderedMatch != null) {
                val num = orderedMatch.groupValues[1]
                val content = orderedMatch.groupValues[2]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (indentLevel * 12).dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "$num.",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primary,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = parseInlineMarkdown(content, primary),
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = color,
                        modifier = Modifier.weight(1f)
                    )
                }
                continue
            }

            // 引用块: >
            if (trimmedStart.startsWith(">")) {
                val quoteText = trimmedStart.substring(1).trim()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(primary.copy(alpha = 0.6f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = parseInlineMarkdown(quoteText, primary),
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        color = color.copy(alpha = 0.85f),
                        lineHeight = 17.sp
                    )
                }
                continue
            }

            // 普通段落文本
            Text(
                text = parseInlineMarkdown(line, primary),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = color,
                modifier = Modifier.padding(vertical = 1.5.dp)
            )
        }
    }
}
