# Android 源码

这是 Capacitor Android 宿主工程，依赖仓库根目录的 `node_modules/@capacitor/android`。当前目录只包含源码和构建骨架，不包含已编译 APK。

后续构建顺序：

```powershell
# 在仓库根目录执行
npm install
npm run mobile:bundle
npx cap sync android
cd android
.\\gradlew.bat assembleDebug
```

需要 JDK 21、Android SDK Platform 35 和对应 Build Tools。正式发布前请使用自有 keystore；当前源码不包含签名密钥。

Android 自定义桥接：

- `Wenku8Http`：允许列表、GBK 解码、限速、429 退避、图片缓存和取消
- `TaskGuard`：前台导出服务与通知
- `EpubFile`：保存到 `Download/EPUB` 和系统分享
