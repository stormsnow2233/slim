# 拾卷 iOS

这是拾卷的独立 iOS 版工程目录，不会修改 Android 工程。当前工程已包含可构建的手工 Xcode 项目，并可生成未签名 IPA。

## 当前状态

当前已实现的 iOS16 功能：

- 书架、搜索、设置三栏导航
- 经典 / MiuiX 风格主题切换
- 本地书架与 UserDefaults 持久化
- TXT、PDF、EPUB 导入；TXT 导出
- 书籍详情、在线目录和章节正文加载
- 自定义书源增删、启停、多源并行搜索
- HTML/JSON/纯文本正文兼容与清洗
- 阅读字号、行距、夜间背景和进度保存
- 章节缓存、后台 BGProcessingTask 调度及通知
- 漫画搜索/详情/竖向阅读（JM 代理，ComicCatalog/ComicViews）
- 规则书源引擎 MVP（RuleSource/MiniHTML/RuleEngine，JSON 导入、启停）
- 文件化章节正文存储（Application Support/ChapterContents）与缓存暂停/恢复
- Cloudflare 清算（最小实现）：403/503 时隐形 WKWebView 解算并注入 cookies
- Wenku8/JM/自定义代理配置

当前仍不是 Android 全功能等价版本：完整 Android Sources/Rules 规则引擎、可靠持续后台下载/暂停恢复（BGTask 仅机会调度）和真机验证仍待实现。EPUB 导入为无第三方依赖的最小实现（ZIP 中心目录 + Compression 解压 + XMLParser 解析 OPF/spine），不支持 DRM 加密书籍。

## 开发环境

项目目标为 iOS 16，使用 macOS + Xcode 15 或更高版本构建。当前已在虚拟 macOS（macOS 13.7.8、Xcode 15.2、iPhoneOS SDK 17.2）验证真机 Release 构建和 iPhone 15 模拟器 Debug 构建；Windows 侧通过 `sync_build_ios.py` 同步源码并调用该虚拟 Mac 构建。

## 构建

工程已包含 `Shijuan.xcodeproj`，无需重新创建项目。在 macOS 上可执行：

```bash
xcodebuild -project Shijuan.xcodeproj -scheme Shijuan \
  -sdk iphoneos -configuration Release \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

输出为未签名 `.app`；将其放入 `Payload/` 并压缩为 `.ipa` 后，再使用自己的开发者证书签名。Windows 环境可使用仓库根目录的 `sync_build_ios.py` 同步到已配置的 macOS 构建机。
