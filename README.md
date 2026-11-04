# 拾卷（Shijuan）· 原生安卓小说阅读器

多书源聚合搜索 + 原生阅读体验。Kotlin + Jetpack Compose，无 WebView，纯原生。

## 功能

- **多书源聚合搜索**：并发请求所有启用的书源，结果按书名合并去重，同一本书有几个源可用一眼看到
- **原生阅读器**：章内左右翻页（按屏精确分页）/ 上下滚动两种模式，5 种背景色，字号行距可调，进度自动保存
- **整本导出**：详情页三点菜单可直接导出 TXT / EPUB（EPUB 带 NCX + nav 双层目录），后台跑、完成通知
- **书架**：网格布局，显示阅读进度，长按可移除
- **书源管理**：18 个内置书源，可单独开关，支持一键体检看哪个能用
- **离线缓存**：读过的章节自动缓存，断网也能接着看
- **界面**：液态玻璃底栏（只有胶囊能拖）、四页横滑切换、全套过渡动画

## 2.2 新增

- **漫画模块**：侧边栏切「小说 / 漫画」，漫画侧 4 个源并发搜索 + 单源筛选 + 分源状态（返回几本 / 耗时 / 连不上）
  - 动漫屋（m.dm5.com）：搜索 / 详情 / 章节 / 图片解包全流程实测通过
  - 漫画人（manhuaren.com）：与动漫屋同模板，实测通过
  - 拷贝漫画：官方 v3 接口
  - 栗子漫画：从官方 APK 解出加密配置（`{"domain":"http://ai.qsmm.fun/"}`）与 `/app/api/` 接口族，字段兼容解析
- **阅读记忆**：退出后无论是否在书架都保留进度，书架页顶部单列「最近在读」
- **侧边栏**：小说 / 漫画两个入口，刘海处菜单按钮或左缘右滑打开
- **底栏交互修复**：只有玻璃胶囊响应拖动，点按不会被拖动手势吃掉；图标与文字固定基线对齐

## 编译

需要 JDK 17 + Android SDK（compileSdk 35）。

```bash
# 修改 local.properties 里的 sdk.dir 指向你的 Android SDK
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。

签名配置在 `app/build.gradle.kts` 的 `signingConfigs` 里，用的是仓库自带的 `keystore/shijuan.jks`（口令 `shijuan`）。发布正式版请换成你自己的 keystore。

## 书源

书源定义在 `app/src/main/java/com/zaka/shijuan/data/Sources.kt`。

规则语法（简化版「阅读」规则）：

```
".bookbox a@href"       选择器@字段（text / html / texts / 任意属性名）
".a@text || .b@text"    左失败用右
".a@text##正则##替换"    文本后处理
```

加一个源：往 `Sources.ALL` 里加一条，字段照抄最像的那个。站点结构不固定的可以直接写 `auto = true` 走通用识别。

## 结构

```
app/src/main/java/com/zaka/shijuan/
├── MainActivity.kt        入口 + 导航
├── data/
│   ├── Sources.kt         书源定义
│   ├── Rules.kt           规则解析引擎（Jsoup）
│   ├── Net.kt             网络层（GBK 解码 / meta 跳转跟随）
│   ├── BookRepo.kt        搜索 / 目录 / 正文 / Z-Library 接口
│   ├── Store.kt           书架、进度、缓存、设置
│   └── Models.kt          数据模型
└── ui/                    各个页面
```

## 说明

书源内容来自各公开站点，仅供个人阅读使用。
