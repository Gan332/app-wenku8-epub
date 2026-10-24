# Android 源码

这是纯 Kotlin Android 宿主工程，使用 Jetpack Compose 和官方 MiuiX `v0.9.4`，不再依赖 Capacitor、WebView 或 Node.js。

主要模块：

- `ui/StudioApp.kt`：MiuiX 主题、书架、探索、创建流程、章节选择、导出进度和设置页面
- `ui/BookshelfScreen.kt`：本地 EPUB/Wenku8 书架（封面 + 书名 + 折叠分组 + 排序入口）
- `ui/BookshelfSort.kt`：书架排序纯函数（`sortBookshelf`，置顶恒在最前、全序确定）
- `ui/ReadingHomeScreen.kt`：阅读首页（继续阅读 / 最近在读 / 最近阅读，默认首屏）
- `ui/BookmarkCenterScreen.kt`：跨书书签中心（按书分组、带正文摘录）
- `ui/cover/`：封面加载、缓存与全屏缩放预览
- `ui/SettingsScreen.kt`：设置索引（分组卡 + 分类侧栏）与九个二级页
- `ui/ConfigSection.kt`：配置导入导出（凭据安全的标量模型）
- `settings/ConfigTransfer.kt`：配置编解码、逐字段校验与写入
- `reader/XyReaderActivity.kt`：本地 EPUB 阅读器宿主（装配仓库与页面源，见下）
- `reader/XyReaderBridge.kt`：阅读器数据桥——DataStore 实现 + EPUB → 排版引擎
- `reader/ReaderUiState.kt`：阅读交互状态与三个纯函数（菜单栏判据/返回键决策/面板互斥），
  由 `CoreSmokeTest` 锁定；旧渲染层 `ReaderScreenCore` 与 `ReaderActions` 已于 0.19.0-alpha02 移除
- `reader/OnlineReaderActivity.kt` + `reader/OnlinePageSource.kt`：wenku8 在线阅读宿主，
  已接入 xy-reader `ReaderScreen`，按章增长页轴并保存起始章进度
- `reader/OnlineReaderBridge.kt`：在线阅读专用 `ReaderRepository` 与同步页面源打开器
- `reader/OnlineReaderSource.kt`：wenku8 目录与正文装配为 `ReaderBook`
- `reader/OnlineChapterCache.kt`：在线章节缓存（50 章 LRU、墓碑、原子写）
- `reader/RemoteImage.kt`：正文插图加载，复用封面加载器与限流客户端
- `reader/EpubReaderRepository.kt`：EPUB 解析（Rust 原生结构解析 + Jsoup + 回退链）
- `ui/NotificationRoute.kt`：通知点击路由的纯函数决策层
- `ui/ExploreScreen.kt`：Wenku8 数据源探索和搜索
- `ui/ReadingStatsScreen.kt`：阅读时长和阅读统计
- `ui/PageChrome.kt`：页面外框的**单一决策点**（`shellPage` / `chromeTitle` / `upTarget`）——
  顶栏标题、返回图标按钮、系统返回键、底部导航显隐全部从这里读，
  由 `PageChromeTest` 锁定层级优先级与逐级后退（0.19.0-alpha05）
- `ui/StudioViewModel.kt`：应用状态、搜索、详情和主题交互
- `settings/`：DataStore 应用主题、阅读设置、搜索历史和阅读进度
- `auth/LoginActivity.kt`：仅用于 wenku8 登录和验证码的 WebView
- `core/`：URL 白名单、OkHttp Cookie/限流、GBK 解码、搜索和 Jsoup 解析
- `core/ExploreRepository.kt`：数据源抽象和 Wenku8 探索页
- `core/CatalogCrawler.kt`：公开书目抓取（无 Cookie）
- `core/CatalogIndex.kt`：本地书目索引与搜索
- `data/`：DataStore 书架、阅读统计和设置持久化
- `epub/EpubBuilder.kt`：EPUB 3 / NCX 与本地图片打包
- `service/ExportJobManager.kt`：串行任务队列、实时进度、取消和历史恢复
- `service/ExportNotificationService.kt`：前台导出通知
- `file/EpubFileStore.kt`：保存到 `Download/EPUB` 和系统分享

## 阅读器（`com/xyreader/`）

本地 EPUB 阅读器来自 [TerryYu12/xy-reader](https://github.com/TerryYu12/xy-reader)（MIT），
**保留上游包名**放在 `java/com/xyreader/`，便于日后与上游比对：

- `core/`：`PageSource` / `Chapter` 契约、`ReaderPrefs`、`ArchiveFactory`、`ReaderGraph`
- `archive/`：`NovelPageSource`（`StaticLayout` 预分页 → 透明底位图页）与各格式页面源
- `reader/`：`ReaderScreen`（翻页 / 上下滚动 / 手势 / 目录 / 书签）、`ReaderViewModel`
- `ui/`：`ReaderConfigScreen`、`NovelSpacingControls`、`Common`

三条边界（详见仓库根 `AGENTS.md` §4.8）：

1. `com/xyreader/` **不得反向依赖** `com.example.hyperreader`；阅读器只认
   `com.xyreader.core.ReaderRepository`，实现由 `reader/XyReaderBridge.kt` 提供。
2. **EPUB 解析仍走本工程管线**（`EpubReaderRepository`），通过 `sourceOpener` 注入，
   没有改用上游的 `NovelTextExtractor.parseEpub`。
3. 上游的 Room / Coil / WebDAV / Google Drive / 书库管理界面**未引入**。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools 37.0.0：

```powershell
cd android
.\\gradlew.bat testDebugUnitTest assembleDebug
```

GitHub Actions 会自动安装 SDK、Gradle 9.8.1，并上传 debug APK。推送 `v*` 标签时，APK 会自动附加到 GitHub Release。

## 日常安装（固定下载入口）

main 上每次通过全部检查的构建都会覆盖到固定标签 `ci-latest`，地址不变，可直接存书签：

```text
https://github.com/Gan332/app-wenku8-epub/releases/download/ci-latest/app-release.apk
https://github.com/Gan332/app-wenku8-epub/releases/download/ci-latest/app-debug.apk
```

- 只有单元测试与 Rust 测试**全部通过**的构建才会覆盖它，因此这个地址永远是能装的版本；
  失败的推送不会把它变成半成品。
- 它是**预发布**（prerelease），且不会顶掉 `v*` 正式版本的 Latest 标记。
- 与 Actions 页面的 artifact 相比：不需要进运行页、不需要解压、不受 14 天过期限制。
- Release 说明里带当次的提交号、versionName/versionCode 与两个 APK 的 SHA-256。

命令行安装（需要 `adb`）：

```powershell
Invoke-WebRequest -Uri 'https://github.com/Gan332/app-wenku8-epub/releases/download/ci-latest/app-release.apk' -OutFile "$env:TEMP\app-release.apk"
adb install -r "$env:TEMP\app-release.apk"
```

正式发布前请配置自有 keystore；当前仓库不包含签名密钥。
