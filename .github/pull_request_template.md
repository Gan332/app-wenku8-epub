## 变更内容

<!-- 用两三句话说清楚改了什么、为什么。 -->

## 关联 Issue

<!-- Closes #123；没有关联则写「无」。 -->

## 影响面

- [ ] 纯文案 / 文档
- [ ] UI 界面（Compose / MiuiX）
- [ ] 数据层（DataStore / 模型）
- [ ] 网络层（`core/` 的 HTTP、Cookie、端点、重试）
- [ ] EPUB 解析（`rust/epub-core` 或 `EpubReaderRepository`）
- [ ] 涉及安全边界（凭据、Cookie、URL 白名单、中继、WebView）

## 需要遵守的项目约定

改动命中下面这些时，对应单测会直接让 CI 变红，请确认已满足：

- [ ] 没有引入 material / material3 / material-icons（界面统一 MiuiX）
- [ ] 尺寸全部取自 `UiDimens`（App）或 `ReaderDimens`（阅读器），没有裸 `dp` / `sp`
- [ ] 颜色取自 `MiuixTheme.colorScheme`，没有写死颜色
- [ ] 没有新增 `preferencesDataStore` 实例
- [ ] 动效时长经 `Motion.duration()`，尊重系统「移除动画」
- [ ] 若改了 EPUB 解析：保留了 legacy 回退路径，并补了测试夹具

## 验证

- [ ] CI 已 `conclusion == "success"`（run 链接：）
- [ ] 若改动 release 变体或 `proguard-rules.pro`：已**真机冒烟**（R8 只在运行期暴露问题）

## 截图

<!-- UI 改动请附前后对比；纯逻辑改动可留空。 -->

## 备注

<!-- 踩到的坑、临时方案、已知未解决的部分。没有则删掉本节。 -->
