# v0.5.0 发布说明

发布日期：2026-09-25

## 新增

- 首页改为独立书架
- 在线 Wenku8 书籍和本地 EPUB 都可以加入书架
- 书架支持置顶、移除和继续阅读
- 导出历史移动到书架内的导出记录区域
- 新增 Wenku8 数据源抽象和探索页面
- 探索页面支持今日更新、热门、新书、动画化、全部、标签分类
- 搜索并入探索页面，支持书名和作者搜索
- 书籍详情支持加入书架
- 新增阅读统计页面：总时长、今日时长、连续天数、最近 7 天和按书籍统计
- 阅读器启动和退出时记录阅读时长

## 主题

- 首页和阅读器共用统一 MiuiX 主题
- 阅读器目录和设置 BottomSheet 跟随应用主题
- 阅读正文背景仍可独立自定义

## 参考实现

- [dmzz-yyhyy/LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
  - 数据源接口
  - 探索页结构
  - 书架模型
  - 阅读统计模型
- [MewX/light-novel-library_Wenku8_Android](https://github.com/MewX/light-novel-library_Wenku8_Android)
  - Wenku8 源站页面组织方式

当前应用继续使用 Kotlin + Jetpack Compose + MiuiX，初始只注册 Wenku8 数据源。

## 已知限制

- 探索和搜索部分 wenku8 页面可能要求登录
- 左右模式仍以章节为翻页单位，章节内部使用上下滚动
- 仅支持标准无 DRM EPUB
