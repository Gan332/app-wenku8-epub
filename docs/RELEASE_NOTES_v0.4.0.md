# v0.4.0 发布说明

发布日期：2026-09-25

## 修复

- 修复阅读器目录和设置按钮触摸区域过小、点击不响应的问题。
- 阅读器进入沉浸模式后隐藏状态栏、导航栏、顶部栏和底部栏。
- 点击正文中央区域显示或隐藏阅读控制栏。
- 目录和设置改为 Material 3 BottomSheet，长列表可以滚动。
- 底部章节按钮增加明确的启用/禁用状态。
- 退出阅读器时恢复系统栏。

## 新增

- 设置页和阅读器设置中新增系统文件选择器“导入 EPUB”。
- 新增沉浸模式和保持屏幕常亮设置持久化。
- 统一阅读器 48dp 按钮触摸区域、BottomSheet 间距和正文边距。
- 目录支持当前章节标记和章节进度显示。

## 参考实现

本版本参考了：

- [MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android)：沉浸阅读、章节控制和阅读设置。
- [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)：Compose 工具栏动画、BottomSheet、阅读器状态和进度结构。

当前应用继续使用 Kotlin + Jetpack Compose + MiuiX，未引入参考项目的旧 View 层或完整插件系统。

## 已知限制

- 左右模式仍以章节为翻页单位，章节内部使用上下滚动。
- 仅支持标准无 DRM EPUB，不支持加密 EPUB。
- 搜索和 EPUB 源站功能仍遵循原有网络与登录边界。
