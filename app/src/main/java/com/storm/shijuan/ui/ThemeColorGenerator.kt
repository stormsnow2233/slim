package com.storm.shijuan.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * 预设经典种子色推荐
 */
data class ThemeColorPreset(
    val name: String,
    val color: Color,
    val description: String
)

object ThemePresets {
    val list = listOf(
        ThemeColorPreset("经典赭石", Color(0xFF9C4A2F), "国风书卷 · 沉稳内敛"),
        ThemeColorPreset("澎湃蔚蓝", Color(0xFF3482FF), "科技极简 · 活力明快"),
        ThemeColorPreset("竹青翠绿", Color(0xFF2E7D32), "自然舒缓 · 护眼温润"),
        ThemeColorPreset("琥珀暖阳", Color(0xFFE65100), "热情温暖 · 晨曦明朗"),
        ThemeColorPreset("紫罗兰晶", Color(0xFF673AB7), "优雅深邃 · 质感典雅"),
        ThemeColorPreset("胭脂薄红", Color(0xFFD81B60), "明艳绮丽 · 活泼灵动"),
        ThemeColorPreset("青碧松石", Color(0xFF00897B), "清澈通透 · 宁静平缓"),
        ThemeColorPreset("玄墨冷灰", Color(0xFF455A64), "低调理性 · 高级灰度")
    )
}

/**
 * 色彩生成与协议扩展器：
 * 支持分别基于【谷歌 Material 3 协议】与【小米 MiuiX 协议】根据种子色自动推导并拓展整套配色体系。
 */
object ThemeColorGenerator {

    /**
     * 将 RGB 转换为 HSL (色相 0..360, 饱和度 0..1, 明度 0..1)
     */
    fun colorToHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue

        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val delta = max - min

        var h = 0f
        var s: Float
        val l = (max + min) / 2f

        if (delta == 0f) {
            h = 0f
            s = 0f
        } else {
            s = if (l < 0.5f) delta / (max + min) else delta / (2f - max - min)
            when (max) {
                r -> h = ((g - b) / delta + (if (g < b) 6f else 0f)) * 60f
                g -> h = ((b - r) / delta + 2f) * 60f
                b -> h = ((r - g) / delta + 4f) * 60f
            }
        }
        return floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    /**
     * 将 HSL 还原为 Color
     */
    fun hslToColor(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f

        var rPrime = 0f
        var gPrime = 0f
        var bPrime = 0f

        when {
            h < 60f -> {
                rPrime = c; gPrime = x; bPrime = 0f
            }
            h < 120f -> {
                rPrime = x; gPrime = c; bPrime = 0f
            }
            h < 180f -> {
                rPrime = 0f; gPrime = c; bPrime = x
            }
            h < 240f -> {
                rPrime = 0f; gPrime = x; bPrime = c
            }
            h < 300f -> {
                rPrime = x; gPrime = 0f; bPrime = c
            }
            else -> {
                rPrime = c; gPrime = 0f; bPrime = x
            }
        }

        return Color(
            red = (rPrime + m).coerceIn(0f, 1f),
            green = (gPrime + m).coerceIn(0f, 1f),
            blue = (bPrime + m).coerceIn(0f, 1f),
            alpha = alpha.coerceIn(0f, 1f)
        )
    }

    /**
     * 计算相对感知亮度 (W3C WCAG)
     */
    fun calculateLuminance(color: Color): Float {
        fun channel(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f
            else Math.pow(((v + 0.055) / 1.055), 2.4).toFloat()
        return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
    }

    /**
     * 【谷歌 Material 协议计算方案】
     * 遵循 Material Design 3 色调阶梯规范 (Tonal Palettes)，以种子色为基准生成柔和、协调、富有阶梯感的情感色彩。
     */
    fun generateMaterialScheme(seedColor: Color, dark: Boolean): ColorScheme {
        val hsl = colorToHsl(seedColor)
        val h = hsl[0]
        val s = hsl[1]

        return if (!dark) {
            // ── Material 3 浅色规范 ──
            val primary = hslToColor(h, (s * 0.95f).coerceAtLeast(0.45f), 0.38f)
            val onPrimary = Color.White
            val primaryContainer = hslToColor(h, (s * 0.40f).coerceIn(0.15f, 0.45f), 0.90f)
            val onPrimaryContainer = hslToColor(h, s.coerceAtLeast(0.5f), 0.12f)

            val secH = (h + 12f) % 360f
            val secondary = hslToColor(secH, (s * 0.45f).coerceIn(0.2f, 0.5f), 0.42f)
            val onSecondary = Color.White
            val secondaryContainer = hslToColor(secH, (s * 0.30f).coerceIn(0.12f, 0.35f), 0.92f)
            val onSecondaryContainer = hslToColor(secH, s.coerceAtLeast(0.4f), 0.14f)

            val terH = (h + 60f) % 360f
            val tertiary = hslToColor(terH, (s * 0.50f).coerceIn(0.25f, 0.55f), 0.40f)
            val onTertiary = Color.White
            val tertiaryContainer = hslToColor(terH, (s * 0.35f).coerceIn(0.15f, 0.40f), 0.91f)
            val onTertiaryContainer = hslToColor(terH, s.coerceAtLeast(0.45f), 0.12f)

            // 背景与表面采用种子色极低饱和度微染色调（体现 Material You 自然氛围）
            val background = hslToColor(h, 0.08f, 0.98f)
            val onBackground = Color(0xFF1B1B1F)
            val surface = hslToColor(h, 0.06f, 0.99f)
            val onSurface = Color(0xFF1B1B1F)
            val surfaceVariant = hslToColor(h, 0.14f, 0.90f)
            val onSurfaceVariant = hslToColor(h, 0.18f, 0.32f)

            val outline = hslToColor(h, 0.14f, 0.52f)
            val outlineVariant = hslToColor(h, 0.12f, 0.82f)

            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = Color(0xFFBA1A1A),
                onError = Color.White,
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410002)
            )
        } else {
            // ── Material 3 深色规范 ──
            val primary = hslToColor(h, (s * 0.85f).coerceAtLeast(0.45f), 0.76f)
            val onPrimary = hslToColor(h, s.coerceAtLeast(0.6f), 0.16f)
            val primaryContainer = hslToColor(h, s.coerceAtLeast(0.5f), 0.28f)
            val onPrimaryContainer = hslToColor(h, (s * 0.5f).coerceIn(0.2f, 0.5f), 0.90f)

            val secH = (h + 12f) % 360f
            val secondary = hslToColor(secH, (s * 0.50f).coerceIn(0.25f, 0.55f), 0.74f)
            val onSecondary = hslToColor(secH, s.coerceAtLeast(0.5f), 0.18f)
            val secondaryContainer = hslToColor(secH, s.coerceAtLeast(0.4f), 0.26f)
            val onSecondaryContainer = hslToColor(secH, (s * 0.4f).coerceIn(0.18f, 0.45f), 0.90f)

            val terH = (h + 60f) % 360f
            val tertiary = hslToColor(terH, (s * 0.55f).coerceIn(0.3f, 0.6f), 0.75f)
            val onTertiary = hslToColor(terH, s.coerceAtLeast(0.55f), 0.16f)
            val tertiaryContainer = hslToColor(terH, s.coerceAtLeast(0.45f), 0.27f)
            val onTertiaryContainer = hslToColor(terH, (s * 0.45f).coerceIn(0.2f, 0.5f), 0.90f)

            val background = hslToColor(h, 0.08f, 0.07f)
            val onBackground = Color(0xFFE4E1E6)
            val surface = hslToColor(h, 0.07f, 0.11f)
            val onSurface = Color(0xFFE4E1E6)
            val surfaceVariant = hslToColor(h, 0.14f, 0.26f)
            val onSurfaceVariant = hslToColor(h, 0.16f, 0.76f)

            val outline = hslToColor(h, 0.14f, 0.56f)
            val outlineVariant = hslToColor(h, 0.12f, 0.28f)

            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6)
            )
        }
    }

    /**
     * 【小米 MiuiX / HyperOS 协议计算方案】
     * 遵循小米 MIUI/HyperOS 设计系统：纯净白色/深黑底色，高通透卡片层级，以强烈、纯正、明快的主题色作为点睛视觉焦点。
     */
    fun generateMiuixScheme(
        seedColor: Color,
        dark: Boolean
    ): Pair<ColorScheme, top.yukonga.miuix.kmp.theme.Colors> {
        val hsl = colorToHsl(seedColor)
        val h = hsl[0]
        val s = hsl[1]

        val m3Scheme: ColorScheme
        val miuixColors: top.yukonga.miuix.kmp.theme.Colors

        if (!dark) {
            // ── MiuiX 浅色规范 ──
            val primary = seedColor
            val onPrimary = if (calculateLuminance(primary) > 0.6f) Color(0xFF111111) else Color.White
            // 浅色容器采用主色与白底的通透淡彩（HyperOS 标签、卡片高光规范）
            val primaryContainer = hslToColor(h, (s * 0.55f).coerceIn(0.18f, 0.55f), 0.94f)
            val onPrimaryContainer = hslToColor(h, s.coerceAtLeast(0.6f), 0.35f)

            // MiuiX 经典辅助标志色
            val secondary = Color(0xFF00C4B4)
            val secondaryContainer = Color(0xFFF0F0F0)
            val onSecondaryContainer = Color(0xFF303030)

            val background = Color(0xFFF7F7F7)
            val onBackground = Color(0xFF111111)
            val surface = Color.White
            val onSurface = Color(0xFF111111)
            val surfaceVariant = Color(0xFFEDEDF0)
            val onSurfaceVariant = Color(0xFF8C93B0)
            val outline = Color(0xFFD9D9D9)
            val outlineVariant = Color(0xFFE5E5EA)

            m3Scheme = lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = Color.White,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = Color(0xFFFF6900),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFFFEDE0),
                onTertiaryContainer = Color(0xFF6B2600),
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = Color(0xFFE94634),
                onError = Color.White,
                errorContainer = Color(0xFFFFF0EE),
                onErrorContainer = Color(0xFFB01D11),
            )

            val baseMiuix = top.yukonga.miuix.kmp.theme.lightColorScheme()
            miuixColors = baseMiuix.copy(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                surface = surface,
                onSurface = onSurface,
                background = background,
                onBackground = onBackground,
                dividerLine = outlineVariant,
            )
        } else {
            // ── MiuiX 深色规范 ──
            val primary = hslToColor(h, (s * 0.90f).coerceAtLeast(0.5f), 0.65f)
            val onPrimary = Color.White
            val primaryContainer = hslToColor(h, s.coerceAtLeast(0.45f), 0.22f)
            val onPrimaryContainer = hslToColor(h, (s * 0.6f).coerceIn(0.2f, 0.6f), 0.88f)

            val secondary = Color(0xFF00C4B4)
            val secondaryContainer = Color(0xFF434343)
            val onSecondaryContainer = Color(0xFFD9D9D9)

            val background = Color(0xFF141414)
            val onBackground = Color(0xE6FFFFFF)
            val surface = Color(0xFF242424)
            val onSurface = Color(0xFFF2F2F2)
            val surfaceVariant = Color(0xFF2D2D2D)
            val onSurfaceVariant = Color(0xFF959595)
            val outline = Color(0xFF404040)
            val outlineVariant = Color(0xFF333333)

            m3Scheme = darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = Color.White,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = Color(0xFFFF7A22),
                onTertiary = Color(0xFF3E1400),
                tertiaryContainer = Color(0xFF4F2208),
                onTertiaryContainer = Color(0xFFFFDBCB),
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceVariant,
                onSurfaceVariant = onSurfaceVariant,
                outline = outline,
                outlineVariant = outlineVariant,
                error = Color(0xFFF12522),
                onError = Color.White,
                errorContainer = Color(0xFF3D0C0A),
                onErrorContainer = Color(0xFFFFDAD6),
            )

            val baseMiuix = top.yukonga.miuix.kmp.theme.darkColorScheme()
            miuixColors = baseMiuix.copy(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                surface = surface,
                onSurface = onSurface,
                background = background,
                onBackground = onBackground,
                dividerLine = outlineVariant,
            )
        }

        return Pair(m3Scheme, miuixColors)
    }

    /**
     * 将十六进制颜色文本 (#RRGGBB) 安全解析为 Color
     */
    fun parseHexColorOrNull(hex: String): Color? {
        val clean = hex.trim().removePrefix("#")
        return try {
            when (clean.length) {
                6 -> {
                    val colorInt = clean.toLong(16).toInt() or (0xFF shl 24)
                    Color(colorInt)
                }
                8 -> {
                    val colorInt = clean.toLong(16).toInt()
                    Color(colorInt)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将 Color 转为 #RRGGBB 字符串
     */
    fun colorToHex(color: Color): String {
        val argb = color.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return String.format("#%02X%02X%02X", r, g, b)
    }

    /**
     * 将 RGB 分量 (0..255) 转为 Color
     */
    fun rgbToColor(r: Int, g: Int, b: Int): Color {
        return Color(
            red = r.coerceIn(0, 255) / 255f,
            green = g.coerceIn(0, 255) / 255f,
            blue = b.coerceIn(0, 255) / 255f,
            alpha = 1f
        )
    }

    /**
     * 获取 Color 的 RGB 整数分量 (0..255)
     */
    fun colorToRgbInts(color: Color): Triple<Int, Int, Int> {
        val argb = color.toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return Triple(r, g, b)
    }
}
