# Wenku8 EPUB 工坊

本机单用户的轻小说 EPUB 整理工具：输入 wenku8 书籍或目录网址，选择章节后下载正文与插图，并生成包含本地图片的 EPUB 3 文件。

> 仅处理无需登录、付费或访问验证即可读取的公开页面。下载内容仅用于用户拥有合法使用权的个人离线阅读，并遵守源站条款。应用不绕过登录、付费墙、Cloudflare 验证或其他访问控制。

## 功能

- 解析书籍元数据、卷名和章节目录
- 支持 GBK/GB2312 页面解码
- 搜索、全选或清空章节
- 下载正文与插图并将图片本地化
- 生成 EPUB 3，同时保留 NCX 目录以兼容更多阅读器
- 显示章节、图片和封面的跳过警告
- 支持取消任务、历史记录和本地下载
- 默认仅监听 `127.0.0.1`

## 环境要求

- Windows 10/11（应用也可在其他支持 Node.js 20+ 的系统运行）
- Node.js 20 或更高版本
- npm
- 可访问目标公开页面的网络

检查 Node.js：

```powershell
node --version
npm --version
```

## 快速开始

### Windows 一键启动

双击项目目录中的：

```text
start.bat
```

脚本会在首次运行时安装依赖、打开浏览器并启动本机服务。

### 命令行启动

```powershell
npm install
npm start
```

浏览器访问：

```text
http://127.0.0.1:3210
```

开发模式：

```powershell
npm run dev
```

## 使用流程

1. 输入书籍页、目录页 URL，或直接输入书籍 ID。
2. 检查书名、作者和目录。
3. 搜索并选择需要导出的章节。
4. 点击“继续导出”，按需选择封面。
5. 点击“开始生成 EPUB”。
6. 等待任务完成；如出现警告，先查看被跳过的资源。
7. 下载 EPUB，并使用 EPUB 阅读器检查目录、正文和插图。

详细操作见 [`docs/USER_SOP.md`](docs/USER_SOP.md)。

## 数据目录

```text
data/jobs/   任务 JSON 记录
output/       生成的 EPUB 文件
```

记录和文件默认保留，应用不会自动删除。请在确认不再需要后手动清理。

## Android 独立应用源码

Android 版本已迁移为纯 Kotlin + Jetpack Compose + MiuiX 原生应用，不需要启动 Node.js 服务，也不加载业务 WebView。网页版仍使用原有 HTML/CSS/JavaScript。

```text
android/             原生 Kotlin 业务层、MiuiX Compose UI、搜索、阅读器和导出服务
.github/workflows/   GitHub Actions 原生 APK 构建与 Release 流程
```

### Android 功能

- MiuiX 应用主题：跟随系统、浅色、深色、动态色和自定义强调色
- 独立书架：在线 Wenku8 书籍和本地 EPUB，可置顶、移除和继续阅读
- Wenku8 数据源探索页：今日更新、热门、新书、动画化、分类和标签
- wenku8 登录态搜索：按书名/作者搜索，登录 Cookie 使用 Keystore 加密保存
- 书籍详情：作者、分类、连载状态、更新时间、全文字数、简介、标签和目录
- 阅读统计：总时长、今日时长、连续天数、每日时长和按书籍统计
- EPUB 阅读器：章节目录、章节导航、插图、左右章节翻页和上下滚动
- 阅读设置：米黄/白纸/护眼/夜间/OLED/自定义背景，字体、字号、字重、行距、段距和边距
- 自定义 TTF/OTF 字体导入，阅读进度按章节和段落恢复
- 导出 EPUB 保存到 `Download/EPUB`、系统分享和历史任务重新阅读

本地需要 JDK 21、Android SDK Platform 36 和 Build Tools 36.1.0：

```powershell
cd android
.\\gradlew.bat testDebugUnitTest assembleDebug
```

Android 任务历史保存在应用私有数据目录，正式发布前请使用自有 keystore；当前源码不包含签名密钥。

### GitHub Actions APK

工作流位于 `.github/workflows/android-apk.yml`：

- 推送到 `main`、提交 Pull Request 或手动触发时，CI 构建 debug APK 并保存为 Actions Artifact
- 推送 `v*` 标签时，额外把 APK 挂到 GitHub Release
- 目标仓库：`https://github.com/Gan332/app-wenku8-epub`
- APK 不直接提交到 Git，避免二进制文件污染仓库历史

推送代码到目标仓库：

```powershell
git remote add origin https://github.com/Gan332/app-wenku8-epub.git
git add .
git commit -m "feat: add standalone Android APK source and CI"
git push -u origin main
```

推送 `v0.5.0` 等版本标签会触发 Release APK：

```powershell
git tag v0.5.0
git push origin v0.5.0
```

## 测试与验收

离线回归测试：

```powershell
npm run check
npm test
npm run smoke:epub
```

真实公开源站验收：

```powershell
npm run test:live
```

真实源站测试会访问一个公开书籍和一个公开插图章节，生成临时 EPUB 并检查下载接口及容器结构。源站不可用、限流或出现访问验证时，测试会失败；不要通过绕过访问控制来“修复”测试。

完整门禁：

```powershell
npm run verify
npm run test:live
```

## 发布

```powershell
npm run release:package
```

命令会在 `dist/` 生成：

- `wenku8-epub-studio-v0.1.0.zip`
- `wenku8-epub-studio-v0.1.0.zip.sha256`

发布包不包含 `node_modules/`、任务记录、EPUB 输出、研究抓取文件或源站网页测试样本；测试样本仅保留在 Git 仓库中供开发回归使用。

## 文档

- [`docs/USER_SOP.md`](docs/USER_SOP.md)：用户操作流程
- [`docs/ENGINEERING_SOP.md`](docs/ENGINEERING_SOP.md)：Git、开发、测试和发布流程
- [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)：发布验收清单
- [`docs/RELEASE_NOTES_v0.5.0.md`](docs/RELEASE_NOTES_v0.5.0.md)：v0.5.0 书架、探索、数据源和阅读统计发布说明
- [`docs/RELEASE_NOTES_v0.4.0.md`](docs/RELEASE_NOTES_v0.4.0.md)：v0.4.0 阅读器沉浸模式、按钮、EPUB 导入和 BottomSheet 修复
- [`docs/RELEASE_NOTES_v0.3.0.md`](docs/RELEASE_NOTES_v0.3.0.md)：v0.3.0 主题、搜索与 EPUB 阅读器发布说明
- [`docs/RELEASE_NOTES_v0.1.0.md`](docs/RELEASE_NOTES_v0.1.0.md)：v0.1.0 发布说明与回滚说明
- [`CHANGELOG.md`](CHANGELOG.md)：版本记录

## 项目结构

```text
public/       本地 Web 界面
android/      原生 Kotlin、Compose、MiuiX 搜索与 EPUB 阅读器
src/          Express 服务、解析器、任务和 EPUB 生成器
scripts/      离线、真实源站和发布验收脚本
test/         Node.js 自动化测试与网页样本
data/         本机任务记录（Git 忽略）
output/       EPUB 输出（Git 忽略）
docs/         中文 SOP
```

## 故障排查

- **无法启动**：确认 Node.js 版本、端口 `3210` 是否被占用、依赖是否安装成功。
- **源站要求验证**：停止重试，不尝试绕过；改用你有权访问且无需验证的公开页面。
- **HTTP 429（请求过多）**：应用会按 `Retry-After` 和退避策略自动等待，并降低请求频率；如果仍然被限流，请暂停任务，等待源站限制解除后再重试，不要连续点击重试。
- **章节失败**：查看任务警告，单独重试失败章节。
- **图片缺失**：重新生成；若源站图片不可访问，正文仍会保留并显示警告。
- **EPUB 无法打开**：运行 `npm run smoke:epub`，检查容器、manifest 和 XML。
- **历史记录异常**：检查 `data/jobs/` 中的 JSON 是否损坏。

## 许可与内容边界

代码采用 [MIT License](LICENSE)。内容权利归各自权利人所有；用户需自行确认下载和离线阅读的合法性。项目不提供访问控制绕过、账号共享、批量公开服务或云端同步。
