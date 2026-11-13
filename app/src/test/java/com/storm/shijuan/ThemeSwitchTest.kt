package com.storm.shijuan

import androidx.compose.ui.graphics.Color
import com.storm.shijuan.ui.AppThemeMode
import com.storm.shijuan.ui.AppThemeStyle
import com.storm.shijuan.ui.MiuixBlue
import com.storm.shijuan.ui.MiuixDarkBlue
import com.storm.shijuan.ui.MiuixTeal
import com.storm.shijuan.ui.ReaderThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSwitchTest {

    @Test
    fun testAppThemeStyleEnumAndFallback() {
        assertEquals(0, AppThemeStyle.Classic.id)
        assertEquals(1, AppThemeStyle.MiuiX.id)

        assertEquals("经典书卷", AppThemeStyle.Classic.title)
        assertEquals("MiuiX 极简", AppThemeStyle.MiuiX.title)

        assertEquals(AppThemeStyle.Classic, AppThemeStyle.fromId(0))
        assertEquals(AppThemeStyle.MiuiX, AppThemeStyle.fromId(1))
        assertEquals(AppThemeStyle.Classic, AppThemeStyle.fromId(-1))
        assertEquals(AppThemeStyle.Classic, AppThemeStyle.fromId(99))
    }

    @Test
    fun testAppThemeModeEnumAndFallback() {
        assertEquals(0, AppThemeMode.System.id)
        assertEquals(1, AppThemeMode.Light.id)
        assertEquals(2, AppThemeMode.Dark.id)

        assertEquals("跟随系统", AppThemeMode.System.title)
        assertEquals("浅色模式", AppThemeMode.Light.title)
        assertEquals("深色模式", AppThemeMode.Dark.title)

        assertEquals(AppThemeMode.System, AppThemeMode.fromId(0))
        assertEquals(AppThemeMode.Light, AppThemeMode.fromId(1))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromId(2))
        assertEquals(AppThemeMode.System, AppThemeMode.fromId(999))
    }

    @Test
    fun testMiuixColorTokens() {
        // 校验官方 MiuiX / HyperOS 核心特征色值
        assertEquals(Color(0xFF3482FF), MiuixBlue)
        assertEquals(Color(0xFF277AF7), MiuixDarkBlue)
        assertEquals(Color(0xFF00C4B4), MiuixTeal)
    }

    @Test
    fun testReaderThemesContainsMiuixAndHyperOs() {
        val names = ReaderThemes.map { it.name }
        assertTrue("ReaderThemes 应包含 MiuiX蓝", names.contains("MiuiX蓝"))
        assertTrue("ReaderThemes 应包含 HyperOS黑", names.contains("HyperOS黑"))

        val miuixTheme = ReaderThemes.first { it.name == "MiuiX蓝" }
        assertNotNull(miuixTheme)
        assertEquals(Color(0xFFF5F8FF), miuixTheme.bg)

        val hyperOsTheme = ReaderThemes.first { it.name == "HyperOS黑" }
        assertNotNull(hyperOsTheme)
        assertEquals(Color(0xFF181A1D), hyperOsTheme.bg)
    }
}
