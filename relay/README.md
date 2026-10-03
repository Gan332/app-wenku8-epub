# 自建中继（Cloudflare Worker）

直连 wenku8 被 Cloudflare 403 拦住时，可以让**免登录公开页**走一条自建通道。
本目录是完整的 Worker 实现，用**你自己的** Cloudflare 账号部署，App 里只填域名。

## 它做什么 / 不做什么

| 做 | 不做 |
| --- | --- |
| 代理 `articleinfo.php`、`authorarticle.php`、目录页、`/zt/sugoi/`、`/zt/booklist/` | 代理 `login.php`、`search.php`、`toplist.php`、`tags.php`、`articlelist.php` |
| 保留上游 GBK 原始字节 | 解码 / 重编码页面 |
| 5 分钟缓存 | 转发 Cookie 或 Authorization |
| 每 IP 每分钟 60 次的简单限流 | 记录访问日志（默认无日志） |
| | 新增任何源站没有的接口 |

不部署也能用 App：中继只是**可选传输路径**，App 默认直连。

## 部署（你自己的 Cloudflare 账号）

```bash
# 1. 安装并登录
npm i -g wrangler
wrangler login

# 2. 在本目录初始化（把 name 改成你自己的）
cd relay
cp wrangler.toml.example wrangler.toml
# 编辑 wrangler.toml：name = my-wenku8-relay

# 3. 部署，得到 https://my-wenku8-relay.<subdomain>.workers.dev
wrangler deploy
```

也可以在 Cloudflare 控制台用「Workers → Create Worker → 导入 `worker.js`」，
再在「设置 → 变量与绑定」里无需任何变量。

## 在 App 里启用

1. 打开 **设置 → 网络**
2. 「中继端点」填 `https://my-wenku8-relay.<subdomain>.workers.dev`
3. 点「保存端点」，再打开开关
4. 点「测试公开端点」验证（会用 `articleinfo.php` 匿名探测）

中继只影响公开页：搜索、排行榜、官方标签与正文**始终直连**，因为那些需要你本人的会话
Cookie，而中继不应该看到它（`Wenku8Url.carriesSession` 保证 Cookie 只发往 wenku8 域名）。

## 路径契约

Worker 的 `ALLOWED` / `DENIED` 与 App 侧 `Wenku8Endpoint.isRelayablePath` 一一对应，
两侧都有契约测试（`RelayEndpointTest.relayPathContractMatchesWorkerAllowList`），
改了一边必须改另一边，否则 CI 会红。

## 注意

- 这是**你自己的**基础设施：Worker 的流量、缓存与日志都由你的 Cloudflare 账号承担
- 不要使用他人提供的中继：它能看到你请求的所有书籍编号与关键词
- Worker 是可选的；直连可用时建议关掉中继，减少一跳延迟