# 第三方组件声明

本项目包含以下第三方组件。

## XY reader（阅读器，引入中）

- **来源**：`com.xyreader`（保留上游包名）
- **上游**：[TerryYu12/xy-reader](https://github.com/TerryYu12/xy-reader)（引入基线 `main` @ `4136b90`，v0.4.8）
- **许可证**：MIT License
- **引入范围**：阅读器链路的数据层与排版引擎
  - `com.xyreader.core`：`PageSource` / `Chapter` 契约、`BookFormat`、`BookEntity`、
    `BookmarkEntity`、`ReaderPrefs`、`NovelFonts`、`ArchiveFactory`
  - `com.xyreader.archive`：`NovelPageSource`（`StaticLayout` 预分页 → 透明底位图页）、
    `NovelTextExtractor`（TXT / EPUB 文本版 / MOBI 文本版解析）、`PageSources`、
    `ZipPageSource`、`RarPageSource`、`SevenZipPageSource`、`TarPageSource`、
    `PdfPageSource`、`MobiPageSource`、`DirectoryPageSource`、`PdfRenderMath`
  - `com.xyreader.reader`：`ReaderViewModel`（位图 LRU 缓存 + 前后各 2 页预载 +
    串行渲染 worker + 防抖存进度）、`PageZoom`、`ImageDownscale`
- **说明**：MIT 与本项目的 MIT 许可证兼容。引入的源文件保留原始版权与许可证声明。

### 已修改的部分

| 改动 | 原因 |
| --- | --- |
| Room `@Entity` / `@PrimaryKey` 注解移除，`BookEntity` / `BookmarkEntity` 降级为纯数据类 | AGENTS §4.3：不引入 Room，设置/书架/进度统一走 DataStore |
| `LibraryRepository`（含书库扫描、本地仓库、分组、封面）→ 收窄为 `ReaderRepository` 八成员接口 | 阅读器只需要这些能力；不把上游整套应用数据层搬进来 |
| `AppGraph` → `ReaderGraph`（可安装的窄注入点） | 避免 `com.xyreader` 反向依赖宿主包名 |
| WebDAV / Google Drive 远程链路整体未引入（`RemoteArchiveSources`、`RemoteGdriveSources`、`HttpRangeChannel`，及 `ArchiveFactory` / `NovelPageSource` / `MobiPageSource` 的对应入口） | 本工程数据源只有 wenku8（AGENTS §4.2），无远程书库设施 |
| `R.font.misans_regular` → 复用本工程既有的 `R.font.misansvf` | 不重复打包同一款字体，省约 8MB |
| `ReaderPrefs` 加 `@Serializable`，持久化由 8 个独立键改为单键 JSON | 落在本工程既有的 `wenku8_settings` DataStore 上，遵守「实例唯一」约束 |
| `ReaderViewModel` 增加 `sourceOpener` 注入点 | 让宿主复用本工程既有的 EPUB 解析管线（Rust + 回退链），不交给上游解析器 |
| material3 / material-icons → MiuiX（待完成） | AGENTS §4.4：界面统一 MiuiX，有守卫单测强制 |

### 尚未引入

上游的 UI 层（`reader/ReaderScreen`、`ui/ReaderConfigScreen`、`ui/NovelSpacingControls`、
`ui/Common`）依赖 material3 与 material-icons，需整体 MiuiX 化后再引入；
上游的 Room 数据库、Coil、WebDAV / Google Drive 客户端、书架与仓库管理界面不在引入范围。

### 内置字体

| 字体 | 来源 | 许可 |
| --- | --- | --- |
| 霞鹜文楷 Lite | [LXGW WenKai](https://github.com/lxgw/LxgwWenKai) | SIL OFL 1.1 |
| 朱雀仿宋 | 璇玑造字 | SIL OFL 1.1 |

字体文件随 APK 打包分发；用户导入的自定义字体仅存于本机，版权归字体作者所有。

## LightNovelReader（阅读器，待移除）

- **来源**：`indi.dmzz_yyhyy.lightnovelreader` / `io.nightfish.lightnovelreader.api`
- **上游**：[dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
  （引入基线 `1.2.0-197-g5e2f7cd0`）
- **许可证**：Apache License 2.0
- **引入范围**：`:api` 模块的阅读器子集（`content/`、`image/`、`error/`、
  `userdata/`、`identifier/`、`settings/`、`book/`、`text/`、`util/`、`serializer/`、
  `ui/`）与 `ui/book/reader/**` 阅读器 UI，共 126 个 Kotlin 文件
- **说明**：Apache-2.0 与本项目的 MIT 许可证兼容。引入的源文件保留原始版权与
  Apache-2.0 文件头；未修改其许可证声明。

### 已修改的部分

为遵守本项目的技术约束（AGENTS.md §4.2/§4.3/§4.4/§4.7），对引入代码做了以下改动：

| 改动 | 原因 |
| --- | --- |
| material3 → MiuiX 组件 | 本项目 0.9.3 起移除 material 依赖，有守卫单测强制 |
| 删除 Hilt 注解，手动构造 ViewModel | 本项目不使用 DI 框架 |
| Coil / panpf → `CoverRepository` + 自研手势 | §4.7：图片字节不得绕过全局限流 |
| Room DAO → 单一 DataStore（`lnr_userdata_*` 前缀） | §4.3：不引入 Room，且 DataStore 实例唯一 |
| `Navigation.kt` 等导航基建移除，改用本项目弹层组件 | 避免引入 navigation3 UI 与 overlay 体系 |
| `RollingNumber.kt`（GPLv3，来自 EhViewer）及其调用点**未引入** | GPLv3 与本项目 MIT 许可证不兼容 |

### 未引入的上游能力

Room、Hilt、Coil、panpf、WorkManager、插件系统（`data/plugin`）、navigation3 UI、
Room 统计聚合、书架仓储、主设置页的更新渠道/排序选项组。

## MiuiX

- **来源**：`top.yukonga.miuix.kmp`（`miuix-ui` / `miuix-core` / `miuix-icons`），v0.9.4
- **上游**：[compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix)
- **许可证**：Apache License 2.0

## Rust 核心（epub-core）

- 位置：`rust/epub-core`，本项目自写
- 依赖：`zip`、`serde`、`serde_json`、`jni`
- 说明：EPUB 结构解析参考了 plato（Apache-2.0）的实现思路，**未复制其代码**。

## 其它依赖

| 组件 | 许可证 | 用途 |
| --- | --- | --- |
| OkHttp | Apache-2.0 | HTTP（带全局限流与 429 退避） |
| Jsoup | MIT | HTML 解析 |
| kotlinx.serialization | Apache-2.0 | 配置序列化 |
| kotlinx-result | Apache-2.0 | LNR 接口的 Result 类型 |
| dom4j | BSD-3-Clause | LNR 组件数据的 HTML 序列化 |
| Apache Commons Compress | Apache-2.0 | 压缩包页面源（ZIP / 7Z / TAR） |
| junrar | UnRAR License | RAR 解压（**仅用于解压**，不用于创建压缩包） |
| AndroidX Security Crypto | Apache-2.0 | Cookie Keystore 加密 |
| MiSans 字体 | 见字体文件内声明 | 应用与阅读器字体 |