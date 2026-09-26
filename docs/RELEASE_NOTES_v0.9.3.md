# 文库 EPUB 工坊 v0.9.3

阅读器用 MiuiX 组件重写，material3 与 material-icons 从工程中全量移除。

## 变更

### 阅读器界面全面 MiuiX 化

- **设置面板**重排为标准 MiuiX 结构：
  - `SmallTitle` 分组：背景 / 排版 / 颜色 / 翻页 / 开关 / 字体与导入
  - 每行用 `BasicComponent` 承载，滑块整行宽度
  - 字号、字重、行高、段距、边距 → MiuiX `Slider`（带磁吸与触感）
  - 「保持屏幕常亮」「沉浸模式」→ 文字按钮变标准 `Switch` 开关
  - 「翻页模式」→ 来回点的按钮变 `TabRow` 分段选择（左右章节 / 上下滚动）
- **目录面板**条目改为 `BasicComponent`，当前章带「阅读中」标记。
- 顶栏/底栏图标换成 MiuiX 图标集：`Back`、`ChevronBackward`、`ChevronForward`、
  `ListView`（目录）、`Tune`（设置）。

### material 全量移除（构建层面）

- 阅读器、全局设置页、首页导航、封面查看器四处的 material 引用清零。
- `material`、`material3`、`material-icons-extended` 三个依赖从 `build.gradle.kts`
  与版本目录中断开 —— v0.9.0 那类 material3 编译期/运行期版本错配崩溃
  （`NoSuchMethodError: ModalBottomSheet`）从依赖层面根除。
- 新增静态守卫测试 `mainSourcesUseNoMaterialComponents`：任何源文件再出现
  `androidx.compose.material*` 引用，单元测试直接失败。

## 不变

- 阅读行为与 0.9.2 完全一致：点按呼出菜单栏、断点恢复、图片三态、手势分层
  均零改动 —— 本次只换组件皮，不动逻辑。
- 公开 API（`ReaderScreen` / `ReaderScreenCore` / `ReaderActions`）签名不变。

## 升级说明

- 无数据迁移变更（沿用 `com.example.hyperreader`）。
- release 包经 R8 压缩，签名由 CI Secrets 提供。

## 验证重点

1. 阅读器设置面板：滑块可拖、开关可切、翻页 Tab 可换、色块可选，分组标题对齐。
2. 目录面板：条目可点，当前章显示「阅读中」。
3. 顶栏/底栏图标显示正常（图标形状与旧版略有差异，属预期）。
4. 点正文呼出/收起、断点恢复、图片显示不回归。
5. 首页底部导航（书架/探索/创建/设置）、导出记录保存/分享按钮、封面查看关闭按钮正常。
