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

> 0.13.0 起阅读器改用 XY reader（见上）。**LightNovelReader 的阅读器（126 个 Kotlin 文件）
> 已整体移除**（`indi.dmzz_yyhyy.lightnovelreader` / `io.nightfish.lightnovelreader.api` 下的
> reader UI 与阅读数据层），连同 `LnrReaderActivity` / `LnrBridge`。
> 0.14.0 起**重新引入该项目的书源体系**（`io.nightfish.lightnovelreader.api`，见下），
> 但**只保留书源抽象与模型，不含阅读器 UI**。

## LightNovelReader 书源体系（0.14.0 起）

- **来源**：`io.nightfish.lightnovelreader.api`
- **上游**：[dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
  （引入基线 `dev/1.3`）
- **许可证**：Apache License 2.0
- **引入范围**：书源抽象与数据模型，共 102 个 Kotlin 文件
  - `api/web/`：`WebDataSource` / `WebBookDataSource` 书源契约、书源注册表
    （`WebBookDataSourceManagerApi`）、探索页提供者（`ExplorePageProvider` /
    `ExploreTapPageDataSource` / `ExploreExpandedPageDataSource`）与过滤器体系、搜索提供者
  - `api/book/`、`api/explore/`、`api/content/`、`api/identifier/`、`api/serializer/`、
    `api/xml/`、`api/text/`、`api/image/`、`api/util/`、`api/error/`：书本/章节模型、
    内容组件体系、标识符与序列化器
  - `api/plugin/`、`api/ui/ReaderStyle.kt` 等：插件接口与阅读样式数据类
- **说明**：Apache-2.0 与本项目的 MIT 许可证兼容。引入的源文件保留原始版权与
  Apache-2.0 文件头；未修改其许可证声明。

### 已修改的部分

| 改动 | 原因 |
| --- | --- |
| 删除 `api/ui/components/**` 与 `api/ui/theme/**`（3 个文件） | 依赖 material3，本项目界面统一 MiuiX 且有守卫单测 |
| 删除 `api/Route.kt`（整棵 LNR 应用导航树，300+ 行） | 本工程不引入 navigation3；`progressBookTagClick` 返回类型由 `NavKey?` 改为 `String?`，跳转由宿主决定 |
| 删除 `api/plugin` 的 `EntryProviderScope<NavKey>.onBuildNavHost()` 钩子 | 同上 |
| 删除 `api/doc/sample/**` | 上游示例代码，非运行期依赖 |
| 书源 HTTP 层**未沿用上游实现**（见下） | 上游 `Wenku8Api` 内嵌了作者本人的账号凭据且使用 Ktor，详见下节 |

### 未引入的上游能力

- **上游的 wenku8 书源实现（`defaultplugin/wenku8`）未直接引入**。原因：
  1. `Wenku8Api.kt` 硬编码了上游作者的 wenku8 账号 Cookie
     （`jieqiUserId` / `jieqiUserName` / `jieqiUserPassword`）并随每个请求发送，
     与本项目「不得保存用户密码」「Cookie 使用 Keystore 加密保存」的边界冲突；
  2. 其 HTTP 栈是 Ktor + 自建信号量，会绕过本项目的全局限流与 429 退避；
  3. 它抓取的 `toplist.php` / `tags.php` / `search.php` 等接口由站点控制登录，
     按本项目约定保持原样、不得规避。
  本项目改为在自有的限流客户端上实现 wenku8 书源。
- 上游的插件安装器与插件商店、Room 数据库、`api/userdata/**` 的持久化实现、
  `api/bookshelf/**`、material3 UI 组件。

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
| kotlinx-result | Apache-2.0 | 书源接口的 Result 类型 |
| dom4j | BSD-3-Clause | 书源 XML 构建 |
| Apache Commons Compress | Apache-2.0 | 压缩包页面源（ZIP / 7Z / TAR） |
| junrar | UnRAR License | RAR 解压（**仅用于解压**，不用于创建压缩包） |
| AndroidX Security Crypto | Apache-2.0 | Cookie Keystore 加密 |
| MiSans 字体 | 见字体文件内声明 | 应用与阅读器字体 |