# AGENTS.md

文库 EPUB 工坊（`app-wenku8-epub`）的智能体工作说明。本文件记录项目约定、构建验证方式和远程同步流程，任何自动化代理在修改仓库前都应先阅读。用中文输出

## 1. 项目概况

一个把 wenku8 公开轻小说整理为 EPUB 的应用，包含两套实现：

- `public/` + `src/`：本机单用户 Web 版本（Express + 原生前端）
- `android/`：独立的原生 Android 版本（Kotlin + Jetpack Compose + MiuiX）
- `rust/epub-core/`：EPUB 结构解析的 Rust 核心（编译为 Android JNI 的 `libepub_core.so`）
- `data/`、`output/`：Web 版运行时的书目数据与 EPUB 产物目录（已 gitignore）

当前主要发版对象是 **Android 原生应用**。Android 版本不需要 Node.js 服务，WebView 仅用于 wenku8 登录。

当前版本：`0.18.0-alpha01`（versionCode 25，见 `android/app/build.gradle.kts`，以该文件为准）
发布节奏：0.18.x 为**预发布**（prerelease，标签带 `-alphaNN`），功能收敛后再转正式版。
包名：`com.example.hyperreader`（由 `com.wenku8.epubstudio` 于 0.9.0 重命名，非原地改名，升级需数据迁移）

仓库地址：`https://github.com/Gan332/app-wenku8-epub`

## 2. 技术栈

### Android

- Kotlin
- Jetpack Compose
- MiuiX `v0.9.4`（依赖坐标 `top.yukonga.miuix.kmp:miuix-ui` + `miuix-core` + `miuix-icons`）
- ~~Material3~~（0.9.3 起全量移除，界面统一 MiuiX，material 依赖已从构建中断开）
- OkHttp
- Jsoup
- DataStore Preferences
- AndroidX Security Crypto（Keystore 加密 Cookie）
- AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.6.0 / Compose Multiplatform 1.12.0
- Compile SDK 37 / Target SDK 36 / Min SDK 26
- JDK 21

### 0.9.x 升级踩过的坑（务必先读）

1. **Maven 坐标在 0.9.0 变了**。旧坐标 `top.yukonga.miuix.kmp:miuix` 在 Maven Central 上止于
   `0.8.8`，0.9.x 只发布在新坐标 `miuix-ui` / `miuix-core`（`miuix-icons` 同版本序列）。
2. **`platforms;android-37` 这个包不存在**。SDK 37 起平台包强制带小版本号，基础包是
   `platforms;android-37.0`（`source.properties` 里 `ApiLevel=37.0`、`IsBaseSdk=true`，
   安装目录 `android-37.0/`）。`build-tools` 对应 `build-tools;37.0.0`。
3. **MiuiX 0.9.4 的 AAR 强制 `compileSdk >= 37`**，`targetSdk` 可以保持 36。
4. **AGP 9.4.1 强制 Gradle >= 9.6.0**。`gradle-wrapper.properties` 和 workflow 里的
   `gradle/actions/setup-gradle` 的 `gradle-version` **两处必须一致**，只改一处会在
   `com.android.internal.version-check` 挂掉。
5. **`kmp.extra` 包已整体删除**，`SuperDialog` → `top.yukonga.miuix.kmp.overlay.OverlayDialog`。
6. 0.9.4 中 **`TextStyles`（14 个字段）、`ThemeController`、`ColorSchemeMode`、`MiuixTheme`
   签名均未变化**，主题层代码不需要为升级而改。


### 数据源

- Wenku8 是唯一注册的数据源
- 通过 `NovelDataSource` 抽象，后续可扩展
- 探索页参考 LightNovelReader 的 `ExplorePageProvider` 思路
- 源站编码为 GBK/GB18030，解析前必须正确解码
- 请求有全局限流、HTTP 429 `Retry-After` 和退避重试

## 3. 目录结构

```text
rust/epub-core/               Rust EPUB 结构解析 → libepub_core.so（JNI 快路径，见 §4.10）

android/app/src/main/java/com/example/hyperreader/
├── Wenku8Application.kt         依赖容器：设置、书架、统计、数据源、任务
├── auth/                        wenku8 登录 WebView（LoginActivity）
├── core/                        URL 白名单、HTTP、Cookie、解析、书目索引、探索数据源
├── data/                        DataStore：设置、书架、阅读统计
├── epub/                        EPUB 3 + NCX 打包
├── file/                        EPUB 保存、分享、字体导入
├── http/                        全局限流 HttpRateLimiter
├── model/                       业务模型（Book、Chapter、BookshelfEntry 等）
├── reader/                      EPUB 解析、阅读器宿主（XyReader/OnlineReader）、数据桥、进度
├── service/                     导出任务队列、前台进度通知
├── settings/                    设置模型与 DataStore 仓储、配置导入导出
└── ui/                          Compose 页面、ViewModel、封面缓存（ui/cover/）

android/app/src/main/java/com/xyreader/     ← xy-reader 阅读器（保留上游包名，见 §4.8）
├── core/                        契约与配置：PageSource/Chapter、ReaderPrefs、ArchiveFactory、ReaderGraph
├── archive/                     页面源：NovelPageSource（排版引擎）、各压缩包/PDF/MOBI 页面源
├── reader/                      ReaderScreen（阅读界面）、ReaderViewModel、PageZoom
└── ui/                          ReaderConfigScreen、NovelSpacingControls、Common

android/app/src/main/java/io/nightfish/lightnovelreader/api/   ← LNR 书源抽象（见 §4.5.1）
├── web/                         书源契约、注册表、探索页/搜索提供者与过滤器
├── book/ explore/ content/      书本/章节模型、内容组件、探索页模型
└── identifier/ serializer/ xml/ text/ image/ util/ error/ plugin/
```

入口：Manifest 声明的启动 Activity 是 `.MainActivity`，但它**不是独立文件**，类定义在
`ui/StudioApp.kt`（`proguard-rules.pro` 的 keep 规则也指向它）。找入口别搜 `MainActivity.kt`。

页面：**书架**（含书籍操作二级菜单）、**探索**（书籍详情全屏页）、**设置**（二级：主题与外观/阅读器设置/阅读统计/书目缓存/关于）。0.17.0 起**导出**不再是一级入口，而是书架卡片菜单里的次级动作，其向导是全屏覆盖层（`StudioUiState.exportStep != null`）。

## 4. 关键设计约束

### 4.1 主题统一

首页和阅读器必须共用 `ui/AppMiuixTheme.kt`，禁止在阅读器里硬编码 `ThemeController` 或 `ColorSchemeMode`。

- 主题模式、动态色、强调色来自 DataStore
- MiSans 字体通过 `AppMiuixTheme` 统一注入
- 阅读正文背景可独立自定义，但控件、BottomSheet、工具栏跟随应用主题

### 4.2 安全与源站边界

- 只允许 wenku8.net / wenku8.cc / wenku8.com
- 阻止本机和内网地址，重定向后重新校验
- Cookie 使用 Keystore 加密保存，过期需清理
- EPUB 解析必须限制解压大小、条目大小，并拒绝路径穿越


**会话链路**（合规路径，依据 §4.5.3 第2 条）：只携带 `Wenku8SessionStore` 里
**用户本人**的会话 Cookie，经 `Wenku8HttpClient` 访问（限流/退避不变），实现分别在
`core/Wenku8SearchProvider`（搜索页）、`core/ExploreRepository`（榜单/标签）。
返回登录页即清会话并提示重新登录，

三条链路都用 `core/Wenku8SessionStore.kt` 里的 `SessionGate`（`hasSession` / `clear`）
拿会话，而不是直接依赖实现类——实现类要 Context + Keystore，JVM 单测里构造会 `Stub!`。

搜索页的分页口径（0.15.0 起）：请求带 `page=`（从 1 开始），**页码以请求参数为准**，
`em#pagestats` 只用于推断总页数（认 `2/5`、`共5页`），否则退回「下一页」锚点；
请求序号（`StudioViewModel.searchRequestId`）保证旧响应不覆盖新结果。

免登录抓取（上表白名单）由 `CatalogCrawler` / `CatalogRepository` 负责，必须使用
**独立的无 Cookie 客户端**，——与会话链路分开，
两条链路不要混用。

用户可自行为公开页配置**第三方中继**（见 §4.11）；那是传输路径的可选替换，
**不改变本节的端点白名单，也不新增任何接口**。

### 4.2.1 客户端协议与 403 口径（0.18.0）

**协议**：HTTP/2 优先（ALPN 协商，回落 HTTP/1.1）、TLS 1.3 优先（回落 1.2），
在 `core/Wenku8NetProtocols.kt` 显式声明并由 `NetProtocolsTest` 锁定。不要手加
`Accept-Encoding: br`（OkHttp 只自动处理 gzip）；User-Agent 诚实标识，**不伪装浏览器**。

**403 排查口径**（实测于 2026-10）：匿名公开页前置 Cloudflare，对部分网络会返回
403「Attention Required」（约 5.5KB 拦截页）：

- 与UA / Referer / Accept-Language **无关**（补齐浏览器头仍 403），不要试图伪装绕过；
- 是**波动**的：同一网络实测出现过连续 8 次 403、也出现过 200；
- 图域`img.wenku8.com` 走 nginx、不经 Cloudflare，**不受影响**（封面/插图链路正常）；
- `wenku8.com` 镜像当前长期无响应，白名单里留着但不要指望。

应用侧无需为 403 改逻辑：`Wenku8Parser.looksLikeChallenge()` 会转成 `UPSTREAM_CHALLENGE`，
用户可用「设置 → 网络 → 测试公开端点」判断当前该直连还是走自建中继（§4.13）。

### 4.3 状态与持久化

- 设置、书架、阅读统计、阅读进度均使用 DataStore，不引入 Room
- `preferencesDataStore(name = "wenku8_settings")` 全项目只有一个实例，位于 `data/AppDataStore.kt`
- 阅读统计只在阅读器前台 `onStart` 到 `onStop` 之间累计，单次最长 30 分钟
- 书架条目按 `id`/`bookId` 去重

### 4.4 Compose 约定

- 按钮最小触摸区域 48dp（用 `UiDimens.touchMin`，**不要**写裸 `48.dp`）
- **尺寸令牌**（0.18.0）：控件高度、页面边距、字号一律取 `ui/UiDimens.kt` 的令牌
  （`touchMin` / `rowMin`、`spaceXXS…spaceXL`、`captionSmall…display`、`MOTION_*`）；
  只有装饰性小值（进度圈直径、圆角等）允许直接写数值
- **动效**（0.18.0）：统一用 `ui/Motion.kt`（交错入场/按压/展开/shimmer），
  时长经 `Motion.duration()` 换算，**必须尊重系统「移除动画」**（`ANIMATOR_DURATION_SCALE=0` 时归零）；
  长列表交错延迟只对前 `Motion.STAGGER_VISIBLE_LIMIT` 项累加，否则靠后的项会等几秒才出现
- 长列表使用 `LazyColumn`，不要一次性构建全部条目
- 读图/解析类操作放 `Dispatchers.IO`
- MiuX 组件**唯一**：0.9.3 起 material/material3/material-icons 全量移除，
  有单测 `mainSourcesUseNoMaterialComponents` 守卫，源码出现 material 引用直接失败
- 主题色从 `MiuixTheme.colorScheme` 获取，不要写死颜色

### 4.5 探索页自动获取（0.14.0 起；**取代** 0.7.0 的「被动触发」）

0.7.0–0.13.0 探索页是**被动触发**的。0.14.0 起改为**自动获取**：

- 进入探索页即自动抓取公开榜单，并把**本地索引没有的书自动走书源补全**
  （`articleinfo.php`），逐个追加进列表；不再要求用户手动更新缓存
- （`StudioViewModel.EXPLORE_AUTO_FETCH_LIMIT = 20`）：全局限流是
  1 秒/请求，一次榜单动辄 30+ 本，全抓会让用户干等
- 「更新书目缓存」仍保留在设置 → 书目缓存，用于一次性批量补全

仍然生效的边界：

- 所有请求必须走 `Wenku8HttpClient`（全局限流 + 429 退避 + 重定向复校验），
- 书源契约里的搜索仍走**本地书目索引**；站内 `search.php` 搜索在搜索页走会话链路，
  两条并存（见 §4.5.3 第 2 条）
- `CatalogRepository.expandAuthor` 仍只在用户显式点击时调用

### 4.5.1 书源体系（0.14.0 起）

LNR 的书源抽象在 `io.nightfish.lightnovelreader.api`（102 文件），本工程的实现是
`core/Wenku8BookSource.kt`：

- `Wenku8BookSource` 实现 `WebBookDataSource`，取数一律走 `Wenku8HttpClient` +
  `Wenku8SessionStore`（用户自己的会话 Cookie），**不沿用上游的 `defaultplugin/wenku8`**
  ——上游那份硬编码了作者账号 Cookie 且用 Ktor 绕过限流，理由见 `THIRD_PARTY_NOTICES.md`
- 装配在 `Wenku8Application.bookSource`；探索页/详情页从这里取数
- `permits` 压到 2：节奏由 `HttpRateLimiter` 决定，放大并发只会让请求排队

### 4.5.2 标签浏览（0.14.0 起；0.14.x 接入官方 `tags.php`）

探索页有「按标签浏览」区，标签两路合并、官方在前（`StudioViewModel.mergedTags`）：

- **官方标签**（登录后）：`tags.php` 首页的标签锚点，由 `Wenku8DataSource.officialTags()`
  用**用户本人会话**抓取，`Wenku8Parser.parseTagList` 按 GBK 解码 `t` 参数；
  冷启动已有会话与登录返回（`refreshSession`）时各拉一次，失败静默
- **本地索引标签**（始终）：已抓 `articleinfo.php` 的「作品Tags」由 `CatalogIndex.tagIndex`
  聚合（`CatalogRepository.tagList()` / `searchTag()`），未登录时的唯一来源，断网可用
- 选中标签（`StudioViewModel.selectTag`）：本地结果**立即**显示；已登录时再用
  `tags.php?t=X`（`Wenku8DataSource.tagBooks`，交互档节流）的结果合并覆盖，
  服务端失败保留本地结果、只提示原因



### 4.6 探索详情 ≠ 导出向导（0.11.0 起；0.17.0 起导出改为二级页）

「看一眼书」和「导出这本书」是两条**互不相干**的链路，不要互相调用：

| | 探索详情（含书架书详情） | 导出向导 |
| --- | --- | --- |
| 入口 | 探索页点书 / 书架卡片菜单「查看详情」 | 书架卡片菜单「导出 EPUB」 |
| 数据 | `ExploreDetailRepository`（`articleinfo.php` + 目录页） | `ExportJobManager.parseSource` → `resolveForExport()` |
| 节奏 | `Wenku8HttpClient.fetchTextInteractive()` | `Mode.BATCH`（默认） |
| 状态 | `StudioUiState.exploreDetail*` | `StudioUiState.exportStep` + `book` / `index` / `selectedIds` |
| 产物 | `ExploreBookDetail`（展示用） | `Book` + `BookIndex`（选章导出） |

硬约束：

- 探索详情**不得**调用 `resolveForExport()`，也**不得**改动 `book` / `index` / `exportStep`
- 导出向导**不得**在解析前假定有详情数据；解析成功即默认全选并进入选章节
- 探索详情走 `Wenku8HttpClient.fetchTextInteractive()`，批量路径继续用 `fetchText()`
- 详情页目录页失败可降级（`ExploreBookDetail.indexError` 非空、`chapters` 为空），
  不得因此整页报错
- `HttpRateLimiter.Mode.INTERACTIVE` 只是「允许突刺」，**不降低批量节奏**：
  6 秒滑动窗口内最多 6 次，超限自动退回 1 秒/请求

### 4.6.1 阅读优先（0.17.0 起）

信息架构的第一原则：**读者打开应用是为了读，不是为了导出**。为此定下几条硬约束：

1. 底部导航**只有三项**（书架 / 探索 / 设置）；任何导出入口都不得回到一级导航。
   新增功能时不要为了“放得下”而给导出一个 tab。
2. **点书即读**：书架卡片主体点击 = 打开阅读器（本地 EPUB → `XyReaderActivity`，
   远程书 → `onlineReaderIntent`，并 `markShelfRead`）。详情与导出是卡片「更多」菜单里的条目。
3. **详情只有一个实现**：书架书与探索书共用 `ExploreDetailScreen` + `ExploreDetailRepository`；
   不允许再写第二套详情 UI（0.17.0 已删掉 `BookDetailScreen`）。
4. **导出向导是覆盖层**：`StudioUiState.exportStep != null` 时全屏接管（`StudioUiState.exportStep`
   为 null 表示不在向导里）；状态迁移集中在纯函数 `ExportWizard`，便于单测。
5. **导出记录跟着导出走**：独立成页，入口在设置 → 概览与通知栏路由，不占书架页顶部。
6. 解析逻辑（`ExportJobManager`）与阅读解耦：阅读走 `OnlineReaderActivity` 自己的目录预取与分页源，
   不经过 `parseSource()` / `resolveForExport()`。

### 4.7 封面加载

自研 `ui/cover/CoverRepository`，**不引入 Coil/Glide**：第三方图片库会自建
OkHttp 客户端，从而绕过 `Wenku8HttpClient` 的全局 1 秒限流与 429 退避。

- 内存 LruCache + 磁盘 `cacheDir/covers`
- 下载走共享的无 Cookie 客户端
- 按目标宽度下采样，避免 OOM
- 缩放手势自行实现（`detectTransformGestures`），不引入 panpf

### 4.8 阅读器：XY reader（0.13.0 起）

本地 EPUB 阅读器来自 [TerryYu12/xy-reader](https://github.com/TerryYu12/xy-reader)（MIT），
代码在 `com/xyreader/`，**保留上游包名**便于日后与上游比对。0.12.0 引入的
LightNovelReader 阅读器（126 个文件）已在 0.13.0 整体移除。

它是**页面位图**阅读器，不是 Compose 文本排版：`PageSource` 只暴露
`pageCount` + `renderPage(i): ImageBitmap`；文字小说走 `NovelPageSource`，用
`StaticLayout` 把段落预分页后把每页画到**透明底 Bitmap** 上，pager 背景直接透出。

三条不能违反的边界：

1. **`com.xyreader` 不得反向依赖 `com.example.hyperreader`**。阅读器只认
   `com.xyreader.core.ReaderRepository`（8 个成员），实现由
   `reader/XyReaderBridge.kt` 提供，经 `ReaderGraph.install(...)` 在 `setContent` 前安装。
2. **EPUB 解析仍走本工程管线**。`ReaderViewModel` 的 `sourceOpener` 由宿主注入
   `XyEpubPageSourceOpener`（内部调 `EpubReaderRepository` 的 Rust + 回退链），
   **不要**改回上游的 `NovelTextExtractor.parseEpub`。
3. **不引入上游的 Room / Coil / WebDAV / GDrive / 书库管理界面**。远程链路已整体删去
   （`RemoteArchiveSources` / `RemoteGdriveSources` / `HttpRangeChannel` 及其入口）；
   本工程数据源只有 wenku8。

数据落点全部在既有 `wenku8_settings` DataStore（**实例唯一**，AGENTS §4.3），键前缀 `xy_reader_`：
阅读配置 `xy_reader_prefs`（单键 JSON）、书签 `xy_reader_bookmarks`、收藏
`xy_reader_favorites`、页进度 `xy_reader_page_<bookId>`。

进度口径：上游是「页 / 总页数」，本工程 `ReadingProgress` 是「章 + 段」，两者**不换算**
（分页依赖字号与边距）。页进度单独存并作为续读唯一依据，同时刷书架 `recordRead`。

在线阅读（OnlineReaderActivity）已完成 0.14.0 step 3/3，走 xy-reader：
OnlinePageSource 按章抓取后，用与本地 EPUB 完全同一套 StaticLayout 分页生成位图页，
并以 growable = true + loadMore 在接近末尾时追加章节。Activity 先在 IO 协程预取目录，
再安装 OnlineReaderRepository 并把 OnlinePageSourceOpener 注入 xy-reader 的 ReaderScreen。
在线页进度保存在 xy_reader_online_progress_<bookId>，必须同时保存页轴起始章与当前章，
恢复时先按起始章重建页轴再应用页码；旧 ReadingProgress 只用于第一次迁移时选择起始章，
不做页↔章换算。在线正文插图经 CoverRepository.loadBytes 下载，仍走共享 Wenku8HttpClient
限流链路。旧 reader/ReaderScreen.kt（ReaderScreenCore）与 ReaderActions 仍保留给历史代码
和单测兼容，但在线入口不再调用它们。

MiuiX 化对照（上游全是 material3，本项目禁止 material，有守卫单测）：
`Text/Icon/IconButton/Button/Slider/Switch/Surface/Card/RadioButton/CircularProgressIndicator`
签名基本兼容，只换 import；`TextButton` 改收 `text: String`；`ModalBottomSheet` →
`overlay.OverlayBottomSheet(show = true, onDismissRequest = ...)`；
`MaterialTheme.colorScheme` → `MiuixTheme.colorScheme`（无 `surfaceContainerLow`，用
`surfaceContainerHigh`；`onSurfaceVariant` → `onSurfaceVariantSummary`）；
`MaterialTheme.typography` → `MiuixTheme.textStyles`；图标用 `MiuixIcons`（本项目已有的
`Back` / `ListView` / `Tune` / `ChevronBackward` / `ChevronForward`）或
`ImageVector.vectorResource(R.drawable.*_24px)`；无 `FilterChip`，用 `ReaderScreen.kt`
末尾的本地同名实现。

### 4.9 双 EPUB 导出引擎（0.14.0 起）

`settings/SettingsModels.kt` 的 `EpubEngine` 枚举有两个引擎，产出同样的 EPUB 3.3
结构、调用契约一致，由 `ExportJobManager` 按配置选择：

- `CLASSIC`：自研 `epub/EpubBuilder`，保留已 sanitize 的行内强调标签，默认
- `POTATO`：LightNovelReader 的 `:epub` 模块（`io.nightfish.potatoepub`，Apache-2.0，源码整体 vendored 进仓库），正文按段落纯文本写入

切换入口：设置页「EPUB 导出引擎」（`SettingsRepository.EXPORT_ENGINE`）。
探索详情页有**两个导出小按钮**（`StudioViewModel.exportExploreDetail(engine)`），
各对应一个引擎，**不受全局设置影响**；其它导出路径走全局配置。

### 4.10 Rust 解析快路径（`rust/epub-core`）

`EpubReaderRepository.parseArchive` 优先走 JNI 快路径（`EpubNative.parse` →
`libepub_core.so`），失败任何一步（so 缺失、结构解析失败、JSON 失败、超限）都回退
`parseArchiveLegacy`（zip + Jsoup）。约束：

- Rust **只做结构解析**（container/OPF/nav/NCX + 读出章节 HTML）；HTML → 内容块
  仍走 Kotlin Jsoup 的 `parseBlocks`，保证两条路径行为一致
- native 是**加速路径，不是单点依赖**，改 Rust 时必须保留 legacy 回退
- `so` 不入库：CI 用 `cargo-ndk` 在构建期产出到 `android/app/src/main/jniLibs/`
  （已 gitignore）；Rust 只依赖 `zip`/`serde`/`jni`，零 XML 库漂移风险
- Rust 测试在 CI 跑（`working-directory: rust` 的 `cargo test`），改动 `rust/` 时同样要过 CI

### 4.11 第三方中继（0.16.0 起，仅公开页）

用户可为「免登录公开页」链路自选**第三方中继**（设置 → 概览 →「第三方中继（仅公开页）」）。
起因是源站对部分网络返回 Cloudflare 403 直连不通；中继只是**换传输路径**，
不改变端点集合、限流与 429 退避，也**不允许任何形式的登录规避**。

实现全在 `core/Wenku8Endpoint.kt`（单例路由状态），硬约束五条：

1. **端点必须用户填写，禁止内置猜测值**。MewX 官方 App 的中继地址从未公开
   （1.x 已移除相关常量，`wenku8.mewx.org` 只是其前端页），只接受 `https://域名[:端口]`；
   拒绝 http、IP 字面量（含内网）、明文凭据、wenku8 自身域名。默认关闭。
2. **只作用于公开端点**：`Wenku8Urls` 里`articleInfo` / `index` / `sugoi` / `booklist` /
   `authorArticle` 走 `publicBase()`；`search.php` / `toplist.php` / `tags.php` / `login.php`
   与「来源地址」`book()` **永远直连**。
3. **Cookie 不出wenku8**：`Wenku8Url.carriesSession` 是唯一判据，
   `Wenku8SessionStore.cookieJar()` 的存取两侧都用它过滤；中继永远收不到
   `jieqiUserInfo` / `PHPSESSID`。
4. **URL 双向变换**：解析出口`Wenku8Endpoint.restoreToDirect` 把中继 URL 还原成wenku8 原域
   （书架、EPUB 内链、再次抓取都不得指向第三方）；真正下载前用 `toRelayUrl` 改写，
   封面缓存键保持直连 URL。粘贴来源网址也先还原再 `assertAllowed`。
5. **白名单不放宽**：`Wenku8Url.isAllowedHost` 只在「开关开且端点合法」时额外放行中继 host，
   内网拦截、重定向复校验、scheme 与凭据校验一律不变。

设置流由 `Wenku8Application.onCreate` 监听（`relay_base` + `relay_enabled`）后应用；
关开关时自动清空端点。回归见单测 `RelayEndpointTest`。

### 4.12 设置页信息架构（0.18.0，对齐 Kazumi）

设置索引页采用「分类 + 分组」结构（Kazumi 的 `_SettingsCategory` / `_SettingsGroup`）：

- 分类枚举在 `ui/SettingsScreen.kt` 的 `SettingsCategory`，每个分类对应一个
  `SettingsSection` 二级页；`SETTINGS_GROUPS` 决定索引页的分组标题；
- 分类侧栏：宽屏常驻左侧 rail（`SettingsRail`），窄屏用 `OverlayBottomSheet` 抽屉；
- **编辑型设置用Sheet 轻量编辑**（如 EPUB 导出引擎）；长内容（阅读器、外观）仍走二级页；
- 新增分类必须在 `SettingsSection` 加枚举 + 在 `CoreSmokeTest.settingsSectionsCoverEveryCategory`
  更新计数（该测试会卡住遗漏）。

### 4.13 自建 Cloudflare Worker 中继（0.18.0）

`relay/worker.js` 是完整可部署的中继实现，用**用户自己的** Cloudflare 账号部署
（`wrangler deploy`，说明见 `relay/README.md`），App 在「设置 → 网络」填端点。

硬约束（Worker 与 App 两侧都写死，并由契约测试锁定）：

1. **只代理匿名白名单端点**：`articleinfo.php`、`authorarticle.php`、目录页、
   `/zt/sugoi/`、`/zt/booklist/`；
2. **拒绝登录墙内接口**：`login.php`、`search.php`、`toplist.php`、`tags.php`、
   `articlelist.php`、`/api/*`；App 侧先由 `Wenku8Endpoint.isRelayablePath` 拒绝，
   Worker 侧再用 `DENIED` 兜底；
3. **不转发 Cookie / Authorization**，不记录访问日志，不透传客户端 IP；
4. 保留上游 GBK 原始字节（不解码/重编码），5 分钟缓存 + 每 IP 每分钟 60 次令牌桶限流；
5. Worker 与 App 的路径白名单是**同一份契约**，改一边必须改另一边，否则
   `RelayEndpointTest.relayPathContractMatchesWorkerAllowList` 会失败。

不得内置或推荐任何第三方中继端点（包括上游作者的）：它是可选路径，不是默认路径。

## 5. 构建与验证

### 5.1 Web 版（Node，改动 `src/` / `public/` / `server.js` 时）

```powershell
npm ci            # Node >= 20
npm run verify    # = npm run check && npm test && npm run smoke:epub
npm start         # 或 npm run dev（--watch），http://127.0.0.1:3210
```

- 单个测试文件：`node --test test/parsers.test.js`；`npm test` 强制 `--test-concurrency=1`
- `npm run test:live` 会访问真实 wenku8 源站，只在需要验证源站时运行
- 仓库**没有** ESLint / Prettier / tsc 等校验：`npm run check` 只是 `node --check` 语法检查

### 5.2 不要在本地编译 Android

本项目 **不在本地执行 Gradle 构建**，所有 Android 编译和测试通过 GitHub Actions 完成。

工作流：`.github/workflows/android-apk.yml`

- 推送到 `main`（含 PR）：Rust 测试（`cargo test`）+ Kotlin 单元测试 + debug APK + 有签名 Secrets 时的 release APK + 上传 Artifact
- 推送 `v*` 标签：额外附加 APK 到 Release

### 5.3 提交后必须跟踪 CI

推送后用 `gh` 跟踪，失败时读取失败日志并修复：

```powershell
& 'D:\SW\gh\gh.exe' run list --repo Gan332/app-wenku8-epub --workflow android-apk.yml --limit 3 --json databaseId,status,conclusion,headSha,url
& 'D:\SW\gh\gh.exe' run watch <runId> --repo Gan332/app-wenku8-epub --exit-status
& 'D:\SW\gh\gh.exe' run view <runId> --repo Gan332/app-wenku8-epub --log-failed
```

失败修复流程：

1. 读 `--log-failed`
2. 只修复日志中的确切错误
3. 本地 `git commit`
4. 用 Git Database API 推送
5. 重新跟踪新的 run
6. 直到 `conclusion == "success"`

### 5.4 单元测试

测试位于 `android/app/src/test/java/com/example/hyperreader/`，覆盖：

- URL 白名单和书籍 ID 规范化
- 目录、插图标记、书籍元数据和字数解析
- 搜索结果与登录页识别
- 探索 URL 和阅读统计序列化
- EPUB mimetype、OPF 与阅读器回读

新增解析逻辑时，必须同时新增测试夹具。

### 5.5 Release 签名与 R8（0.9.0 起）

release 变体开启 `isMinifyEnabled` + `isShrinkResources`，签名配置**全部来自环境变量**，
密钥与口令**绝不进入版本库**。CI 需要的 4 个仓库 Secret：

| Secret | 内容 |
| --- | --- |
| `HYPERREADER_KEYSTORE` | keystore 文件的 base64 编码 |
| `HYPERREADER_STORE_PASSWORD` | keystore 口令 |
| `HYPERREADER_KEY_ALIAS` | 密钥别名 |
| `HYPERREADER_KEY_PASSWORD` | 密钥口令 |

未配置时 CI 会在脚本内**跳过** release 构建（打印 notice 后 `exit 0`），
**不回退到 debug 签名**，避免产出「看起来正常」的假包。

注意 Secret 与 env 变量名不同：Secret `HYPERREADER_KEYSTORE` 是 base64 内容，workflow 把它
解码到临时文件后，以 `HYPERREADER_KEYSTORE=<文件路径>`（另有 `HYPERREADER_KEYSTORE_B64`）传给
Gradle；`build.gradle.kts` 读的是**文件路径**，不是 base64。

两个必须知道的坑：

1. **不要在 step 级 `if:` 里用 `secrets` 上下文** —— 会导致整个 workflow 校验失败、
   run 没有任何 job（total_count = 0）、`run view --log` 报 `log not found`。
   判断要放在 `run:` 脚本里做。
2. **AGP 9.x 的默认 ProGuard 文件名是 `proguard-android-optimize.txt`**，
   旧的 `proguard-optimize.txt` 会在**配置期**直接报
   `Supplied proguard configuration file name is unsupported`。

**R8 只能在运行期证伪**：`assembleRelease` 通过不代表包能用。被裁掉的
`@Serializable` 生成器、反射读取的 DTO、JSoup 反射都会编译期无感、运行期崩。
改了 `proguard-rules.pro` 或引了新库之后，**release APK 必须真机冒烟**。

## 6. 推送方式（重要）

本机 `git push` 在 Windows Schannel 下不可靠，**必须使用 GitHub Git Database API 推送**：

1. `gh api repos/<repo>/git/ref/heads/main` 取当前 `parent` SHA
2. `gh api repos/<repo>/git/commits/<parent>` 取 `base_tree`
3. 对每个改动文件 `POST /git/blobs`（base64 内容）
4. `POST /git/trees`（`base_tree` + 文件树）
5. `POST /git/commits`（message + tree + parents）
6. `PATCH /git/refs/heads/main`（新 SHA）

`gh` 可执行文件：`D:\SW\gh\gh.exe`

**已有脚本**：`scripts/push-via-api.sh`，封装了上面 6 步，用法：

```bash
# 首次推送（LOCAL_BASE = 内容等价于远端 main 的本地提交）
LOCAL_BASE=9bce89f bash scripts/push-via-api.sh

# 之后每次增量推送，LOCAL_BASE 用「上一次推送时本地对应的那个提交」
LOCAL_BASE=eee860e bash scripts/push-via-api.sh
```

### 6.1 API 推送会让远端 SHA 与本地分叉（最容易踩）

API 创建的 commit **SHA 与本地不同**（内容等价，但换行可能被归一化成 CRLF）。
后果：

- `git merge-base --is-ancestor <remote> <local>` **会失败**，不能用它判祖先；
- 不能用「远端 SHA == 本地 SHA」判断是否需要推送；
- 必须以**远端 tree** 作 `base_tree`，并以一个「内容等价的本地提交」作 diff 基准，
  否则会把 CRLF 差异回冲到本地内容上。

脚本已内置这个策略（`LOCAL_BASE` 环境变量），增量推送只需改这一个值。

### 6.2 其它已知坑

- `gh api ... --input -` + stdin 管道、bash 进程替换 `<(...)` 在本机 Git Bash 下**不可用**
  （`open /proc/<pid>/fd/63: cannot find the path`）。必须先把 JSON 写进临时文件再 `--input <文件>`。
- Node `spawnSync` 调 `gh.exe` 会偶发 `EBUSY`（安全软件锁文件），**改用 bash 直接调用**更稳定。
- 某个 `gh api` 响应可能为空导致 `unexpected end of JSON input`，此时先检查远端 SHA 是否已更新，
  再决定是否重试，避免重复提交。

## 7. 发版流程

0. 选预发布还是正式版：`v0.x.y-alphaNN` / `-betaNN` / `-rcN` 打 **prerelease** 标签，
   `v0.x.y` 才是正式版；版本号语义化、不覆盖已发布标签
1. 更新 `android/app/build.gradle.kts`：
   - `versionCode` +1
   - `versionName` 改为新版本
2. 更新 `CHANGELOG.md`
3. 新增 `docs/RELEASE_NOTES_vX.Y.Z.md`
4. 更新 `README.md` 和 `android/README.md`
5. 推送并确认 main 构建成功
6. 创建 Release：

```powershell
& 'D:\SW\gh\gh.exe' release create 'v0.6.0' --repo Gan332/app-wenku8-epub --target 'main' --title '文库 EPUB 工坊 v0.6.0' --notes $notes
```

7. 跟踪标签构建，确认 APK 上传
8. 核对 Release 资产：

```powershell
& 'D:\SW\gh\gh.exe' release view v0.6.0 --repo Gan332/app-wenku8-epub --json url,assets
```

版本规则：语义化版本，不覆盖已发布标签。

## 8. 分支与提交信息

分支：`docs/ENGINEERING_SOP.md` 写了 develop/feature 分支流程，但 `develop` 已落后 `main`
80+ 提交且不再使用 —— **直接在 `main` 上工作**，推送 main（含 PR）即触发 CI。

Conventional Commits：

```text
feat: 新功能
fix: 缺陷修复
docs: 文档
test: 测试
refactor: 重构
```

示例：

```text
feat: add bookshelf explore stats and unified app theme
fix: resolve explore repository and bookshelf coroutine errors
docs: release reader interaction fixes as v0.5.0
```

## 9. 参考实现

修改相关模块前先阅读对应参考项目：

- [TerryYu12/xy-reader](https://github.com/TerryYu12/xy-reader)
  - `app/src/main/java/com/xyreader/reader/`：阅读器界面与 ViewModel（本工程阅读器的上游）
  - `app/src/main/java/com/xyreader/archive/NovelPageSource.kt`：文字分页引擎
  - 升级阅读器时对照上游 `main`，注意本工程已按 §4.8 做了裁剪
- [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
  - `api/web/`：数据源与探索页接口
  - `defaultplugin/wenku8/`：Wenku8 数据源实现
  - `data/bookshelf/`：书架模型与排序
  - `data/statistics/`：阅读时长与连续天数统计
  - （阅读器已于 0.13.0 移除，仅数据源与探索页思路仍可参考）
- [MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android)
  - Wenku8 页面组织方式和阅读交互

只借鉴思路，不复制其旧 View 架构（XML、AsyncTask、自建图片缓存）。

## 10. 禁止事项

- 不要在本地执行 Gradle 构建
- 不要使用 `git push`
- 不要提交 APK、密钥、签名文件或 `local.properties`
- 不要提交构建产物（`rust/target/`、`android/app/src/main/jniLibs/`，均已 gitignore）
- 不要绕过源站登录/验证码
- 不要把用户密码写入代码或日志
- 不要新增 `preferencesDataStore` 实例
- 不要在阅读器中硬编码主题
- 不要在没有测试的情况下修改 EPUB 解析逻辑
- 不要覆盖已发布 Release 标签

## 11. 交付前自检

- [ ] `git status` 干净
- [ ] 版本号已递增
- [ ] CHANGELOG 和 Release Notes 已更新
- [ ] main 分支 Actions 成功
- [ ] 标签构建成功
- [ ] Release 包含 `app-debug.apk`
- [ ] 单元测试全部通过
- [ ] 未引入新的密钥或本地路径
