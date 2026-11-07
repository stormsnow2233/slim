package com.storm.shijuan.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// 书卷色：暖米白 + 赭石
val Paper = Color(0xFFF8F3EA)
val PaperDeep = Color(0xFFF0E7D8)
val Ink = Color(0xFF2B241C)
val InkSoft = Color(0xFF6B5D4C)
val Cinnabar = Color(0xFF9C4A2F)
val CinnabarLight = Color(0xFFC46B45)
val Bamboo = Color(0xFF5C7A5A)

private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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
fun ShijuanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
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
)
