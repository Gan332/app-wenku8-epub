# 更新记录

本项目遵循语义化版本。

## [Unreleased]

### 修复

- **上下滚动不再页间断开**：`NovelPageSource` 过去给每页都出一张**整屏高**位图，
  而页容量按 `floor((屏高 - 上边距 - 下边距) / 行高)` 计算——取整剩下的死区连同页边距
  一起堆在页底。默认边距下相邻两页之间实际空出 `177px 死区 + 64px 下边距 + 64px 上边距
  = 305px`（以 2400×57px 行高、64px 边距计），滚动时表现为明显的横向断开带，
  与代码里「页页相接、不重叠」的约定相反。
  新增 `NovelStyle.seamlessFlow`：开启时（上下滚动）上边距只落首页顶部、下边距只落
  末页底部，其余页上下内边距为 0，且位图高度取该页**实际占用**高度，相邻页严丝合缝。
  左右翻页仍是整屏页 + 四边页边距，逐像素不变。

### 内部

- 新增 `com/xyreader/archive/PageFlowGeometry.kt`：把单页上下内边距、容量行数与位图
  高度抽成纯函数（`pageTop` / `pageBottom` / `linesOnPage` / `fullPageHeight` /
  `flowPageHeight`），`NovelPageSource` 只做委托——「页间无空白像素」这条不变量从此
  可被 JVM 单测锁定，不再埋在 Bitmap/Canvas 渲染层里。
- 分页循环改为按**当前页序**取容量（无缝流下首页让出上边距，容量与页序相关）。
- `seamlessFlow` 与 `pageMode` 一并进入 `ReaderViewModel.styleKey`：两种翻页模式是两种
  版面，切换模式必须重排。阅读位置由既有的字符偏移锚点（`pageForCharOffset`）恢复。
- `drawImagePage` 接收页高参数，图片页在无缝流下与文字页同高，整列不再忽长忽短。
- 新增 `SeamlessFlowTest`（7 例）：整屏页高度不变、页边距只落首页/末页、中间页两两
  零空白、中间页高度唯一、首页/末页与中间页的高度关系、旧公式 177px 死区的对照、
  零边距与极小屏边界。

## [0.19.0-alpha03] - 2026-10-04（预发布）

本版加**书架排序**（用户可选，此前硬编码），并修掉若干阅读统计与缓存侧缺陷。
**无数据迁移、无配置变更**，从 alpha02 直接覆盖安装即可。

### 新增

- **书架排序**：书架页新增「排序」入口（轻量 Sheet），四档可选并在退出后保留：
  - **最近阅读**（默认，与 0.19.0 之前行为一致）：读过的在前，未读按加入时间；
  - **最近加入**：新加入的在前；
  - **书名**：按书名升序；
  - **字数**：字数多的在前，未知字数（本地 EPUB 未解析）排最后。
  置顶书在任何排序下都排在最前——那是用户显式标记的意图，不该被排序方式覆盖；
  排序只影响各分组**内部**，不改变「置顶 / 全部」两段结构。

### 修复

- **阅读统计的「今日」与「连续天数」不再过期**：这两个值原先在 `recordSession` 写入时
  算好并存盘，之后只在**下一次阅读**时才刷新。于是隔天打开应用、还没读任何书时，
  「今日」显示的仍是**昨天**的时长；连续中断两天后「连续天数」也仍显示中断前的旧值。
  改为在读取时按当前日期重新推导（纯函数 `withCurrentDay`），写入路径不再预存这两个派生值。

### 内部

- 新增 `data/ReadingStatsDerived.kt`：`withCurrentDay` / `streakEndingAt` 为纯函数，
  `today` 可注入。`ReadingStatsRepository.stats` 在**读取时**调用它，
  `recordSession` 不再预存 `todaySeconds` / `currentStreak`
  （它们退化为无意义的缓存值，真值来源永远是 `dailySeconds`）。
- `ReadingStatsDerivedTest`（12 例）：跨天后「今日」归零、今天没读不清零连续、
  断两天归零、陈旧连续值在读取时被纠正、跨年、闰日（2024-02-29）、
  最长连续只增不减、其它字段不被触碰。
- 排序从 `BookshelfRepository.entries` 移除，改由展示层纯函数 `ui/sortBookshelf` 应用。
  原先仓库层硬编码「置顶 → 最近阅读 → 标题」，既让用户无从选择，也造成
  「设置里的排序」与「仓库的排序」两个真相来源。仓库现在只负责存储顺序。
- `sortBookshelf` 保证**全序且确定**：同键值一律以 `title`、再 `id` 兜底。
  缺这层兜底时等值条目的顺序取决于输入顺序，DataStore 每次发射都可能不同，列表会莫名跳动。
- `BookshelfSort`（枚举 + 持久化）放在 `settings` 包，与 `EpubEngine` / `ReaderBackground`
  一致；排列逻辑放 `ui`，避免 settings 反向依赖 ui。
- `BookshelfSortTest`（11 例）：锁定置顶恒在最前（对全部排序方式）、未读回落到加入时间、
  未知字数排最后、等键确定性、入参不被改动、空书架、无丢书无重复。
- 书架两个 collector（书目、排序设置）都写 `bookshelf`，因此**各自都维持「已排序」不变量**，
  否则后到的原始列表会覆盖排序结果。

## [0.19.0-alpha02] - 2026-10-04（预发布）

本版为**预发布（prerelease）**：在 alpha01 的基础上补三个阅读侧改进，并清理掉整套
已废弃的旧阅读渲染层。**无数据迁移、无配置变更**，从 alpha01 直接覆盖安装即可。

### 变更

- **阅读器页内搜索**：底部工具栏新增搜索入口（仅文字小说，与「复制文字」同一条件）。
  输入关键词后全书扫描，结果按页码列出「第 N 页 + 上下文摘要」，命中段在摘要里用 `‖`
  分隔并高亮；点结果直接跳到该页。扫描逐页释放 `renderMutex`，不长时间占用翻页渲染锁；
  面板显式显示「已扫 x / y 页」进度。在线阅读只搜已加载章节（未抓的正文尚不存在）。
- **阅读统计热力图改为动态范围**：按最早一次非零阅读记录铺开到今天，上界 52 周；
  无记录时退化为 1 周。原先固定 26 周，长期使用的人看不到更早的记录。
- **书架分组头箭头改为旋转动画**：新增 `expand_more_24px`（MiuiX 图标集只有静态的
  ExpandLess/ExpandMore，做不出旋转），收起时旋 180°，时长经 `Motion.duration()`
  换算并尊重系统「移除动画」。

### 内部

- **移除旧阅读渲染层（净删约 1,090 行）**：`reader/ReaderScreen.kt`（`ReaderScreenCore`，
  由 0.13.0 起被 xy-reader 取代、0.14.0 起在线阅读也改走 xy-reader）、`reader/FlatBlocks.kt`、
  `reader/ReaderImage.kt`、`reader/RemoteImage.kt` 与 `ReaderActions` 接口已无任何调用点，
  整体删除；这些文件里只剩注释提到 `ReaderScreenCore`。**在线/本地阅读行为不变**——
  两条链路都走 `com/xyreader`，与本次删除无关。
- `ReaderUiState` 与 `controlsShown` / `resolveBack` / `withPanel` 三个纯函数**保留**
  （`CoreSmokeTest` 直接测试，是阅读交互回归基线），文件更名为 `reader/ReaderUiState.kt`；
  随之删除只测已删符号的 6 个失效用例（摊平映射、段落下标、对比度纠偏）。
- 注意：被删的 `readableTextOn` 曾为「自定义背景色」做对比度纠偏（黑底不留黑字）。
  xy-reader 只有 4 种固定背景且字色是静态映射，**没有**该纠偏逻辑；
  旧的自定义背景色设置属于旧渲染层，不影响现行阅读器。
- `ReaderSearch.kt`：`contextSnippet` / `searchResultPages` 为纯函数，`ReaderSearchTest`（12 例）
  锁定摘要截取、忽略大小写、空查询、负半径退化、null 页跳过等边界。
- `ReaderViewModel.searchBook` 逐页调 `PageSource.pageText`（每页各自进出 `renderMutex`），
  用 `SearchState` 发布进度；`onCleared` 与面板关闭时取消，避免后台继续翻文本。
- `heatWeekRange` 为纯函数，`ReadingHeatmapTest` 补 5 例（最早记录计数、截断到上界、
  无记录退化为 1 周、自定义上界、未来日期）。
- `ReaderDimens` 新增 `sheetItemCorner` / `searchResultMaxHeight`，阅读器界面无裸 `dp`/`sp`
  （由 `DesignTokenTest.readerUiHasNoHardcodedSizes` 锁定）。
- 文档修正：`README.md` 的本地构建依赖由「Platform 36 / Build Tools 36.1.0」改为
  「Platform 37 / Build Tools 37.0.0」，与 `compileSdk = 37` 及 `android/README.md` 对齐。


## [0.19.0-alpha01] - 2026-10-04（预发布）

本版为**预发布（prerelease）**：三处界面重构刚落地，密度与手感仍待真机确认，
建议当「尝鲜版」试用。功能面相对 v0.18.0-alpha01 是净增量，无配置变更、无数据迁移。

### 变更

- **书架分组**：参考 LNR `BookshelfHomeContent`，书架切成可折叠的「置顶 / 全部」两段，
  分组头吸顶（stickyHeader）带图标与展开箭头；卡片封面放大到 LNR 的 94×144、作者用强调色。
  点卡片即读、更多菜单等交互不变（AGENTS §4.6.1）。
- **探索页**：榜单切换从横向 Chip 行换成可滚动 `TabRow`；新增下拉刷新
  （用 MiuiX 自带的 `PullToRefresh`——此前 `ExploreExpandedScreen` 注释称 0.9.4 无等价组件是误判）；
  切换榜单时列表自动回到顶部。
- **阅读统计热力图**（参考 LNR `HeatMapCalendar`）：「最近 7 天」进度条换成 GitHub 式年度网格，
  列 = 周、行 = 周内七天，五档色阶，色阶阈值按非零阅读时长的 25%/50%/75% 分位动态计算，
  图例标注最高档阈值；点选某天显示当天时长。网格横向滚动、默认停在最近一周。

### 内部

- **全局排版放宽**：`UiDimens` 间距六档整体上调一档（4/8/12 → 6/10/14，`spaceL` 16 → 18，
  `spaceXL` 24 → 26），`cardInset` 14 → 16dp，`rowMin` 44 → 48dp（与最小触控区对齐）。
  另把 37 处绕过令牌直接写 `dp` 的间距（`PaddingValues(12/14/16/18.dp)`、`spacedBy(10.dp)`、
  `padding(top = 12/14/16/18.dp)`）统一换成 `cardInset` / `spaceS` / `spaceL` / `pagePadding`，
  消除「部分控件拥挤、部分宽松」的不一致。阅读器（`com.xyreader`）按 §4.8 保持上游原样。
- `ui/ReadingHeatmap.kt`：阈值分档、网格切分与点击命中测试抽为纯函数，
  由 `ReadingHeatmapTest`（24 例）锁定。
- `ui/BookshelfGroups.kt`：`groupBookshelf` 为纯函数，`BookshelfGroupsTest`（7 例）锁定
  「每本只出现一次、组内不重排、空组不产出」；折叠状态留在 Composable 内，不进 ViewModel。
- `scripts/push-via-api.sh`：算「本次改动文件」时补上 `--ignore-cr-at-eol`。此前本机
  `core.autocrlf=true` 且 `.gitattributes` 未给 `*.kt`/`*.xml` 指定 eol，纯行尾差异会被
  算成改动，既拖慢推送（多出上百次 blob 调用），也会把远端 LF 文件回冲成 CRLF。

## [0.18.0-alpha01] - 2026-10-06（预发布）

本版为**预发布（prerelease）**：功能面大、动效与布局仍在调整，默认关闭中继等新特性为可选。

### 新增

- **尺寸令牌** `ui/UiDimens.kt`：触控最小 48dp、行高 44dp、间距六档（2/4/8/12/16/24）、
  字号七档（25/20/16/15/14/13/12）+ 动效时长；全局替换裸 `dp`/`sp`，控件大小可一处调整。
- **详情页章节目录**：默认预览 20 章，可搜索（标题/卷名）、展开/收起（带动画）、限高滚动；
  点某章直接在线读该章（`EXTRA_START_CHAPTER`，起始页归零）。
- **详情页字段级隔离**：状态、字数、更新、章节数、来源各一张 Card，标签/简介/操作各自成卡。
- **探索页按 LNR `ExploreHomeScreen` 重排**：TopBar 搜索入口 + 分区（本地书目/榜单/标签），
  榜单为横向书卡 + 行尾「更多」进全屏展开页（网格 + 刷新）。
- **搜索成为独立二级页**（从探索页 TopBar 进入）。
- **动画基建** `ui/Motion.kt`：交错入场、按压反馈、展开动画、shimmer 骨架；
  全部尊重系统「移除动画」，长列表只对首屏项累加延迟。
- **设置页对齐 Kazumi**：分类（账号/阅读/外观/网络/数据/关于）+ 分组卡片 + 分类侧栏
  （宽屏 rail、窄屏 BottomSheet 抽屉）；导出引擎改为 Sheet 轻量编辑。
- **账号分区 + 顶栏账号入口**：登录状态、登录、退出（确认弹窗）；不保存账号密码。
- **网络分区**：中继设置 + 「测试公开端点」（匿名公开页探测，不带 Cookie）。
- **自建 Cloudflare Worker 中继**（`relay/`）：只代理匿名白名单端点、拒绝登录墙内接口、
  不转发 Cookie、5 分钟缓存、每 IP 每分钟 60 次限流；用你自己的 CF 账号 `wrangler deploy`。
- 路径契约：Worker 的 `ALLOWED`/`DENIED` 与 `Wenku8Endpoint.isRelayablePath` 一一对应，
  由 `RelayEndpointTest` 锁定，防止两端漂移。

### 内部

- 全屏覆盖页（详情/记录/榜单展开/搜索/向导）统一 `AnimatedContent` 转场。
- `StartPosition`（起始章+起始页决策）、`ChapterPreview`（目录预览/过滤）抽为纯函数并加单测。

## [0.17.0] - 2026-10-06

### 变更（信息架构：阅读优先）

- **底部导航去掉「创建」tab**，只保留 书架 / 探索 / 设置；导出不再占据一级入口。
- **书架卡片点一下直接读**：本地书进 xy-reader，远程书进在线阅读器（自动跳最近章）并记录阅读；
  详情与导出改到卡片「更多」菜单（「查看详情」「导出 EPUB」）。
- **导出降级为二级页向导**：从书架卡片菜单发起，四步为 解析目录 → 选择章节 → 打包设置 → 进度；
  解析失败可重试。目录解析成功默认全选章节。
- **书架远程书的详情复用探索详情那条公开数据通路**（`ExploreDetailRepository`），
  不再维护第二套详情 UI；探索详情的「在线阅读」主按钮与双引擎一键导出保持不变（§4.9）。
- **导出记录独立成页**，入口在 设置 → 概览与通知栏路由；书架页只保留进行中任务概览。
- 解析/导出相关的历史入口「粘贴网址创建」随「创建」tab 一并移除：导出现在从具体书籍发起。

### 内部

- `StudioTab.CREATE` 与 `CreateStep`（源站/详情/章节/导出/进度）删除，
  改为 `ExportStep`（解析中/章节/打包/进度）+ `StudioUiState.exportStep`（null = 不在向导里）。
- 新增纯函数 `ExportWizard`（begin / resolved / failed / close / openShelfDetail）承载状态迁移，
  单测 `ExportWizardTest` 锁定「发起必清残留」「解析失败留在解析态」「详情不碰导出状态」三条不变量。
- `BookDetailScreen.kt` 与 `SourceScreen` 删除；`parseSource` 更名 `resolveForExport`；
  `openShelfRemote` 改为 `openShelfBookDetail`（走公开详情通路）。

## [0.16.0] - 2026-10-05

### 新增

- **设置 → 概览新增「第三方中继（仅公开页）」开关**：直连被 Cloudflare 403 拦时，
  可把**免登录公开页**（书目索引、公开榜单、封面图）改走用户自填的中继端点。
  端点必须用户自己填（本项目不内置任何猜测地址，默认关闭）。
- 公开端点（`articleinfo.php`、目录页、年度/月度榜、`authorarticle.php`）改由
  `Wenku8Endpoint.publicBase()` 拼装；会话端点（`search.php` / `toplist.php` /
  `tags.php` / 登录）与「来源地址」`book()` 始终直连。
- 新增 `Wenku8Url.carriesSession`：`Wenku8SessionStore.cookieJar()` 的存取两侧都用它
  按 host 把关，**中继永远收不到** `jieqiUserInfo` / `PHPSESSID`。
- 解析出口统一 `Wenku8Endpoint.restoreToDirect`：书架、EPUB 内链、粘贴的来源网址
  都还原成 wenku8 原域；封面/正文图片在下载前才用 `toRelayUrl` 改写，缓存键不变。

### 不变

- 端点集合、URL 白名单、内网拦截、限流与 HTTP 429 退避一律不变；中继只换传输路径，
  不新增任何接口，也不涉及任何登录规避。
- 关闭开关时行为与 0.15.0 逐字节一致（默认关闭且无内置端点）。

## [0.15.0] - 2026-10-05

### 变更

- **站内搜索支持翻页**：`search.php` 请求带上 `page=` 参数（从 1 开始），搜索页底部
  新增「加载更多」，跨页结果按书籍 id 去重后追加，末页显示「已加载第N 页」。
- 页码**以请求参数为准**，`em#pagestats` 只用来推断总页数（认 `2/5`、`共5页` 两种文案），
  拿不到总页数时退回「下一页」锚点判断，两条路都拿不到才算末页——不再把分页文案里的
  第一个数字当成当前页，避免跳页。
- 搜索请求加单调序号：用户中途改关键词、切字段或连点「加载更多」时，旧响应直接丢弃，
  不会出现旧结果盖掉新结果。
- 搜索改走交互档节流（`fetchTextInteractive`），与探索详情、官方标签同一口径；
  白名单、内网拦截、429 退避、重定向复校验完全不变（AGENTS §4.6）。
- 抽出 `SessionGate`（`hasSession` / `clear`）会话门接口，`Wenku8SessionStore` 实现它；
  `Wenku8SearchProvider` / `Wenku8DataSource` / `Wenku8BookSource` 依赖接口而非具体实现，
  单测可注入假实现，不必在 JVM 里碰 Context / Keystore。会话链路与合规边界不变
  （仍只走用户自己的会话，见 AGENTS §4.2 / §4.5.3）。
- 搜索页补进度指示：首屏搜索中显示加载环，「加载更多」按钮在加载中禁用并显示进度。

## [0.14.0] - 2026-10-03

### 变更

- 完成在线阅读迁移 step 3/3：`OnlineReaderActivity` 从旧 `ReaderScreenCore` 切到
  xy-reader 的 `ReaderScreen`，在线目录先预取，再由 `OnlinePageSourceOpener` 注入
  可增长页面源。
- 新增在线专用 `OnlineReaderRepository`：页进度与页轴起始章、当前章一起持久化，
  避免恢复页码错位；书架 `recordRead` 使用传入的真实书架条目 id。
- 在线正文标题、段落和插图统一进入 `NovelPageSource` 分页；插图下载复用
  `CoverRepository` 与共享 `Wenku8HttpClient` 限流客户端。
- 为 `PageSource` 增加 `styleDependent`，在线组合源与文字小说支持字号/背景变化后的
  重排；设置页旧阅读配置仅在首次打开 xy 配置时迁移。

## [0.13.0] - 2026-10-01

### 变更

- **阅读器换成 [XY reader](https://github.com/TerryYu12/xy-reader)（MIT）**，
  取代 0.12.0 引入的 LightNovelReader。引入代码放在 `com/xyreader/`，**保留上游包名**
  便于日后比对。

  这是一套**页面位图**阅读器：`PageSource` 只暴露 `pageCount` + `renderPage(i): ImageBitmap`。
  文字小说走 `NovelPageSource`——用 `StaticLayout` 把段落预分页，再把每页画到**透明底
  Bitmap** 上，pager 背景直接透出；配套 `ReaderViewModel`（位图 LRU 缓存、前后各 2 页
  预载、串行渲染 worker、防抖存进度）与 `ReaderScreen`（左右翻页/上下滚动、点击翻页、
  双击缩放、目录抽屉、书签、复制文字、亮度、屏幕方向）。

  引入范围：`core`（契约与配置）+ `archive`（排版引擎与各格式页面源）+
  `reader`（阅读界面）+ `ui`（阅读配置页），共 27 个 Kotlin 文件、约 6900 行。

### 按本工程约束做的裁剪

- **Room 全去**：`BookEntity` / `BookmarkEntity` 降级为纯数据类，书签与页进度手写 JSON
  落在既有 `wenku8_settings` DataStore（键前缀 `xy_reader_`），遵守「DataStore 实例唯一」。
- **`LibraryRepository` → `ReaderRepository`**（收窄到 8 个成员），`AppGraph` →
  可安装的 `ReaderGraph`，避免 `com.xyreader` 反向依赖宿主包名。
- **EPUB 解析没换**：`ReaderViewModel` 增加 `sourceOpener` 注入点，宿主注入
  `XyEpubPageSourceOpener`，仍走本工程 Rust 原生结构解析 + Jsoup + 回退链。
- **远程链路整体未引入**（WebDAV / Google Drive / HttpRangeChannel）：本工程数据源只有 wenku8。
- **material3 / material-icons 全量 MiuiX 化**（约 330 处引用），满足 `mainSourcesUseNoMaterialComponents` 守卫。
- 新增内置字体霞鹜文楷 Lite 与朱雀仿宋（OFL 1.1）；MiSans 复用既有 `misansvf`，不重复打包。

### 移除

- LightNovelReader 的 **126 个 Kotlin 文件**（`indi.dmzz_yyhyy.lightnovelreader` /
  `io.nightfish.lightnovelreader.api`）及其专属依赖 kotlin-result、dom4j、navigation3-runtime。
- 旧自研阅读器壳 `ReaderActivity` 与已无调用方的 `ReaderViewModel`（EPUB 侧）。
  **注意**：`reader/ReaderScreen.kt` 的 `ReaderScreenCore` / `ReaderActions` 被
  **在线阅读复用，保留**。

### 需要注意

- **阅读器设置项与位置变了**（改为 XY reader 布局），原有能力（字号/行距/边距/字距/
  首行缩进/章首另起一页/背景/亮度/翻页方式/常亮/屏幕方向/图片缩放）都在。
- **进度口径变了**：页进度单独存（分页依赖字号与边距，无法与「章 + 段」稳定换算），
  同时刷新书架「上次阅读」。旧版的章/段进度不再用于本地 EPUB 续读。
- **APK 体积增大约 23MB**（两款内置 CJK 字体）。

### 验证重点

1. 打开 EPUB：正文按页排版，左右翻页/上下滚动都能翻
2. 目录抽屉：当前章高亮、点击跳转；书签可增删
3. 阅读设置面板：字号/行距/边距/背景调整后**重新分页且保留当前位置**
4. 续读：读到中间退出 → 重开回到原页；书架「上次阅读」更新
5. 亮度/屏幕方向/常亮/手势锁生效
6. 回归：wenku8 在线阅读、导出（缓存/富文本/卷级目录）不受影响

## [0.12.0] - 2026-09-27

### 变更

- **阅读器换成 LightNovelReader 的内置阅读器**（来自本地 LNR 工作副本，
  基线 `1.2.0-197-g5e2f7cd0`，**Apache 2.0**）。核心收益是它的
  **按页排版翻页引擎**（`FlipPageContentComponent`，968 行）与滚动组件、
  章节选择面板、图片查看器、阅读器设置面板。

  引入方式与边界：

  - 拷贝 `:api` 阅读器子集 + reader UI 共 **126 个 Kotlin 文件**
    （原包名 `io.nightfish.lightnovelreader.api.*` / `indi.dmzz_yyhyy.lightnovelreader.*` 保留，
    Apache 文件头保留，根目录加 `THIRD_PARTY_NOTICES.md` 声明）。
  - **数据层全部保留本工程实现**：Rust EPUB 结构解析 + Jsoup 块解析、
    DataStore 断点与设置、`CoverRepository` 限流图片管线、`ReadingStatsRepository` 统计。
  - **不引入** LNR 的 Room / Coil / panpf / Hilt / WorkManager / 插件系统 / navigation3 UI。

  适配要点：

  - **material3 全清**（本项目守卫测试 `mainSourcesUseNoMaterialComponents` 持续绿）：
    `ModalBottomSheet`→`OverlayBottomSheet`、`SecondaryTabRow`→MiuiX `TabRow`、
    M3 的 `TopAppBar.title` 是 composable 而 MiuiX 是 String（标题动画简化）、
    复合 `TextButton`→`Column+clickable`（按压遮罩由 `MiuixTheme` 的 `LocalIndication` 提供）。
  - **Snackbar** 走 MiuiX 同名体系；**图片三态**（加载/失败占位/就绪）经
    `ReaderImageLoader` 接 `CoverRepository`，失败点「重试」会真正重载。
  - **主题桥** `LnrAppTheme`：把 MiuiX `colorScheme` 投影成 LNR 期望的
    `LocalAppTheme`（含 `MiuixTheme.colorScheme` 双层写法的自引用兼容）。
  - **数据桥** `LnrBridge.kt`：8 类 `UserData` 绑到**同一个** DataStore
    （`lnr_userdata_*` / `lnr_reading_*` 前缀，不新建 DataStore 实例）、
    `ReaderBlock`→LNR 组件 JSON（id 取组件自身 `Identifier`，与渲染注册表严格一致）、
    EPUB 内插图解包落 `cacheDir/lnr-images`（sha1 寻址、原子写）。
  - **断点双向**：进入时把既有 `ReadingProgress` 换算成 LNR 的 `lastReadChapterId`；
    退出时把 LNR 的章内比例换算回段落序号写回 `ReadingProgress`，并 `recordRead` 更新书架时间。
  - **统计口径不变**：`accumulateBookReadTime` → `ReadingStatsRepository.recordSession`
    （单次 ≤30 分钟）；LNR 的事件流/完读率方法显式 no-op，避免统计双写。
  - 组件 id 缺失时走 `ErrorContentComponentData`（可诊断），不静默吞。

- **GPLv3 文件已剔除**：`RollingNumber`（EhViewer 来源，GPLv3）不进入本 MIT 项目，
  4 处调用点改为普通文本。删除集：`Navigation.kt`、ImageViewer、`Dialog.kt`（11 个零引用导出）、
  `AppColorPickerTarget`；图片全屏查看改接本工程 `CoverViewerDialog`。
- **裁剪主设置页专属功能**：更新渠道（`data.update`）、书架/本地排序（`ui.bookmanager`）
  选项组零引用且依赖 Hilt/shimmer，随之裁掉。

### 升级说明

- 无数据迁移变更（沿用 `com.example.hyperreader`）。
- 旧 `ReaderActivity`（自研渲染壳）**暂留**作对照路径：首帧、R8、手势这类问题
  只能在真机暴露；真机冒烟通过后在下一个版本删除。
- 阅读器设置项位置与旧版不同（改为 LNR 布局），字号/字重/行高/背景等仍可调。

## [0.11.0] - 2026-09-27

### 新增

- **探索页书籍详情改为全屏独立页面，与「创建导出」流程彻底分离**。
  以前在探索页点一本书会走 `openSearchBook` → `ExportJobManager.parseSource()` →
  `CreateStep.DETAIL`：那是「创建导出」的流水线，会做导出前置校验
  （`Wenku8Url.validateBook` / `validateChapters`），并按批量档 **1 秒/请求** 节流，
  只想看一眼详情也要等很久，还会污染创建流程的状态。

  现在拆成两条互不相干的路径：

  | | 探索详情（新） | 创建导出（不变） |
  | --- | --- | --- |
  | 入口 | 探索页点书 | 粘贴 URL / 书架在线阅读 → 导出 |
  | 数据 | `articleinfo.php` + 目录页 | 同上，但走 `parseSource` |
  | 节奏 | 交互档（允许短时突刺） | 批量档（1 秒/请求） |
  | 产物 | 页面展示用 `ExploreBookDetail` | `Book` + `BookIndex` 供选章导出 |

  - 新增 `ExploreDetailRepository`：只做两次只读请求，不走导出解析管线；
    带 `LinkedHashMap` 内存缓存（32 条），同一次会话里重复打开同一本书 **0 请求**。
  - 新增 `ExploreDetailScreen`（全屏新页面）：封面 / 标题作者 / 元数据卡 / 标签 / 简介 /
    操作（在线阅读、加入书架、查看同作者作品）。进入时用列表页已有的 `ExploreBookSeed`
    立刻渲染标题作者，接口回来再补齐，避免闪一屏空白。
  - 详情页**同时抓目录页显示章节数**；目录页单独失败不致命 ——
    降级为「只有书籍信息 + 章节数未知」，就地提示并可重试，不整页报错。

### 变更

- **请求节流区分两档**（`HttpRateLimiter.Mode`）：
  - `BATCH`（默认，1 秒/请求）：导出、书目缓存等批量抓取，节奏不变；
  - `INTERACTIVE`（120ms/请求）：只给探索、详情这类**用户按需请求**用，
    且是「允许突刺」而非「提高速率」—— 6 秒滑动窗口内最多 6 次，超限自动退回批量档。

  换句话说，提速只作用在点击后的第一跳，长期均值仍由批量档兜住，
  不会把请求频率推到源站警戒线以上。URL 白名单、内网拦截、429 退避等边界完全一致。
- `Wenku8HttpClient` 新增 `fetchTextInteractive()`；批量路径 `fetchText()` 不变。
- `Wenku8Urls` 新增 `index(bookId, category)` 与 `DEFAULT_NOVEL_CATEGORY`。
- 详情页隐藏底部导航栏，避免误触 tab 丢掉当前详情。

### 测试

- 新增 `CoreSmokeTest` 用例：交互档快于批量档且不取消节流、突刺配额有上限且有下限、
  `CatalogEntry → Book` 映射、接口缺字段时用 seed 兜底、目录页失败可降级、
  章节数与目录一致、`Wenku8Urls.index` 的分类号与脏值兜底。

## [0.10.1] - 2026-09-27

### 修复

- **阅读器菜单栏「目录」「设置」按钮点击无响应（0.9.1 起遗留，三轮静态分析未定位）**。
  根因是 Compose 指针事件的同一层级分发语义与 `detectTapGestures` 的消费忽略：

  - `Scaffold` 把 popup 宿主放在**最上层**（`place` 顺序在 topBar/bottomBar 之后），
    并在测量时把 `constraints.copy(minWidth = 0, minHeight = 0)` 交给它 ——
    **保留非零最小约束**，因此 `MiuixPopupHost` 一旦被组合，其 `fillMaxSize` Box
    就被撑到全屏尺寸，成为覆盖顶/底栏的命中节点。
  - 目录/设置面板的整屏 scrim 与面板的顶/底栏是**同级同尺寸**（均为全屏）的命中节点；
    Compose 在同一层级会把事件分发给**所有**命中节点，`consume()` 只标记状态、
    **不阻止分发**，节点必须自己检查 `isConsumed` 才「让位」。
  - `detectTapGestures` 的 `onTap` 是**无条件**触发的（`waitForUpOrCancellation`
    不因变化已被消费而返回 null）。于是点「目录」时，正文的 `onTap` 也照常执行，
    一次点击既打开面板、又 `toggleControls()` 把菜单栏收起来 —— 观感就是「点了没反应」。

  修复：`readerTapToToggle` 改用 `awaitEachGesture` + `awaitFirstDown`，
  **按下瞬间**记录 `isConsumed`，被同层兄弟节点（按钮、面板）认领的按压不再切换菜单栏；
  无人认领时行为不变（点正文照常呼出/收起）。

- **关闭一个面板后立即打开另一个面板无效**。MiuiX `DialogEntry` 的 `onDispose`
  **不会**移除仍在显示态的条目，关闭动画期间旧面板窗口留在命中测试图里，
  会压住后开的面板。新增纯函数 `ReaderUiState.withPanel()`，让目录与设置**互斥**：
  打开其一即关闭另一个（EPUB 与在线阅读两侧同步）。

### 测试

- 新增 `CoreSmokeTest.panelsAreMutuallyExclusiveSoTheirOverlayWindowsCannotStack`
  与 `resolveBackStillClosesThePanelAfterMutualExclusionSwitchedThem`。
- 0.9.4 引入的 `ReaderTrace` 交互埋点继续保留：本问题属「不崩溃的交互缺陷」，
  真机验证时用 `adb logcat -s ReaderTrace` 可直接确认事件断在哪一层。

## [0.10.0] - 2026-09-27

### MiuiX 动效与组件全面化

#### 页面与列表动效

- **tab/流程转场**：主界面切页（书架/探索/创建/设置 + 创建流程步骤）接入
  `AnimatedContent`，横向滑入 + 淡入，缓动 `DecelerateEasing`（MiuiX anim 包）。
- **列表项动画**：书架、导出记录、搜索结果、探索列表、目录面板全部接入
  `Modifier.animateItem()`，删除/重排平滑过渡。
- **阅读器设置数值联动**：字号/字重/行高/段距/边距的数值用 `AnimatedContent`
  淡变（行是 fill 宽，`animateContentSize` 不会触发——已换更有效的实现）。

#### 阅读器动效

- 控件栏收展换 **MiuiX folme 弹簧**（`folmeSpring(damping=1.0, response=0.35)`）
- 插图加载 → 就绪**纯淡入**（不缩放尺寸，避免排版跳动）
- 翻页模式切换 `Crossfade` 过渡（原为整块硬切）
- 上下滚动模式的目录跳转改**动画滚动**（断点恢复仍瞬时精准落位；>50 项大跨度仍瞬时）

#### 组件补齐

- **SearchBar**：搜索页输入框接入 MiuiX `SearchBar` 容器，历史词作为展开内容
 （初始展开 = 与旧布局等价，无回归）
- **Snackbar**：根消息从内联错误卡改为 `SnackbarHost`（可滑走、自动消失、
  显示后 `clearMessage()`）
- **书架操作菜单**：点卡片主体**直接打开**（本地进阅读器/远程进详情），
  行尾「更多」按钮弹 **Popup + BasicComponent** 操作菜单（在线阅读/同作者/
  置顶/移出），删除旧全屏操作弹窗 `BookActionsDialog`
- 按压反馈与回弹**已全局生效**（`MiuixTheme` 注入 `MiuixIndication` 与
  `MiuixOverscrollFactory`，0.9.3 起即存在——本轮核验确认，无需重复挂载）

#### 主动降级（如实记录）

- 下拉刷新未引入：书架/探索均为 DataStore 实时订阅数据，**没有真实的
  刷新动作可执行**，假转圈不如不做
- 章节选择页的过滤框保持 `TextField`：语义是列表内过滤而非搜索栏，
  套 SearchBar 需要凭空造展开内容

### Rust EPUB 解析核心（`rust/epub-core`）

- **新增 Rust 原生库** `libepub_core.so`（cargo-ndk 交叉编译 arm64/armv7/x86_64）：
  手写结构解析（container/OPF/nav/NCX + 按 spine 读出章节 HTML），
  参考 plato（1705★）语义自写、零上游依赖（原候选 epub crate 仓库已 404）。
- **职责边界**：Rust 只做结构解析；HTML→内容块仍走 Kotlin Jsoup（现有测试
  原样有效），图片字节仍走 Kotlin ZipFile。
- **JNI 契约**：`EpubNative.parse` 返回 JSON，**任何失败返回 null → 自动回退
  legacy zip+Jsoup 路径** —— native 是加速路径不是单点依赖；so 缺失
 （本地开发）同样自动回退。
- **安全限额与 legacy 对齐**：单条目 ≤30MB、解压累计 ≤200MB（AGENTS 约束）。
- **R8**：`EpubNative` 显式 keep（native 符号按类名+方法名查找）。
- **CI**：新增 Rust 工具链（dtolnay/rust-toolchain）、cargo 缓存、
  `cargo test`、cargo-ndk 构建三 ABI so → `src/main/jniLibs/`（不入库，
  `.gitignore`）。
- **测试**：Rust 侧 5 个（结构/toc/nav/NCX 回退/损坏包拒绝/JSON 契约/路径语义）；
  Kotlin 侧 2 个（native JSON → ReaderBook 映射含图片路径归一、无 so 时 legacy 回退）。

### 修复

- **图片路径双前缀（0.9.x 遗留）**：阅读器解析 `img src` 时在相对路径归一后
  又拼了一次 packageDir，产出 `EPUB/EPUB/images/…` —— zip 查不到条目，
  **自家导出的 EPUB 在阅读器里图片全不显示**（外部阅读器不受影响，
  因为其写入的相对路径本身正确）。native 与 legacy 两路共用同一函数，一处修复覆盖。
  正是新增的 native JSON 映射测试断言出的失败暴露了它。

## [0.9.5] - 2026-09-26

### 导出 EPUB 模块重写

#### 排版还原

- 正文保留**内联强调**（粗体/斜体/下划线/上下标）：`parseChapter` 新增
  `preserveInlineFormat`（**仅导出开启**，在线阅读保持默认纯文本、零回归）。
  - 强调标签白名单由**代码生成**（b/strong/i/em/u/s/sup/sub），结构上无属性注入面；
    `style` 里的 `font-weight/font-style/text-decoration` 规范化为对应标签；
    其余元素（a/span 等）展开为纯文本，`javascript:`/`onclick`/`<script>` 必然被剥除。
  - 新增 `ContentBlock.Rich` 承载已 sanitize 的段落，EPUB 原样输出；
    CSS 补充 b/i/u/s/sup/sub 样式。
- **卷级目录**：多卷时 nav.xhtml 按卷嵌套分组（卷名为 `<span>` 不产生链接，
  回读端取 `a[href]` 的链接数恒为「书籍信息 + 章数」，层级不影响应用内目录）。
  插图位置（`figure`）与章间跳转沿用既有实现。

#### 速度

- **导出缓存 `ExportCache`**（URL→sha1 内容寻址，`cacheDir/wenku8-export`）：
  正文页与插图命中即 0 请求 —— **重复导出同一本书接近秒级完成**；
  临时文件 + rename 落盘，读取侧不可能看到半截文件；成功后按 512MB 上限淘汰。
- **跨章 URL 去重**：同 URL 插图任务内只下载一次。
- **进度落盘/通知节流 250ms**（内存 state 每次即时更新）：旧实现每张插图
  各做一次全量 JSON 序列化 + 通知 IPC，大图册开销可观。

#### 可视化

- `JobProgress` 新增 `imageTotal`（插图总数）、`etaSeconds`（预计剩余）、
  `cacheHits`（缓存命中数），均带默认值、旧任务数据向后兼容。
- 进度卡片与导出详情页新增**阶段徽标**（抓取章节/下载插图/下载封面/打包 EPUB）、
  `剩余约 x 分钟` ETA（按 1s/请求限流节奏估算）、`插图 n/总数`、
  `缓存命中 N 项（0 请求）`；通知同步拼接 ETA。

#### 测试

- 新增 `ExportPipelineTest` 8 个：富文本保留与 XSS 剥离、默认参数纯文本回归、
  在线端 Rich 降级、缓存读写/去重/trim、卷级 nav 结构与链接数、ETA、阶段文案。

## [0.9.4] - 2026-09-26（诊断版 prerelease）

- 阅读器交互埋点（`ReaderTrace` 日志），用于定位「菜单栏按钮点击无响应」——
  非功能修复版，定位后埋点将移除。

## [0.9.3] - 2026-09-26

### 变更

- **阅读器全面改用 MiuiX 组件**（应用户要求重写）：
  - 设置面板改为 `SmallTitle` 分组 + `BasicComponent` 行容器，
    滑块 `Slider`、开关 `Switch`、翻页模式 `TabRow`，与全局设置页同一套视觉语言；
    「常亮」「沉浸模式」从文字按钮变标准开关，「翻页模式」从来回点的按钮变分段选择。
  - 目录面板条目改为 `BasicComponent`（当前章带「阅读中」标记）。
  - 顶栏/底栏图标：`Back` / `ChevronBackward` / `ChevronForward` / `ListView` / `Tune` / `Import`。
- **material3 与 material-icons 全量移除**（阅读器、全局设置页、首页导航、封面查看器
  四处清零），`material`、`material3`、`material-icons-extended` 三个依赖从构建中断开 ——
  v0.9.0 那类 material3 版本错配崩溃（NoSuchMethodError）从依赖层面根除。
- 新增静态守卫测试 `mainSourcesUseNoMaterialComponents`：源码里再出现 material 引用直接测试失败。

## [0.9.2] - 2026-09-26

### 修复

- **点正文仍呼不出菜单栏（0.9.1 遗留）**。返回键验证状态与渲染都正常，只有手势没触发：
  内容区点击到不了父层 Box。点按检测改挂到 LazyColumn 节点与正文 item
  （v0.8.x 实证唯一生效的层级），父层检测保留兜底边距/加载/错误区域；
  多层由消费语义保证一次点击只切换一次。
- **阅读断点从未真正生效**。初始 `snapshotFlow` 在定位前先发射，把恢复的章节跳回
  第 0 章、段落覆盖成 0（0.9.0 摊平改造引入的竞态）；追踪闭包还捕获了旧章节号，
  反复 `selectChapter` 重置段落。现在先一次性定位到「章+段」，定位完成前不写进度；
  翻页模式只给当前章携带恢复段落，防止串页。退出阅读时兜底保存最后一次位置。
- **图片无法显示（应用内）**：`src` 路径不再做百分号解码、大图 OOM 静默失败 ——
  现在按原样+解码两次查 zip 条目、按最长边 2048px 降采样，并引入图片三态
  （加载中转圈 / 失败占位框 / 成功），失败不再隐形。
- **导出缺图静默丢弃**：插图下载失败后 EPUB 直接没有该图但任务显示成功；
  现在按章生成「《章名》有 N 张插图未能下载」警告（复用「查看 N 条警告」入口）。
- **导出 EPUB 深色模式黑底黑字**：CSS 里写死的 `body{color:#272522}` 已删除，
  文字颜色交给阅读器控制。

### 改进

- **导出排版**：段落间距（`.55em`）+ 首行缩进 + 孤行控制；插图 `height:auto`
  防拉伸、`max-width:100%` 防溢出；扉页封面 70% 居中、作者行取消首行缩进、
  「内容简介」标题居中；CJK 字体回退栈。
- **书架显示阅读痕迹**：卡片新增「上次阅读：3 分钟前 · 读到 第 5 章 · 第 12 段」
  （相对时间为纯函数，按日历日计算）。EPUB 阅读现在也会更新 `lastReadAt`
  （此前只有在线阅读在记录）。

## [0.9.1] - 2026-09-26

### 修复

- **阅读器点正文呼不出菜单栏**。顶栏/底栏的可见性判据误用 `isImmersive`，
  而点按只翻转 `controlsVisible`（一个只写不读的状态），导致默认沉浸模式下
  菜单栏永远无法出现、目录/设置按钮不可用。现在判据改为
  `controlsVisible || 面板打开`，点正文即呼出、再点收起。
- **菜单栏按钮被状态栏压住**。edge-to-edge 下系统栏可见时，MiuiX 顶栏
  没有状态栏 padding、底栏没有导航栏 padding，按钮落在系统栏触摸区点不到。
  现在顶/底栏按系统栏 inset 自适应让位（系统栏隐藏时 inset 为 0，沉浸布局不变）。
- **在线阅读的点按逻辑与 EPUB 不一致**：仍会翻转 `isImmersive` 并连带切换
  系统栏。已与 `ReaderViewModel` 对齐，只切换控件显隐。
- 返回键决策抽成纯函数 `resolveBack()`：面板优先关闭 → 菜单栏可见则退出 →
  隐藏则先呼出（与 0.8.x 行为一致，不再修改沉浸设置），并补充单元测试。

## [0.9.0] - 2026-09-26

### 破坏性变更

- **包名改为 `com.example.hyperreader`**（原 `com.wenku8.epubstudio`），**不做数据迁移**。
  旧包无法覆盖安装，升级后书架、阅读进度、书目缓存、登录态都需要重新配置。

### 修复

- **阅读器「设置」和「目录」点开必崩**（`NoSuchMethodError: ModalBottomSheet`）。
  根因是 material3 编译期与运行期版本不一致；改为使用 MiuiX 自带的
  `OverlayBottomSheet`，彻底不再让 material3 参与阅读器弹层。
- **沉浸模式进得去出不来**。正文列表上挂了一个空的 `detectTapGestures`，
  吞掉全部点击；同时 `toggleControls()` 错误地翻转了持久化的 `isImmersive`。
- **探索页和设置页无法滚动**。外层 `Column` 只写 `fillMaxWidth`、内层
  `LazyColumn` 缺 `weight(1f)`，内容被裁掉。移除了 0.8.1 引入的、与列表手势冲突的
  字号 `NumberPicker`。
- **黑底黑字**。自定义深色背景配默认深色文字色会看不清；现在按 WCAG 相对亮度
  自动纠正，对比度不足时强制改为白色。

### 改进

- 上下滚动模式改为**跨章节连续滚动**（全书摊平成一个列表），章节交界不再跳变，
  大文件滚动更顺，章节开头自动插入章名。
- 插图解码移出主线程并加 8MB 内存缓存，避免大文件卡顿。
- 段落下标改为按可见项推导，修复阅读进度漂移。
- 探索页点书**直接进入详情页**，不再先落到 URL 输入页；加载目录时显示进度指示，
  失败可就地重试。
- 阅读器设置面板改为色块预览背景，一眼看出选中底色下的文字对比度。
- 阅读器加载/错误/正文之间加入淡入淡出过渡。

### 构建

- 开启 R8 代码压缩与资源裁剪：**release 包 16.0 MB，debug 包 41.4 MB（减少约 60%）**。
- release 签名改由 GitHub Secrets 提供，密钥与口令不再需要进入任何配置。

## [0.8.1] - 2026-09-26

扩充 MiuiX 0.9.4 组件的使用：

- `Badge`：书架卡片置顶标记，从纯文字改为徽标
- `HorizontalDivider`：书架与设置列表分组分隔
- `VerticalScrollBar` + `rememberScrollBarAdapter`：书架长列表滚动条
- `BreadcrumbBar`：创建流程步骤指示（源站 → 详情 → 章节 → 导出 → 进度）
- `NumberPicker`：阅读器字号精确输入，与滑块粗调互补

## [0.8.0] - 2026-09-26

### 依赖升级

- MiuiX `0.8.8` → `0.9.4`
  - Maven 坐标变更：旧的 `top.yukonga.miuix.kmp:miuix` 止于 0.8.8，0.9.x 发布在新坐标
    `top.yukonga.miuix.kmp:miuix-ui` + `miuix-core` + `miuix-icons`
  - `kmp.extra` 包已整体删除，`SuperDialog` → `top.yukonga.miuix.kmp.overlay.OverlayDialog`
  - `Checkbox` 改用 Material 风格的 `ToggleableState` + `onClick`
  - `TextStyles`（14 个字段）、`ThemeController`、`ColorSchemeMode`、`MiuixTheme` 签名均未变化
- AGP `9.1.0` → `9.4.1`，Kotlin `2.3.20` → `2.4.20`，Compose MP `1.10.3` → `1.12.0`
- Gradle `9.4.1` → `9.6.0`（AGP 9.4.1 的最低要求）
- compileSdk `36` → `37`；targetSdk 保持 `36`
- CI SDK 安装改为 `platforms;android-37.0` + `build-tools;37.0.0`
  （SDK 37 起平台包强制带小版本号，裸的 `platforms;android-37` 不存在）

### 通知与导出进度

- 点击进行中任务的通知直达该任务进度界面
- 任务完成/失败/取消后通知不再直接消失，点击进入书架导出记录
- 通知路由决策抽为纯函数 `NotificationRoute.resolve`，可脱机测试
- 书架首页新增进行中任务区：书名、进度条、当前章节、章节数、插图数、取消按钮
- 无进行中任务时该区域完全隐藏，不占空间

### 配置导入导出

- 设置新增「配置导入导出」二级页
- 导出主题与阅读器设置为 JSON，含 `schemaVersion` 版本校验
- 导入时逐字段写入并复用既有范围钳制，单字段非法只跳过该字段
- **凭据隔离是结构性的**：导出模型的字段全部是标量类型，物理上装不进
  Cookie、密码、Token 或书架数据；单元测试用反射强制这条不变量
- 刻意不导出 `fontUri`（设备本地 `content://` 授权地址，跨设备无效且泄露本机路径）
- 枚举以字符串承载，未知取值变成「跳过并计数」而非整份文件解析失败

### 在线阅读

- Wenku8 书籍可直接在应用内阅读，无需先导出 EPUB
- 复用现有阅读器渲染层（`ReaderScreenCore`），字体、背景、沉浸、目录、
  阅读进度与时长统计与 EPUB 完全一致
- 章节正文缓存到 `filesDir/online-cache`，上限最近 50 章 LRU
- 命中缓存不发网络请求，已读章节断网可读
- 章节请求严格串行，不做并发预取
- 遇到登录页提示「该内容需要登录」并停止，**不做任何绕过**
- 章节 404/410 落墓碑并跳转相邻章节；429 不误判为章节删除

## [0.7.0] - 2026-09-25

- 书籍详情页新增封面大图，点击进入全屏预览
- 全屏预览支持双指缩放、拖动与双击还原
- 详情页字数、更新时间、状态、章节数改为独立元数据控件，不再拼接成字符串
- 详情页全部元素可通过上下滑动访问，长简介不再被裁切
- 详情页标签可点击跳转到本地搜索
- 书架卡片显示封面缩略图与书名
- 书籍操作改为二级界面：继续阅读、详情、置顶、展开同作者、移除
- 移除书架需二次确认，且不删除本地 EPUB 文件
- 设置改为二级界面：主题与外观、阅读器设置、阅读统计、书目缓存、关于
- 阅读器设置（背景、字体、字号、字重、行距、段距、边距、翻页）移入设置页
- 阅读统计从底部导航移入设置二级页
- 底部导航由 5 项减为 4 项
- 书目更新改为被动触发：仅在设置页手动触发，移除自动抓取路径
- 新增封面内存与磁盘缓存，复用限流客户端且不引入第三方图片库

## [0.6.0] - 2026-09-25

- 搜索与书籍浏览改为免登录可用
- 新增本地书目索引：抓取公开页面后在设备内检索
- 数据源限定为 wenku8 对匿名访客公开的页面：
  - 书籍详情 `articleinfo.php`
  - 同作者作品 `authorarticle.php`
  - 年度精选榜 `/zt/sugoi/{year}.php`
  - 月度新书榜 `/zt/booklist/{yyyyMM}.php`
- 搜索支持书名、作者、标签，包含全角半角与大小写归一
- 搜索完全在本地内存完成，断网可用
- 探索页改用年度精选与月度新书榜
- 设置页新增「更新书目缓存」，显示本数、上次更新时间与抓取进度
- 抓取使用独立无 Cookie 客户端，不携带登录态
- 登录门禁接口（search/articlelist/toplist/tags）保持原样，未做规避

## [0.5.0] - 2026-09-25

- 首页改为独立书架，支持在线书籍和本地 EPUB
- 导出历史从首页移入书架的导出记录区域
- 新增 Wenku8 数据源抽象和探索页面
- 探索页面支持今日更新、热门、新书、动画化、分类和标签
- 搜索并入探索页面，继续支持书名/作者搜索
- 书籍详情支持加入书架，书架支持置顶、移除和继续阅读
- 新增阅读统计：总时长、今日时长、连续天数、每日时长和按书籍统计
- 阅读器会话接入阅读时长统计
- 首页和阅读器共用统一 MiuiX 主题
- 阅读器目录和设置 BottomSheet 跟随应用主题
- DataStore 统一管理书架和阅读统计数据

## [0.4.0] - 2026-09-25

- 修复阅读器目录和设置按钮的触控区域与可用性
- 阅读器进入沉浸模式，隐藏状态栏、导航栏、顶部栏和底部栏
- 点击正文中央显示/隐藏阅读控制栏
- 目录和设置改为可滚动的 Material 3 BottomSheet
- 增加底部章节导航、当前章节进度和禁用边界状态
- 新增系统文件选择器导入 EPUB，可在设置页或阅读器设置中导入
- 统一阅读器按钮触摸区域、面板间距和正文边距
- 退出阅读器时恢复系统栏，保留屏幕常亮设置
- 研究并参考 MewX 与 LightNovelReader 的沉浸阅读、目录和设置交互

## [0.3.0] - 2026-09-25

- 新增 MiuiX 应用主题模式、动态色和强调色设置
- 新增 wenku8 登录后按书名/作者搜索
- 书籍详情新增全文字数、更新时间和状态信息
- 新增原生 EPUB 阅读器、目录、章节导航和插图阅读
- 新增米黄/白纸/护眼/夜间/OLED/自定义阅读背景
- 新增 TTF/OTF 字体导入、字号、字重、行距、段距和边距设置
- 新增按章节/段落保存的阅读进度
- Cookie 使用 Android Keystore 加密保存，不保存密码
- GitHub Actions 增加搜索、详情和 EPUB 读取回归测试

## [0.2.0] - 2026-09-25

- Android 迁移为纯 Kotlin + Jetpack Compose
- 使用官方 MiuiX v0.8.8 作为 UI 基础
- 移除 Android Capacitor WebView 和 TypeScript 运行层
- 原生任务队列、进度通知、取消、历史恢复和文件保存
- 原生 OkHttp 限流/GBK、Jsoup 解析和 EPUB 打包
- Android MiSans 字体、动态主题和 GitHub Actions 原生 APK 门禁

## [0.1.0] - 2026-09-25

首个规范化 MVP：

- 本机单用户 Web 应用和中文三步式界面
- 公开 wenku8 书籍、目录、章节和插图解析
- GBK/GB2312 解码、图片本地化与 EPUB 3 打包
- 任务进度、取消、警告、历史记录和下载
- 离线自动化测试、真实公开源站验收和 EPUB 结构检查
- Windows 启动脚本、中文 SOP、发布清单和发布包脚本
- Git 分支、Conventional Commits 和语义化版本流程
