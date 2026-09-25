# 更新记录

本项目遵循语义化版本。

## [0.3.0] - 2026-09-25

- 新增 MiuiX 应用主题模式、动态色和强调色设置
- 新增 wenku8 登录后按书名/作者搜索
- 书籍详情新增全文字数、更新时间和状态信息
- 新增原生 EPUB 阅读器、目录、章节导航和插图阅读
- 新增米黄/白纸/护眼/夜间/OLED/自定义阅读背景
- 新增 TTF/OTF 字体导入、字号、字重、行距、段距和边距设置
- 新增按章节/段落保存的阅读进度
- Cookie 使用 Android Keystore 加密保存，不保存密码
- GitHub Actions 增加搜索、详情和 EPUB 读取回归测试

## [0.2.0] - 2026-09-25

- Android 迁移为纯 Kotlin + Jetpack Compose
- 使用官方 MiuiX v0.8.8 作为 UI 基础
- 移除 Android Capacitor WebView 和 TypeScript 运行层
- 原生任务队列、进度通知、取消、历史恢复和文件保存
- 原生 OkHttp 限流/GBK、Jsoup 解析和 EPUB 打包
- Android MiSans 字体、动态主题和 GitHub Actions 原生 APK 门禁

## [0.1.0] - 2026-09-25

首个规范化 MVP：

- 本机单用户 Web 应用和中文三步式界面
- 公开 wenku8 书籍、目录、章节和插图解析
- GBK/GB2312 解码、图片本地化与 EPUB 3 打包
- 任务进度、取消、警告、历史记录和下载
- 离线自动化测试、真实公开源站验收和 EPUB 结构检查
- Windows 启动脚本、中文 SOP、发布清单和发布包脚本
- Git 分支、Conventional Commits 和语义化版本流程
