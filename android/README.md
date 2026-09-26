# Android 源码

这是纯 Kotlin Android 宿主工程，使用 Jetpack Compose 和官方 MiuiX `v0.8.8`，不再依赖 Capacitor、WebView 或 Node.js。

主要模块：

- `ui/StudioApp.kt`：MiuiX 主题、书架、探索、创建流程、章节选择、导出进度和设置页面
- `ui/BookshelfScreen.kt`：本地 EPUB/Wenku8 书架（封面 + 书名）
- `ui/BookActionsDialog.kt`：书籍操作二级界面
- `ui/BookDetailScreen.kt`：封面大图与独立元数据控件
- `ui/cover/`：封面加载、缓存与全屏缩放预览
- `ui/SettingsScreen.kt`：设置总览与五个二级页面
- `ui/ConfigSection.kt`：配置导入导出（凭据安全的标量模型）
- `settings/ConfigTransfer.kt`：配置编解码、逐字段校验与写入
- `reader/OnlineReaderSource.kt`：wenku8 目录与正文装配为 `ReaderBook`
- `reader/OnlineChapterCache.kt`：在线章节缓存（50 章 LRU、墓碑、原子写）
- `reader/RemoteImage.kt`：正文插图加载，复用封面加载器与限流客户端
- `ui/NotificationRoute.kt`：通知点击路由的纯函数决策层
- `ui/ExploreScreen.kt`：Wenku8 数据源探索和搜索
- `ui/ReadingStatsScreen.kt`：阅读时长和阅读统计
- `ui/StudioViewModel.kt`：应用状态、搜索、详情和主题交互
- `settings/`：DataStore 应用主题、阅读设置、搜索历史和阅读进度
- `auth/LoginActivity.kt`：仅用于 wenku8 登录和验证码的 WebView
- `core/`：URL 白名单、OkHttp Cookie/限流、GBK 解码、搜索和 Jsoup 解析
- `core/ExploreRepository.kt`：数据源抽象和 Wenku8 探索页
- `core/CatalogCrawler.kt`：公开书目抓取（无 Cookie）
- `core/CatalogIndex.kt`：本地书目索引与搜索
- `data/`：DataStore 书架、阅读统计和设置持久化
- `epub/EpubBuilder.kt`：EPUB 3 / NCX 与本地图片打包
- `reader/`：EPUB 容器/目录解析、阅读器、背景、字体、沉浸模式和章节导航设置
- `service/ExportJobManager.kt`：串行任务队列、实时进度、取消和历史恢复
- `service/ExportNotificationService.kt`：前台导出通知
- `file/EpubFileStore.kt`：保存到 `Download/EPUB` 和系统分享

## 构建

需要 JDK 21、Android SDK Platform 36 和 Build Tools 36.1.0：

```powershell
cd android
.\\gradlew.bat testDebugUnitTest assembleDebug
```

GitHub Actions 会自动安装 SDK、Gradle 9.4.1，并上传 debug APK。推送 `v*` 标签时，APK 会自动附加到 GitHub Release。

正式发布前请配置自有 keystore；当前仓库不包含签名密钥。
