package com.example.hyperreader.reader

import com.example.hyperreader.settings.ReaderPageTurnMode
import com.example.hyperreader.settings.ReaderBackground
import com.example.hyperreader.settings.ReaderSettings

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
 */
fun ReaderUiState.withPanel(showSettings: Boolean? = null, showToc: Boolean? = null): ReaderUiState = when {
    showSettings == true -> copy(showSettings = true, showToc = false, controlsVisible = true)
    showToc == true -> copy(showToc = true, showSettings = false, controlsVisible = true)
    showSettings == false -> copy(showSettings = false)
    showToc == false -> copy(showToc = false)
    else -> this
}

/**
 * 阅读界面用到的**纯 UI 回调**。
 *
 * 在线阅读（`OnlineReaderViewModel`）与本地 EPUB（`com.xyreader.reader.ReaderViewModel`）
 * 各自持有状态，但共用同一份渲染界面（`ReaderScreenCore`），因此把界面所需的回调抽成这个
 * 接口，渲染层只依赖接口而不是具体 ViewModel。
 *
 * 刻意**不包含** `load(uri, id)`（专属装配）与 `startSession()` / `stopSession()`
 * （AndroidViewModel 生命周期职责），也不包含任何网络或缓存逻辑。
 */
interface ReaderActions {
    fun selectChapter(index: Int)
    fun nextChapter()
    fun previousChapter()
    fun setParagraph(index: Int)
    fun toggleControls()
    fun setImmersive(value: Boolean)
    fun showSettings(show: Boolean)
    fun showToc(show: Boolean)
    fun closeOverlays()
    fun updateFontSize(value: Float)
    fun updateFontWeight(value: Int)
    fun updateLineHeight(value: Float)
    fun updateSpacing(value: Int)
    fun updatePadding(value: Int)
    fun updateBackground(value: ReaderBackground)
    fun updateBackgroundColor(value: Int)
    fun updateTextColor(value: Int)
    fun updatePageMode(value: ReaderPageTurnMode)
    fun updateKeepScreenOn(value: Boolean)
    fun updateImmersive(value: Boolean)
    fun updateFontUri(value: String?)
}
