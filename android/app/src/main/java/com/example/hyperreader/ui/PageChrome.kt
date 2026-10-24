package com.example.hyperreader.ui

/**
 * 页面外框的单一决策点（0.19.0-alpha05）。
 *
 * 0.19.0 之前，顶栏标题、返回动作、页内大标题是三处各写一遍的：
 *
 * - 顶栏标题写在 `StudioApp` 的 `TopAppBar(when { … })` 里，返回图标根本不存在，
 *   `0.18.0` 那条「顶栏账号入口」的注释下面其实什么都没渲染；
 * - 返回动作是**页内文字按钮**（`‹ 返回探索` / `‹ 返回` / `‹ 关闭向导`），
 *   每个全屏页各写一个，措辞与位置互不相同；
 * - 系统返回键只有设置二级页接了 `BackHandler`，其余全屏页（详情/搜索/书签/导出记录/导出向导）
 *   按一下直接退出 App——而底部导航在同一屏是隐藏的，用户没有别的退路。
 *
 * 这里把这些统一成两个纯函数（[chromeTitle] / [upTarget]），由 `StudioApp` 一次性消费：
 * 顶栏标题、顶栏返回按钮、`BackHandler` 都读同一份决策，改一处即改全应用。
 *
 * 纯函数的原因与 `ReaderUiState.resolveBack` 一致：不依赖 Compose 就能单测，
 * 页面层级顺序（哪个覆盖页在最上层）改动时能被测试立刻发现，而不是靠人肉比对五处 `when`。
 */

/** 当前显示的全屏层。名字与 `StudioApp` 里 `AnimatedContent` 的 key 一致。 */
enum class ShellPage { DETAIL, HISTORY, EXPANDED, SEARCH, BOOKMARKS, EXPORT, TABS }

/**
 * 返回键/返回图标的目标。
 *
 * `NONE` 表示没有可返回的上层——此时**不能**拦截返回键，交回系统（退出 App）。
 */
enum class UpTarget { NONE, CLOSE_DETAIL, CLOSE_HISTORY, CLOSE_EXPANDED, CLOSE_SEARCH, CLOSE_BOOKMARKS, CLOSE_EXPORT, CLOSE_SETTINGS }

/**
 * 覆盖页的优先级：详情 > 导出记录 > 榜单展开 > 搜索 > 书签中心 > 导出向导 > 主导航。
 *
 * 这个顺序既决定 `AnimatedContent` 显示哪一层，也决定返回键先关哪一层；
 * 两处必须一致，否则会出现「看得见 A，返回关掉的是 B」。
 */
fun StudioUiState.shellPage(): ShellPage = when {
    exploreDetailId != null -> ShellPage.DETAIL
    showJobHistory -> ShellPage.HISTORY
    exploreExpanded -> ShellPage.EXPANDED
    searchPageOpen -> ShellPage.SEARCH
    bookmarkCenterOpen -> ShellPage.BOOKMARKS
    exportStep != null -> ShellPage.EXPORT
    else -> ShellPage.TABS
}

/** 设置二级页的标题；索引页与 [chromeTitle] 的「设置」共用同一张表。 */
fun SettingsSection.settingsTitle(): String = when (this) {
    SettingsSection.OVERVIEW -> "设置"
    SettingsSection.ACCOUNT -> "账号"
    SettingsSection.NETWORK -> "网络"
    SettingsSection.APPEARANCE -> "主题与外观"
    SettingsSection.READER -> "阅读器设置"
    SettingsSection.STATISTICS -> "阅读统计"
    SettingsSection.CATALOG -> "书目缓存"
    SettingsSection.CONFIG -> "配置导入导出"
    SettingsSection.ABOUT -> "关于"
}

/** 导出向导的标题：与面包屑的四个步骤一一对应。 */
fun ExportStep.exportStepTitle(): String = when (this) {
    ExportStep.RESOLVING -> "解析目录"
    ExportStep.CHAPTERS -> "选择章节"
    ExportStep.PACKAGING -> "导出设置"
    ExportStep.PROGRESS -> "导出进度"
}

/**
 * 顶栏标题。**这是页名的唯一来源**：页内不再重复画一遍同名大标题（见 AGENTS §4.4）。
 *
 * 覆盖页优先于 tab，顺序与 [shellPage] 完全一致。
 */
fun StudioUiState.chromeTitle(): String = when (shellPage()) {
    ShellPage.DETAIL -> "书籍详情"
    ShellPage.HISTORY -> "导出记录"
    ShellPage.EXPANDED -> activeExplorePage?.title?.ifBlank { null } ?: "榜单"
    ShellPage.SEARCH -> "搜索"
    ShellPage.BOOKMARKS -> "书签"
    ShellPage.EXPORT -> exportStep?.exportStepTitle() ?: "导出"
    ShellPage.TABS -> when (tab) {
        StudioTab.READING -> "阅读"
        StudioTab.BOOKSHELF -> "我的书架"
        StudioTab.EXPLORE -> "探索"
        StudioTab.SETTINGS -> settingsSection.settingsTitle()
    }
}

/**
 * 返回键（以及顶栏返回图标）该做什么。
 *
 * 与 `com.xyreader.reader.ReaderUiState.resolveBack` 同构：先关最上层的覆盖页，
 * 再退设置二级页，都没有就把返回键交回系统（`NONE`，不拦截）。
 *
 * 导出向导的返还是**逐级后退**而不是一刀关闭：打包页退回选章页（保留已选章节），
 * 其余步骤关闭向导回到发起页。
 */
fun StudioUiState.upTarget(): UpTarget = when (shellPage()) {
    ShellPage.DETAIL -> UpTarget.CLOSE_DETAIL
    ShellPage.HISTORY -> UpTarget.CLOSE_HISTORY
    ShellPage.EXPANDED -> UpTarget.CLOSE_EXPANDED
    ShellPage.SEARCH -> UpTarget.CLOSE_SEARCH
    ShellPage.BOOKMARKS -> UpTarget.CLOSE_BOOKMARKS
    ShellPage.EXPORT -> UpTarget.CLOSE_EXPORT
    ShellPage.TABS ->
        if (tab == StudioTab.SETTINGS && settingsSection != SettingsSection.OVERVIEW) UpTarget.CLOSE_SETTINGS
        else UpTarget.NONE
}
