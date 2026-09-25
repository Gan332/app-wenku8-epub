# Android 源码

这是纯 Kotlin Android 宿主工程，使用 Jetpack Compose 和官方 MiuiX `v0.8.8`，不再依赖 Capacitor、WebView 或 Node.js。

主要模块：

- `ui/StudioApp.kt`：MiuiX 主题、搜索、创建流程、章节选择、导出进度和历史页面
- `ui/StudioViewModel.kt`：应用状态、搜索、详情和主题交互
- `settings/`：DataStore 应用主题、阅读设置、搜索历史和阅读进度
- `auth/LoginActivity.kt`：仅用于 wenku8 登录和验证码的 WebView
- `core/`：URL 白名单、OkHttp Cookie/限流、GBK 解码、搜索和 Jsoup 解析
- `epub/EpubBuilder.kt`：EPUB 3 / NCX 与本地图片打包
- `reader/`：EPUB 容器/目录解析、阅读器、背景、字体和分页设置
- `service/ExportJobManager.kt`：串行任务队列、实时进度、取消和历史恢复
- `service/ExportNotificationService.kt`：前台导出通知
- `file/EpubFileStore.kt`：保存到 `Download/EPUB` 和系统分享

## 构建

需要 JDK 21、Android SDK Platform 36 和 Build Tools 36.1.0：

```powershell
cd android
.\\gradlew.bat testDebugUnitTest assembleDebug
```

GitHub Actions 会自动安装 SDK、Gradle 9.4.1，并上传 debug APK。推送 `v*` 标签时，APK 会自动附加到 GitHub Release。

正式发布前请配置自有 keystore；当前仓库不包含签名密钥。
