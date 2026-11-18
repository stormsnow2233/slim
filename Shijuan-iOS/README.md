# 拾卷 iOS

这是拾卷的独立 iOS 版基础工程目录，不会修改 Android 工程。

## 当前状态

首阶段使用 SwiftUI 搭建可运行的应用壳：

- 书架、搜索、设置三栏导航
- 经典 / MiuiX 风格主题切换
- 本地书架模型与示例数据
- 书籍详情、章节目录、阅读器占位页
- Wenku8/JM 新 Pages 代理配置占位
- 为书源、网络请求和后台缓存服务预留目录

## 开发环境

需要 macOS + Xcode 15 或更高版本。当前开发环境为 Windows，无法在本机执行 Xcode 构建或 iOS 模拟器验证。

## 创建 Xcode 工程

在 macOS 上新建一个 iOS App（SwiftUI、Swift），产品名设为 `Shijuan`，然后将 `Shijuan/` 目录中的源文件加入工程。后续可将本目录升级为标准 `.xcodeproj` / Swift Package 结构。
