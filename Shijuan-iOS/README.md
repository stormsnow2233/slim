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

## 最近完善

- 修复 TXT/PDF/EPUB 导入后阅读器正文无法读取的问题
- 修复整本缓存会把未下载章节误标记为已缓存的问题
- 补充阅读进度记忆、封面与简介持久化、GB18030 文本解码
- 增强规则书源对阅读 3.0 `searchUrl`、GET/POST 搜索和详情封面的兼容

## 开发环境与 GitHub Actions 构建

项目目标为 iOS 16。仓库的 GitHub Actions 会在 `Shijuan-iOS/` 变更推送到 `main`/`master`、相关 Pull Request，以及手动触发时，使用 macOS runner 和 Xcode 构建未签名 IPA。

在 GitHub 仓库的 **Actions → iOS Build → Run workflow** 可手动构建。完成后进入对应运行页面的 **Artifacts**，下载 `Shijuan-iOS-unsigned-IPA`。该 IPA 未签名，安装到真机或发布前仍需使用 Apple 开发者证书签名。

## 构建

工程已包含 `Shijuan.xcodeproj`，无需重新创建项目。在 macOS 上也可执行：

```bash
xcodebuild -project Shijuan.xcodeproj -scheme Shijuan \
  -sdk iphoneos -configuration Release \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

输出为未签名 `.app`；将其放入 `Payload/` 并压缩为 `.ipa` 后，再使用自己的开发者证书签名。iOS 构建需要 macOS/Xcode；Windows 上建议通过上述 GitHub Actions 工作流构建。
