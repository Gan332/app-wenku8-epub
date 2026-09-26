# AGENTS.md

文库 EPUB 工坊（`app-wenku8-epub`）的智能体工作说明。本文件记录项目约定、构建验证方式和远程同步流程，任何自动化代理在修改仓库前都应先阅读。

## 1. 项目概况

一个把 wenku8 公开轻小说整理为 EPUB 的应用，包含两套实现：

- `public/` + `src/`：本机单用户 Web 版本（Express + 原生前端）
- `android/`：独立的原生 Android 版本（Kotlin + Jetpack Compose + MiuiX）

当前主要发版对象是 **Android 原生应用**。Android 版本不需要 Node.js 服务，WebView 仅用于 wenku8 登录。

当前版本：`0.7.0`（versionCode 7）

仓库地址：`https://github.com/Gan332/app-wenku8-epub`

## 2. 技术栈

### Android

- Kotlin
- Jetpack Compose
- MiuiX `v0.9.4`（依赖坐标 `top.yukonga.miuix.kmp:miuix-ui` + `miuix-core` + `miuix-icons`）
- Material3（仅用于阅读器 BottomSheet 和 Slider）
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
android/app/src/main/java/com/wenku8/epubstudio/
├── MainActivity.kt              入口 Activity，ActivityResult 启动器
├── Wenku8Application.kt         依赖容器：设置、书架、统计、数据源、任务
├── core/                        URL 白名单、HTTP、Cookie、解析、探索数据源
├── data/                        DataStore：设置、书架、阅读统计
├── epub/                        EPUB 3 + NCX 打包
├── file/                        EPUB 保存、分享、字体导入
├── model/                       业务模型（Book、Chapter、BookshelfEntry 等）
├── reader/                      EPUB 解析、阅读器、进度、设置
├── service/                     导出任务队列、进度通知
├── settings/                    设置模型与 DataStore 仓储
└── ui/                          Compose 页面和 ViewModel
```

页面：书架（含书籍操作二级界面）、探索、创建流程（源站/详情/章节/导出/进度）、设置（二级：主题与外观/阅读器设置/阅读统计/书目缓存/关于）。

## 4. 关键设计约束

### 4.1 主题统一

首页和阅读器必须共用 `ui/AppMiuixTheme.kt`，禁止在阅读器里硬编码 `ThemeController` 或 `ColorSchemeMode`。

- 主题模式、动态色、强调色来自 DataStore
- MiSans 字体通过 `AppMiuixTheme` 统一注入
- 阅读正文背景可独立自定义，但控件、BottomSheet、工具栏跟随应用主题

### 4.2 安全与源站边界

- 只允许 wenku8.net / wenku8.cc / wenku8.com
- 阻止本机和内网地址，重定向后重新校验
- 搜索与浏览**不要求登录**，靠本地书目索引实现
- **不得**绕过登录、验证码、付费墙或访问控制
- **不得**保存用户密码
- Cookie 使用 Keystore 加密保存，过期需清理
- EPUB 解析必须限制解压大小、条目大小，并拒绝路径穿越
- 不支持加密/DRM EPUB

#### 免登录书目索引（0.6.0 起）

只允许抓取 wenku8 对匿名访客公开返回 200 的页面：

| 页面 | 用途 |
| --- | --- |
| `modules/article/articleinfo.php?id=N` | 书籍详情 |
| `modules/article/authorarticle.php?author=X&page=N` | 同作者作品 |
| `/zt/sugoi/{year}.php` | 年度精选榜 |
| `/zt/booklist/{yyyyMM}.php` | 月度新书榜 |
| `/novel/2/{id}/index.htm` | 章节目录 |

以下接口由站点控制登录，**保持原样，不得规避**：

- `modules/article/search.php`
- `modules/article/articlelist.php`
- `modules/article/toplist.php`
- `modules/article/tags.php`

抓取器 `CatalogCrawler` / `CatalogRepository` 必须使用**独立的无 Cookie 客户端**，
即使设备存在登录态也不得携带 Cookie。

### 4.3 状态与持久化

- 设置、书架、阅读统计、阅读进度均使用 DataStore，不引入 Room
- `preferencesDataStore(name = "wenku8_settings")` 全项目只有一个实例，位于 `data/AppDataStore.kt`
- 阅读统计只在阅读器前台 `onStart` 到 `onStop` 之间累计，单次最长 30 分钟
- 书架条目按 `id`/`bookId` 去重

### 4.4 Compose 约定

- 按钮最小触摸区域 48dp
- 长列表使用 `LazyColumn`，不要一次性构建全部条目
- 读图/解析类操作放 `Dispatchers.IO`
- MiuX 组件优先，Material3 仅在需要 BottomSheet/Slider 时使用
- 主题色从 `MiuixTheme.colorScheme` 获取，不要写死颜色

### 4.5 被动触发（0.7.0 起）

除用户点击外，应用**不发起任何书目网络请求**：

- 「更新书目缓存」只存在于设置 → 书目缓存
- 探索页只读本地索引，缓存为空时提供跳转入口
- 打开未缓存书籍必须由用户点击「加载完整详情」后才请求
- `CatalogRepository.ensureBook` / `expandAuthor` 不得在页面加载路径上自动调用

### 4.6 封面加载

自研 `ui/cover/CoverRepository`，**不引入 Coil/Glide**：第三方图片库会自建
OkHttp 客户端，从而绕过 `Wenku8HttpClient` 的全局 1 秒限流与 429 退避。

- 内存 LruCache + 磁盘 `cacheDir/covers`
- 下载走共享的无 Cookie 客户端
- 按目标宽度下采样，避免 OOM
- 缩放手势自行实现（`detectTransformGestures`），不引入 panpf

## 5. 构建与验证

### 5.1 不要在本地编译 Android

本项目 **不在本地执行 Gradle 构建**，所有 Android 编译和测试通过 GitHub Actions 完成。

工作流：`.github/workflows/android-apk.yml`

- 推送到 `main`：构建 + 单元测试 + 上传 Artifact
- 推送 `v*` 标签：额外附加 APK 到 Release

### 5.2 提交后必须跟踪 CI

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

### 5.3 单元测试

测试位于 `android/app/src/test/java/com/wenku8/epubstudio/`，覆盖：

- URL 白名单和书籍 ID 规范化
- 目录、插图标记、书籍元数据和字数解析
- 搜索结果与登录页识别
- 探索 URL 和阅读统计序列化
- EPUB mimetype、OPF 与阅读器回读

新增解析逻辑时，必须同时新增测试夹具。

## 6. 推送方式（重要）

本机 `git push` 在 Windows Schannel 下不可靠，**必须使用 GitHub Git Database API 推送**：

1. `gh api repos/<repo>/git/ref/heads/main` 取当前 `parent` SHA
2. `gh api repos/<repo>/git/commits/<parent>` 取 `base_tree`
3. 对每个改动文件 `POST /git/blobs`（base64 内容）
4. `POST /git/trees`（`base_tree` + 文件树）
5. `POST /git/commits`（message + tree + parents）
6. `PATCH /git/refs/heads/main`（新 SHA）

`gh` 可执行文件：`D:\SW\gh\gh.exe`

注意：脚本中某个 `gh api` 响应可能为空导致 `unexpected end of JSON input`，此时先检查远端 SHA 是否已更新，再决定是否重试，避免重复提交。

## 7. 发版流程

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

## 8. 提交信息

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

- [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
  - `api/web/`：数据源与探索页接口
  - `defaultplugin/wenku8/`：Wenku8 数据源实现
  - `data/bookshelf/`：书架模型与排序
  - `data/statistics/`：阅读时长与连续天数统计
  - `ui/book/reader/`：阅读器工具栏、BottomSheet、沉浸模式
- [MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android)
  - Wenku8 页面组织方式和阅读交互

只借鉴思路，不复制其旧 View 架构（XML、AsyncTask、自建图片缓存）。

## 10. 禁止事项

- 不要在本地执行 Gradle 构建
- 不要使用 `git push`
- 不要提交 APK、密钥、签名文件或 `local.properties`
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
