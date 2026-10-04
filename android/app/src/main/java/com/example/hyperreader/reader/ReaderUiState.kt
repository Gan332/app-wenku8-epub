package com.example.hyperreader.reader

import com.example.hyperreader.settings.ReaderSettings

/**
 * 阅读交互状态。
 *
 * 历史上这里还放着旧渲染层（`ReaderScreenCore`）的 `ReaderActions` 回调接口；
 * 0.13.0 起本地阅读交给 xy-reader，0.14.0 起在线阅读也接入同一阅读器，
 * 旧渲染层与 `ReaderActions` 已无任何调用点，于 0.19.0-alpha02 整体移除。
 *
 * 保留本类型与下面三个扩展函数的原因：它们锁定的行为（菜单栏判据、返回键决策、
 * 面板互斥）由 `CoreSmokeTest` 直接测试，是阅读交互的回归基线，且与渲染实现解耦。
 */
data class ReaderUiState(
    val loading: Boolean = true,
    val book: ReaderBook? = null,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val error: String? = null,
    val showSettings: Boolean = false,
    val showToc: Boolean = false,
    val isImmersive: Boolean = true,
    val controlsVisible: Boolean = false,
    /** 在线模式：正在抓取当前章节正文。 */
    val chapterLoading: Boolean = false,
    /** 在线模式：一次性提示（如「该内容需要登录」）。不打断已渲染的正文。 */
    val notice: String? = null,
)

/** [resolveBack] 的返回值：返回键该做什么。 */
enum class BackAction { CLOSE_SETTINGS, CLOSE_TOC, SHOW_CONTROLS, EXIT }

/**
 * 顶部/底部菜单栏是否可见。
 *
 * 曾经 UI 只看 `isImmersive`、`controlsVisible` 成了只写不读的死状态，
 * 导致点正文呼出菜单栏失效（默认沉浸态下菜单栏永远出不来）。
 * 面板打开时菜单栏也必须在（关掉面板后菜单栏还在）。
 */
fun ReaderUiState.controlsShown(): Boolean = controlsVisible || showSettings || showToc

/**
 * 返回键的决策。纯函数，可单测。
 *
 * 规则与 0.8.x 行为等价：面板打开先关面板；菜单栏可见则退出；
 * 菜单栏隐藏则先呼出——**不修改 `isImmersive` 持久设置**，
 * 也不会出现「隐藏→显示→隐藏」的死循环。
 */
fun ReaderUiState.resolveBack(): BackAction = when {
    showSettings -> BackAction.CLOSE_SETTINGS
    showToc -> BackAction.CLOSE_TOC
    controlsVisible -> BackAction.EXIT
    else -> BackAction.SHOW_CONTROLS
}

/**
 * 打开/关闭目录或设置面板，并保证两个面板**互斥**。
 *
 * 为什么必须互斥：MiuiX 的 `OverlayBottomSheet` 在根 `Scaffold` 的 popup 宿主里各占一个
 * `fillMaxSize` 窗口，而 `DialogEntry` 的 `onDispose` **不会**移除仍在显示态
 * （`showState.value == true`）的条目 —— 关闭动画期间旧窗口依然在命中测试图里。
 * 两个面板同时为真时后开的那层会盖住前一层，关掉一层后遗留的「关闭中」窗口
 * 又会挡住另一层，用户看到的就是「关掉目录再点设置没反应」。
 *
 * 调用点本来就是二选一（按钮打开其中一个、dismiss 关闭其中一个），
 * 因此这里把另一个显式关掉：语义等价，渲染不再打架。
 *
 * 注意：xy-reader 的阅读设置面板同样用 `OverlayBottomSheet`，沿用同一互斥约定。
 */
fun ReaderUiState.withPanel(showSettings: Boolean? = null, showToc: Boolean? = null): ReaderUiState = when {
    showSettings == true -> copy(showSettings = true, showToc = false, controlsVisible = true)
    showToc == true -> copy(showToc = true, showSettings = false, controlsVisible = true)
    showSettings == false -> copy(showSettings = false)
    showToc == false -> copy(showToc = false)
    else -> this
}
