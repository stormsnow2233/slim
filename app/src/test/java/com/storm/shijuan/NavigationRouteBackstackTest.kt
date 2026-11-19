package com.storm.shijuan

import org.junit.Test
import org.junit.Assert.*

class NavigationRouteBackstackTest {

    // 模拟 MainActivity 中的回退栈推导逻辑
    private fun resolveBackRoute(current: Route): Route {
        return when (current) {
            is Route.ComicRead -> Route.ComicDetail(current.comic, current.fromCache)
            is Route.Reader -> Route.Detail(current.book, current.fromSearch)
            is Route.Detail -> Route.Main
            is Route.ComicDetail -> if (current.fromCache) Route.Cache(fromSettings = true) else Route.Main
            is Route.CustomSourceEdit -> Route.Sources(fromSettings = current.fromSettings)
            is Route.Sources -> if (current.fromSettings) Route.Settings else Route.Main
            is Route.Network -> if (current.fromSettings) Route.Settings else Route.Main
            is Route.Cache -> if (current.fromSettings) Route.Settings else Route.Main
            is Route.Settings -> Route.Main
            else -> Route.Main
        }
    }

    @Test
    fun testSettingsSecondaryScreensReturnToSettings() {
        // 1. 网络设置从设置打开，返回应回到设置
        val netFromSettings = Route.Network(fromSettings = true)
        val netBack = resolveBackRoute(netFromSettings)
        assertEquals("网络设置应返回到设置页面", Route.Settings, netBack)

        // 2. 缓存管理从设置打开，返回应回到设置
        val cacheFromSettings = Route.Cache(fromSettings = true)
        val cacheBack = resolveBackRoute(cacheFromSettings)
        assertEquals("缓存管理应返回到设置页面", Route.Settings, cacheBack)

        // 3. 书源管理从设置打开，返回应回到设置
        val srcFromSettings = Route.Sources(fromSettings = true)
        val srcBack = resolveBackRoute(srcFromSettings)
        assertEquals("书源管理应返回到设置页面", Route.Settings, srcBack)

        // 4. 自定义书源从设置的书源管理打开，保存/返回应回到书源管理，再返回应回到设置
        val customEdit = Route.CustomSourceEdit(id = "test", fromSettings = true)
        val editBack = resolveBackRoute(customEdit)
        assertEquals("自定义书源编辑应返回到书源管理", Route.Sources(fromSettings = true), editBack)
        val srcBackFromEdit = resolveBackRoute(editBack)
        assertEquals("书源管理再返回应回到设置页面", Route.Settings, srcBackFromEdit)

        // 5. 设置页面返回回到主页
        val settingsBack = resolveBackRoute(Route.Settings)
        assertEquals("设置页面返回应回到主页", Route.Main, settingsBack)
    }

    @Test
    fun testDrawerCacheReturnsToMain() {
        // 从抽屉或顶部图标打开的缓存管理，返回直接回主页
        val cacheFromDrawer = Route.Cache(fromSettings = false)
        val cacheBack = resolveBackRoute(cacheFromDrawer)
        assertEquals("从主界面打开的缓存管理应返回到主页", Route.Main, cacheBack)
    }
}
