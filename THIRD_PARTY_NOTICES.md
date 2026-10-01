# 第三方组件声明

本项目包含以下第三方组件。

## LightNovelReader（阅读器）

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
| AndroidX Security Crypto | Apache-2.0 | Cookie Keystore 加密 |
| MiSans 字体 | 见字体文件内声明 | 应用与阅读器字体 |