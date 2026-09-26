# v0.8.0 发布说明

发布日期：2026-09-26

## MiuiX 升级到 0.9.4

这是本轮最大的改动，也是破坏性最大的改动。

| 项目 | 变化 |
| --- | --- |
| Maven 坐标 | `top.yukonga.miuix.kmp:miuix` 止于 0.8.8；0.9.x 在 `miuix-ui` / `miuix-core` / `miuix-icons` |
| `SuperDialog` | `kmp.extra` 包整体删除，改用 `kmp.overlay.OverlayDialog` |
| `Checkbox` | 由 `checked` / `onCheckedChange` 改为 `state: ToggleableState` + `onClick` |
| AGP | 9.1.0 → 9.4.1 |
| Kotlin | 2.3.20 → 2.4.20 |
| Compose Multiplatform | 1.10.3 → 1.12.0 |
| Gradle | 9.4.1 → 9.6.0（AGP 9.4.1 的硬性最低要求） |
| compileSdk | 36 → 37（MiuiX 0.9.4 的 AAR 强制要求），targetSdk 保持 36 |

**主题层完全没变**：`TextStyles` 的 14 个字段、`ThemeController`、`ColorSchemeMode`、
`MiuixTheme` 签名在 0.9.4 里都保持原样，所以 `AppMiuixTheme.kt` 一行未改，
阅读器的主题行为也不会因为升级而改变。

### 踩过的坑（已写进 AGENTS.md）

- SDK 37 的平台包**强制带小版本号**，`platforms;android-37` 在 Google 仓库里不存在，
  基础包是 `platforms;android-37.0`（`IsBaseSdk=true`）
- `gradle-wrapper.properties` 和 workflow 里的 `gradle-version` **两处必须一致**，
  只改一处会在 `com.android.internal.version-check` 挂掉

## 通知与导出进度

- **点击进行中任务的通知直接进入该任务进度界面**
- 任务结束（完成/失败/取消）后通知不再直接消失，点击进入书架导出记录
- 路由决策抽成纯函数 `NotificationRoute.resolve`，脱离 Android 运行时即可测试，
  覆盖未知路由无副作用、缺 jobId 忽略、jobs 未加载不误判、查无此任务降级、重复投递幂等
- 书架首页新增进行中任务区：书名、进度条、当前章节、已完成/总章节、插图数、取消按钮
- 没有进行中任务时该区域完全不渲染，不占任何空间

## 配置导入导出

设置新增「配置导入导出」二级页，可把主题与阅读器设置导出为 JSON 并在另一台设备导入。

**凭据隔离是结构性的，不是靠导出时记得剔除**：导出模型的所有字段都是标量
（`Int` / `Float` / `Long` / `Boolean` / `String`），物理上装不进 Cookie、密码、
Token 或书架数据。单元测试用反射遍历 DTO 字段强制这条不变量——将来谁加一个
`cookie: String?` 字段，测试会直接失败，而不是悄悄泄露会话。

另有两处刻意的设计：

- **不导出 `fontUri`**：它是设备本地的 `content://` 授权地址，换台设备就失效，
  还会泄露本机路径。字体文件不走配置同步。
- **枚举以字符串承载**：未知枚举值变成「跳过该字段并计数」，
  而不是让整份文件解析失败。

导入时逐字段写入并复用既有范围钳制（字号、行高、边距等），单个字段非法只跳过该字段，
最终提示「导入成功 N 项，跳过 M 项」并列出跳过原因。

## 在线阅读

Wenku8 书籍现在可以直接在应用内阅读，不用先导出 EPUB。

入口在书籍操作二级界面的「在线阅读」，按来源区分：本地 EPUB 不会出现该选项。

**复用现有阅读器渲染层**：把 wenku8 的目录与正文装配成既有的
`ReaderBook` / `ReaderChapter` / `ReaderBlock`，渲染代码完全共用。
为此从 `ReaderScreen` 抽出 `ReaderScreenCore`，并抽出 `ReaderActions` 接口
（原 `ReaderScreen` 签名逐字不变，`ReaderActivity` 零改动）。
所以在线与 EPUB 的字体、背景、沉浸模式、目录、阅读进度、时长统计完全一致。

### 缓存与离线

- 章节正文缓存到 `filesDir/online-cache/{bookId}/{chapterId}.json`
- 每本上限最近 50 章，按 LRU 淘汰
- 临时文件 + rename 原子写入，缓存损坏会被丢弃并回落到网络
- **命中缓存不发网络请求，所以已读过的章节断网可读**
- 正文插图复用封面加载器（内存 LruCache + 磁盘缓存 + 限流客户端），
  缓存键仅由 URL 派生，封面与插图 URL 路径不同故不会互相覆盖

### 边界

- 章节请求**严格串行**，不做并发预取（项目 HTTP 客户端有全局 1 秒限流与 429 退避）
- 遇到登录页提示「该内容需要登录」并停止，**不做任何绕过**
- 章节 404/410 落墓碑并跳转相邻章节；429 不会被误判为章节删除
- 路径穿越在缓存键层面被中和

## 已知限制

- 左右翻页模式下，未加载的相邻章节会先显示空白页（不做预取是硬性要求）；
  默认的上下滚动模式无此问题
- 在线模式设置面板里的「导入 EPUB」是空操作
- `.gone` 墓碑不会自动过期，源站恢复某章后需清 `filesDir/online-cache/{bookId}`
- 设置里的「清除封面缓存」会连带清掉正文插图缓存
- 导出配置的收件通知只保留主题与阅读器设置，书架与阅读记录不含在内

## 验证

61 个单元测试全部通过，debug APK 构建与 GitHub Actions 上传通过。
