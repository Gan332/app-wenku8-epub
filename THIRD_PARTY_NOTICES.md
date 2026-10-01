# 第三方组件声明

本项目包含以下第三方组件。

## XY reader（阅读器）

- **来源**：`com.xyreader`（保留上游包名）
- **上游**：[TerryYu12/xy-reader](https://github.com/TerryYu12/xy-reader)（引入基线 `main` @ `4136b90`，v0.4.8）
- **许可证**：MIT License
- **引入范围**：阅读器全链路
  - `com.xyreader.core`：`PageSource` / `Chapter` 契约、`BookFormat`、`BookEntity`、
    `BookmarkEntity`、`ReaderPrefs`、`NovelFonts`、`ArchiveFactory`
  - `com.xyreader.archive`：`NovelPageSource`（`StaticLayout` 预分页 → 透明底位图页）、
    `NovelTextExtractor`（TXT / EPUB 文本版 / MOBI 文本版解析）、`PageSources`、
    `ZipPageSource`、`RarPageSource`、`SevenZipPageSource`、`TarPageSource`、
    `PdfPageSource`、`MobiPageSource`、`DirectoryPageSource`、`PdfRenderMath`
  - `com.xyreader.reader`：`ReaderViewModel`（位图 LRU 缓存 + 前后各 2 页预载 +
    串行渲染 worker + 防抖存进度）、`ReaderScreen`（翻页 / 上下滚动、点击翻页、
    双击缩放、目录抽屉、书签、复制文字、亮度、屏幕方向）、`PageZoom`、`ImageDownscale`
  - `com.xyreader.ui`：`ReaderConfigScreen`（阅读配置管理页）、`NovelSpacingControls`、
    `Common`（只保留阅读器用到的 `CapsuleTab` / `formatDate`）
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
| material3 / material-icons → MiuiX 全量替换 | AGENTS §4.4：界面统一 MiuiX，有守卫单测强制 |
| `ui/Common` 只保留 `CapsuleTab` / `formatDate`，删去书库扫描控制器与导入 FAB | 本工程没有本地仓库体系，那些组件无宿主 |
| material3 `FilterChip` → 本地同名同签名实现（MiuiX `Surface` 胶囊） | MiuiX 无 FilterChip；保留签名可不动 5 处调用点 |
| `ReaderConfigScreen` 改为显式注入 `ReaderRepository` | 不再自行取全局单例 |

### 尚未引入

上游的 Room 数据库、Coil、WebDAV / Google Drive 客户端，以及书架、书库扫描、
仓库管理、分组等**书库管理**界面（本工程的数据源只有 wenku8，没有那套本地仓库体系）。

### 内置字体

| 字体 | 来源 | 许可 |
| --- | --- | --- |
| 霞鹜文楷 Lite | [LXGW WenKai](https://github.com/lxgw/LxgwWenKai) | SIL OFL 1.1 |
| 朱雀仿宋 | 璇玑造字 | SIL OFL 1.1 |

字体文件随 APK 打包分发；用户导入的自定义字体仅存于本机，版权归字体作者所有。

> 0.13.0 起阅读器改用 XY reader（见上）。**LightNovelReader 的 126 个 Kotlin 文件已整体移除**
> （`indi.dmzz_yyhyy.lightnovelreader` / `io.nightfish.lightnovelreader.api`），
> 连同 `LnrReaderActivity` / `LnrBridge` 与 LNR 专属依赖（kotlin-result、dom4j、navigation3-runtime）。
> 0.12.0 曾引入该组件（Apache-2.0），本版本不再包含其代码。

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