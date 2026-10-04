# 贡献指南

本项目的构建和推送流程与常见 Kotlin 项目**不一样**，动手前请先读完这一页，能省掉很多来回。

完整的工程约定写在仓库根目录的 [`AGENTS.md`](AGENTS.md)，那里是权威版本；本页面向第一次参与的贡献者。

## 环境

| 用途 | 要求 |
| --- | --- |
| Android 构建 | JDK 21、Gradle 9.6.0、AGP 9.4.1（由 wrapper 提供） |
| Rust 解析核心 | Rust 工具链（仅 `rust/epub-core` 需要） |
| Web 版 | Node.js 20+ |
| 推送 | `gh` CLI（本机路径 `D:\SW\gh\gh.exe`） |

## 不要在本地跑 Gradle 构建

**所有 Android 编译与单元测试都由 GitHub Actions 完成。** 在本机跑 Gradle 既慢又会因为 SDK 版本差异产生误导性报错。

改完代码后：

1. 本地提交；
2. 用 `scripts/push-via-api.sh` 推送（见下一节）；
3. 跟踪 CI，修到 `conclusion == "success"` 为止。

查看运行状态：

```powershell
& 'D:\SW\gh\gh.exe' run list --repo Gan332/app-wenku8-epub --workflow android-apk.yml --limit 3
& 'D:\SW\gh\gh.exe' run view <runId> --repo Gan332/app-wenku8-epub --log-failed
```

## 推送必须走 Git Database API

本机 `git push` 在 Windows Schannel 下不可靠。**不要使用 `git push`**，改用仓库内的脚本：

```powershell
$env:LOCAL_BASE = "<上次推送对应的本地提交>"
& 'C:\Program Files\Git\bin\bash.exe' scripts/push-via-api.sh
```

`LOCAL_BASE` 是「内容与远端 `main` 等价的那个本地提交」。怎么找：远端 `main` 的 tree 与本地某个提交的 tree 应当逐项一致（内容等价，SHA 不同、行尾可能被归一化）：

```powershell
& 'D:\SW\gh\gh.exe' api repos/Gan332/app-wenku8-epub/git/ref/heads/main --jq '.object.sha'
& 'D:\SW\gh\gh.exe' api repos/Gan332/app-wenku8-epub/git/commits/<sha> --jq '.tree.sha'
git log --format='%H %T %s' -6
```

### 为什么不能用 SHA 判断祖先关系

脚本创建的 commit SHA 与本地不同，因此 `git merge-base --is-ancestor <remote> <local>` 会失败，这是**预期行为**，不是仓库出了问题。永远以**远端 tree** 作 `base_tree`，以本地提交作 diff 基准。

## 分支与提交信息

直接在 `main` 上工作，推送 `main`（含 PR）即触发 CI。

提交信息用 Conventional Commits：

```text
feat: 新功能
fix: 缺陷修复
docs: 文档
test: 测试
refactor: 重构
```

## 代码约定（会被测试卡住的部分）

以下几条有守卫单测，写完不满足会直接让 CI 变红：

- **界面只用 MiuiX**。0.9.3 起 material / material3 / material-icons 全量移除，
  `CoreSmokeTest.mainSourcesUseNoMaterialComponents` 会扫描全部源码，出现 material 引用即失败。
- **尺寸用令牌**。App 侧用 `ui/UiDimens.kt`，阅读器用 `com/xyreader/core/ReaderDimens.kt`。
  两份数值必须一致，由 `DesignTokenTest` 逐项比对；阅读器界面不允许再出现裸 `dp` / `sp`。
  （阅读器保留上游包名 `com.xyreader`，不能反向依赖宿主，所以令牌是两份而不是一份。）
- **不要硬编码主题色**，一律从 `MiuixTheme.colorScheme` 取。
- **不要新增 `preferencesDataStore` 实例**，全项目只有 `data/AppDataStore.kt` 里那一个。
- **动效要尊重系统的「移除动画」**，时长经 `ui/Motion.kt` 的 `Motion.duration()` 换算。
- **改 EPUB 解析逻辑必须同时加测试夹具**，并保留 legacy 回退路径
  （`EpubNative` 失败时回退 `parseArchiveLegacy`，native 是加速路径而不是单点依赖）。

## 安全与合规红线

这几条不是风格建议，是硬约束：

- 不绕过源站的登录、付费墙、Cloudflare 验证或任何访问控制；
- 不把用户密码、会话 Cookie 写进代码或日志（会话 Cookie 用 Keystore 加密后存 DataStore）；
- 不内置、不推荐任何第三方中继端点，包括上游作者的——中继是用户可选的自建传输路径，不是默认路径；
- 不提交密钥、签名文件、APK 或构建产物。

如果你要改的逻辑和上面任何一条冲突，请先开 Issue 讨论，不要直接提 PR。

## 提交 PR 前

- [ ] `git status` 干净，没有误提交构建产物
- [ ] 新增/修改的逻辑有对应单测
- [ ] 改动如果涉及 `rust/`，确认 CI 的 `cargo test` 通过
- [ ] 改动如果涉及 release 变体或 `proguard-rules.pro`，**真机冒烟**过
      （R8 只在运行期暴露问题，`assembleRelease` 通过不代表包能用）
- [ ] 推送后 CI `conclusion == "success"`
