# 安全策略

## 支持的版本

当前处于预发布阶段（`0.18.x` 为 prerelease，`0.19.0-alpha01` 为当前开发版）。只有 `main` 与最新标签会获得修复。

| 版本 | 状态 | 是否修复安全问题 |
| --- | --- | --- |
| `0.19.0-alpha*` | 开发中（`main`） | ✅ |
| `0.18.x` | 预发布 | ✅ |
| `0.17.x` 及更早 | 已停止维护 | ❌ |

## 什么算安全问题

- 凭据泄露：用户的 wenku8 会话 Cookie（`PHPSESSID` / `jieqiUserInfo`）、`cf_clearance`
  被写入日志、提交进仓库，或在非 wenku8 域名（含用户自建中继）上被观察到；
- 访问控制绕过：本项目**不应存在**任何绕过 wenku8 登录、付费墙或访问控制的代码路径；
- 网络边界：URL 白名单被绕过导致访问内网地址，或重定向后未复校验；
- 解压风险：EPUB 解析未限制解压体积或条目大小，或存在路径穿越；
- WebView 注入：登录 / 人机验证 WebView 存在可被利用的注入面。

## 什么**不**算安全问题

- **wenku8 返回 403「Attention Required」**：这是源站 Cloudflare 对部分网络的前置拦截，
  是**波动**的（同一网络实测出现过连续 8 次 403，也出现过 200），且与 UA / Referer /
  Accept-Language 无关。请重试或使用自建中继，不要为此类现象提安全报告。
  应用侧会把它转成 `UPSTREAM_CHALLENGE`，且**不会**自动弹窗打断用户。
- 使用来源不明的中继地址、登录异常等账号本身的问题。
- wenku8 站点的可用性、内容或版权问题。

## 敏感数据的处理方式

| 数据 | 存放位置 | 说明 |
| --- | --- | --- |
| 会话 Cookie | `EncryptedSharedPreferences`（`wenku8_session`） | 密钥在 Android Keystore（AES256-GCM），值侧 AES256-GCM、键侧 AES256-SIV |
| `cf_clearance` | 同上 | Cloudflare 人机验证凭证，**不是**账号凭据 |
| 阅读数据 | `wenku8_settings` DataStore | 书架、进度、统计，键前缀 `xy_reader_` |

几条硬约束（改动时不要破坏）：

- **免登录公开链路不得携带会话 Cookie**。它使用独立的无 Cookie 客户端，
  即使设备存在登录态也不得携带 `jieqiUserInfo` / `PHPSESSID`。
- **`cf_clearance` 由单独的 `clearanceCookieJar()` 承载**，只发这一枚，且只发往 wenku8 域名。
  这是会话链路与公开链路唯一的交叉点。
- **启用第三方中继后，会话 Cookie 仍然只发往 wenku8 自身域名**
  （`Wenku8Url.carriesSession` 是唯一判据，在存取两侧同时过滤）。
  中继永远收不到账号凭据，但**能**看到你请求的书籍编号与关键词。

## 自建中继的安全提醒

`relay/worker.js` 是供用户**用自己的 Cloudflare 账号**部署的可选组件（设置 → 网络）。
需要清楚它的边界：

- 流量、缓存与日志都由**你自己的** Cloudflare 账号承担；
- 它只代理匿名白名单端点（`articleinfo.php`、`authorarticle.php`、目录页、`/zt/sugoi/`、`/zt/booklist/`），
  明确拒绝 `login.php` / `search.php` / `toplist.php` / `tags.php`；
- 不转发 Cookie 与 Authorization，不记录访问日志；
- **不要使用他人提供的中继**：它能看到你请求的所有书籍编号与关键词。

## 报告方式

**请不要用公开 Issue 报告安全问题**，它会让漏洞细节公开可见。

优先使用 GitHub 的私密漏洞报告：仓库 → **Security** → **Advisories** → **Report a vulnerability**。
如果该入口不可用，请通过 GitHub 账号的私密联系方式联系维护者。

请尽量提供：

1. 受影响的版本（标签或 commit SHA）；
2. 复现步骤与最小复现材料；
3. 受影响的数据类型，以及是否观察到凭据离开 wenku8 域名。

我们会在确认后尽快给出修复方案，并在修复版本发布后于本文件更新受影响版本表。

## 内容与版权边界

本项目**仅处理无需登录、付费或访问验证即可读取的公开页面**，不提供任何形式的登录规避能力。
下载内容仅用于用户拥有合法使用权的个人离线阅读，并遵守源站条款。若你是权利人并认为仓库侵犯了你的权益，请通过上面的私密渠道联系维护者，我们会配合处理。
