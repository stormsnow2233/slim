package com.storm.shijuan.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ── 主题风格枚举 ──
enum class AppThemeStyle(val id: Int, val title: String, val subtitle: String) {
    Classic(0, "经典书卷", "暖玉生香 · 国风典雅"),
    MiuiX(1, "MiuiX 极简", "澎湃蔚蓝 · 现代纯粹");

    companion object {
        fun fromId(id: Int): AppThemeStyle = entries.find { it.id == id } ?: Classic
    }
}

// ── 深浅色模式枚举 ──
enum class AppThemeMode(val id: Int, val title: String) {
    System(0, "跟随系统"),
    Light(1, "浅色模式"),
    Dark(2, "深色模式");

    companion object {
        fun fromId(id: Int): AppThemeMode = entries.find { it.id == id } ?: System
    }
}

val LocalAppThemeStyle = compositionLocalOf { AppThemeStyle.Classic }

// ── 经典书卷色：暖米白 + 赭石 ──
val Paper = Color(0xFFF8F3EA)
val PaperDeep = Color(0xFFF0E7D8)
val Ink = Color(0xFF2B241C)
val InkSoft = Color(0xFF6B5D4C)
val Cinnabar = Color(0xFF9C4A2F)
val CinnabarLight = Color(0xFFC46B45)
val Bamboo = Color(0xFF5C7A5A)

private val ClassicLightColors = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3D9CE),
    onPrimaryContainer = Color(0xFF3B1408),
    secondary = Bamboo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7DA),
    onSecondaryContainer = Color(0xFF17251A),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFDFAF4),
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = Color(0xFFD5C7B2),
    outlineVariant = Color(0xFFE8DDCB),
    error = Color(0xFFB3261E),
)

private val ClassicDarkColors = darkColorScheme(
    primary = Color(0xFFE8A884),
    onPrimary = Color(0xFF44200F),
    primaryContainer = Color(0xFF5E2E18),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFA8C4A6),
    onSecondary = Color(0xFF17311B),
    background = Color(0xFF15120E),
    onBackground = Color(0xFFEDE4D7),
    surface = Color(0xFF1D1913),
    onSurface = Color(0xFFEDE4D7),
    surfaceVariant = Color(0xFF2A241C),
    onSurfaceVariant = Color(0xFFBDAE9A),
    outline = Color(0xFF4A4034),
    outlineVariant = Color(0xFF332B22),
)

// ── MiuiX (HyperOS / MIUI 极简设计系统) ──
// 采用 compose-miuix-ui 官方设计规范
val MiuixBlue = Color(0xFF3482FF)
val MiuixDarkBlue = Color(0xFF277AF7)
val MiuixTeal = Color(0xFF00C4B4)

private val MiuixLightColors = lightColorScheme(
    primary = MiuixBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF2FF),
    onPrimaryContainer = Color(0xFF0056C7),
    secondary = MiuixTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF303030),
    tertiary = Color(0xFFFF6900),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDE0),
    onTertiaryContainer = Color(0xFF6B2600),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF111111),
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDF0),
    onSurfaceVariant = Color(0xFF8C93B0),
    outline = Color(0xFFD9D9D9),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFE94634),
    onError = Color.White,
    errorContainer = Color(0xFFFFF0EE),
    onErrorContainer = Color(0xFFB01D11),
)

private val MiuixDarkColors = darkColorScheme(
    primary = MiuixDarkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A63),
    onPrimaryContainer = Color(0xFF99C7F1),
    secondary = MiuixTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF434343),
    onSecondaryContainer = Color(0xFFD9D9D9),
    tertiary = Color(0xFFFF7A22),
    onTertiary = Color(0xFF3E1400),
    tertiaryContainer = Color(0xFF4F2208),
    onTertiaryContainer = Color(0xFFFFDBCB),
    background = Color(0xFF141414),
    onBackground = Color(0xE6FFFFFF),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFF959595),
    outline = Color(0xFF404040),
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFF12522),
    onError = Color.White,
    errorContainer = Color(0xFF3D0C0A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp),
)

@Composable
fun ShijuanTheme(
    style: Int = 0,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val themeStyle = AppThemeStyle.fromId(style)
    val colors = when (themeStyle) {
        AppThemeStyle.Classic -> if (dark) ClassicDarkColors else ClassicLightColors
        AppThemeStyle.MiuiX -> if (dark) MiuixDarkColors else MiuixLightColors
    }
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }
    CompositionLocalProvider(LocalAppThemeStyle provides themeStyle) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}

/** 阅读器配色方案 */
data class ReaderTheme(
    val name: String,
    val bg: Color,
    val text: Color,
    val bar: Color,
)

val ReaderThemes = listOf(
    ReaderTheme("纸白", Color(0xFFF8F3EA), Color(0xFF2B241C), Color(0xFFEFE6D8)),
    ReaderTheme("米黄", Color(0xFFF5E6C8), Color(0xFF3A2F22), Color(0xFFE8D6B4)),
    ReaderTheme("护眼绿", Color(0xFFCCE8CF), Color(0xFF23301F), Color(0xFFB9D9BD)),
    ReaderTheme("夜间", Color(0xFF171512), Color(0xFFA8A296), Color(0xFF232019)),
    ReaderTheme("纯黑", Color(0xFF000000), Color(0xFF8C8C8C), Color(0xFF141414)),
    ReaderTheme("MiuiX蓝", Color(0xFFF5F8FF), Color(0xFF1B2A40), Color(0xFFE3EDFA)),
    ReaderTheme("HyperOS黑", Color(0xFF181A1D), Color(0xFFB8C0CC), Color(0xFF22262C)),
)
