# v0.6.0 发布说明

发布日期：2026-09-25

## 新增

- 搜索与浏览书籍不再需要登录
- 新增本地书目索引，抓取一次即可长期离线搜索
- 搜索支持书名、作者、标签
- 搜索完全在设备内存完成，断网可用
- 探索页改为年度精选榜与月度新书榜
- 设置页新增「更新书目缓存」，显示缓存本数、上次更新时间和抓取进度

## 使用的公开来源

本版本只访问 wenku8 对匿名访客公开返回 200 的页面：

| 页面 | 用途 |
| --- | --- |
| `modules/article/articleinfo.php?id=N` | 书籍详情 |
| `modules/article/authorarticle.php?author=X&page=N` | 同作者作品列表 |
| `/zt/sugoi/{year}.php` | 年度精选榜 |
| `/zt/booklist/{yyyyMM}.php` | 月度新书榜 |
| `/novel/2/{id}/index.htm` | 章节目录 |

抓取器使用独立的无 Cookie 客户端，即使设备上存在登录态也不会携带 Cookie。

## 安全边界

以下接口由站点自行控制登录，应用**保持原样，不做规避**：

- `modules/article/search.php`
- `modules/article/articlelist.php`
- `modules/article/toplist.php`
- `modules/article/tags.php`

登录仍然是可选的补充手段，用于站内搜索；不是使用本应用的前置条件。

## 说明

- 索引是本地快照，首次使用需要在设置页手动更新一次
- 抓取遵守既有全局限流（1 秒/请求）与 HTTP 429 退避
- 单次更新默认最多抓取 200 本详情
- 存储位置：`filesDir/catalog/`

## 已知限制

- 本地索引不含章节正文，导出仍需在线获取
- 索引新鲜度取决于用户何时更新
- 失效书籍（详情页已删除）会被跳过并计入统计
