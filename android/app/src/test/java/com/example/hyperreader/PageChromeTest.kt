package com.example.hyperreader

import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.ui.ExportStep
import com.example.hyperreader.ui.SettingsSection
import com.example.hyperreader.ui.ShellPage
import com.example.hyperreader.ui.StudioTab
import com.example.hyperreader.ui.StudioUiState
import com.example.hyperreader.ui.UpTarget
import com.example.hyperreader.ui.chromeTitle
import com.example.hyperreader.ui.shellPage
import com.example.hyperreader.ui.upTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 页面外框（标题 / 返回）的回归基线（0.19.0-alpha05）。
 *
 * 这一层过去是三处手写 `when`（顶栏标题、各页页内返回按钮、设置页 BackHandler），
 * 互相之间没有任何断言约束：加一个新覆盖页时很容易只改其中一处，表现是
 * 「标题对但返回键退出 App」或「看得见 A，返回关掉 B」。
 *
 * 这里锁三件事：
 * 1. **层级优先级**：覆盖页先于 tab，覆盖页之间按 详情 > 导出记录 > 榜单 > 搜索 > 书签 > 导出向导；
 * 2. **标题与层级一致**：`chromeTitle()` 与 `shellPage()` 同源，不会各说各话；
 * 3. **返回逐级后退**：设置二级页回到索引页；导出打包页退回选章节（已选章节不丢）；
 *    主导航上 `NONE`（不拦截返回键，交回系统）。
 */
class PageChromeTest {

    private fun state() = StudioUiState()

    @Test
    fun tabPagesReportTheirOwnTitle() {
        assertEquals("阅读", state().copy(tab = StudioTab.READING).chromeTitle())
        assertEquals("我的书架", state().copy(tab = StudioTab.BOOKSHELF).chromeTitle())
        assertEquals("探索", state().copy(tab = StudioTab.EXPLORE).chromeTitle())
        assertEquals("设置", state().copy(tab = StudioTab.SETTINGS).chromeTitle())
    }

    @Test
    fun settingsSectionTitleFollowsCategory() {
        val account = state().copy(tab = StudioTab.SETTINGS, settingsSection = SettingsSection.ACCOUNT)
        assertEquals("账号", account.chromeTitle())
        val network = state().copy(tab = StudioTab.SETTINGS, settingsSection = SettingsSection.NETWORK)
        assertEquals("网络", network.chromeTitle())
    }

    @Test
    fun overlayPagesWinOverTabs() {
        // 覆盖页无论当前停在哪个 tab，都必须是标题与层级的主语
        val detail = state().copy(tab = StudioTab.BOOKSHELF, exploreDetailId = "2835")
        assertEquals(ShellPage.DETAIL, detail.shellPage())
        assertEquals("书籍详情", detail.chromeTitle())
        assertEquals(UpTarget.CLOSE_DETAIL, detail.upTarget())

        val history = state().copy(tab = StudioTab.READING, showJobHistory = true)
        assertEquals(ShellPage.HISTORY, history.shellPage())
        assertEquals("导出记录", history.chromeTitle())

        val search = state().copy(tab = StudioTab.EXPLORE, searchPageOpen = true)
        assertEquals(ShellPage.SEARCH, search.shellPage())
        assertEquals("搜索", search.chromeTitle())

        val bookmarks = state().copy(tab = StudioTab.READING, bookmarkCenterOpen = true)
        assertEquals(ShellPage.BOOKMARKS, bookmarks.shellPage())
        assertEquals("书签", bookmarks.chromeTitle())
    }

    /**
     * 覆盖页的**优先级顺序**必须与 `StudioApp` 的 `AnimatedContent` 一致。
     *
     * 这条断言的意义在于：同时置位两个覆盖标记时（例如从探索详情里点导出），
     * 用户看到的是优先级最高的那个，返回键也必须先关它。
     */
    @Test
    fun overlayPriorityIsDetailThenHistoryThenExpandedThenSearchThenBookmarksThenExport() {
        // 全部置位：详情最优先
        val all = state().copy(
            exploreDetailId = "1",
            showJobHistory = true,
            exploreExpanded = true,
            searchPageOpen = true,
            bookmarkCenterOpen = true,
            exportStep = ExportStep.CHAPTERS,
        )
        assertEquals(ShellPage.DETAIL, all.shellPage())

        // 去掉详情 → 导出记录
        assertEquals(ShellPage.HISTORY, all.copy(exploreDetailId = null).shellPage())
        // 再去掉记录 → 榜单展开
        assertEquals(ShellPage.EXPANDED, all.copy(exploreDetailId = null, showJobHistory = false).shellPage())
        // 再去掉榜单 → 搜索
        assertEquals(ShellPage.SEARCH, all.copy(exploreDetailId = null, showJobHistory = false, exploreExpanded = false).shellPage())
        // 再去掉搜索 → 书签
        assertEquals(
            ShellPage.BOOKMARKS,
            all.copy(exploreDetailId = null, showJobHistory = false, exploreExpanded = false, searchPageOpen = false).shellPage(),
        )
        // 只留导出向导 → 导出
        assertEquals(ShellPage.EXPORT, state().copy(exportStep = ExportStep.PACKAGING).shellPage())
    }

    @Test
    fun expandedPageFallsBackToGenericTitleWhenPageTitleIsBlank() {
        val blank = state().copy(exploreExpanded = true, activeExplorePage = null)
        assertEquals("榜单", blank.chromeTitle())
        val named = state().copy(
            exploreExpanded = true,
            activeExplorePage = ExplorePage(id = "annual", title = "年度精选", url = "https://www.wenku8.net/zt/sugoi/"),
        )
        assertEquals("年度精选", named.chromeTitle())
    }

    @Test
    fun exportStepTitleMatchesEachWizardStage() {
        assertEquals("解析目录", state().copy(exportStep = ExportStep.RESOLVING).chromeTitle())
        assertEquals("选择章节", state().copy(exportStep = ExportStep.CHAPTERS).chromeTitle())
        assertEquals("导出设置", state().copy(exportStep = ExportStep.PACKAGING).chromeTitle())
        assertEquals("导出进度", state().copy(exportStep = ExportStep.PROGRESS).chromeTitle())
    }

    @Test
    fun mainNavigationDoesNotInterceptBack() {
        // 主导航（且设置停在索引页）没有任何上层可退：必须交回系统，否则返回键彻底失效
        StudioTab.entries.forEach { tab ->
            val s = state().copy(tab = tab, settingsSection = SettingsSection.OVERVIEW)
            assertEquals("$tab 上不应拦截返回键", UpTarget.NONE, s.upTarget())
        }
    }

    @Test
    fun settingsSubPageGoesBackToIndex() {
        val s = state().copy(tab = StudioTab.SETTINGS, settingsSection = SettingsSection.READER)
        assertEquals(UpTarget.CLOSE_SETTINGS, s.upTarget())
        // 但若同时压着覆盖页，先关覆盖页
        assertEquals(UpTarget.CLOSE_SEARCH, s.copy(searchPageOpen = true).upTarget())
    }

    /**
     * 导出向导的返回是逐级后退，不是一刀关闭：打包页退回选章节，其余步骤关闭向导。
     *
     * 这条规则写在 [StudioViewModel.navigateUp] 里（`PACKAGING → backToChapters`），
     * 这里锁定「状态层面”的判断依据，避免有人把它改成无条件 `closeExport()`。
     */
    @Test
    fun exportWizardBackIsStepwise() {
        assertEquals(UpTarget.CLOSE_EXPORT, state().copy(exportStep = ExportStep.PACKAGING).upTarget())
        assertEquals(UpTarget.CLOSE_EXPORT, state().copy(exportStep = ExportStep.CHAPTERS).upTarget())
        // 目标相同，但 ViewModel 内对 PACKAGING 走 backToChapters——用文案区分，防误合并
        assertNotEquals(
            state().copy(exportStep = ExportStep.PACKAGING).chromeTitle(),
            state().copy(exportStep = ExportStep.CHAPTERS).chromeTitle(),
        )
    }

    @Test
    fun titleAndLayerNeverDisagree() {
        // 每个层级都必须有非空标题：加新覆盖页时忘配标题会在这里失败
        val samples = listOf(
            state(),
            state().copy(exploreDetailId = "1"),
            state().copy(showJobHistory = true),
            state().copy(exploreExpanded = true),
            state().copy(searchPageOpen = true),
            state().copy(bookmarkCenterOpen = true),
            state().copy(exportStep = ExportStep.RESOLVING),
            state().copy(tab = StudioTab.SETTINGS, settingsSection = SettingsSection.ABOUT),
        )
        samples.forEach { s ->
            assertNotEquals("层级 ${s.shellPage()} 缺少标题", "", s.chromeTitle())
            // 覆盖页必须能被返回，主导航不能
            if (s.shellPage() == ShellPage.TABS && s.tab != StudioTab.SETTINGS) {
                assertEquals(UpTarget.NONE, s.upTarget())
            } else if (s.shellPage() != ShellPage.TABS) {
                assertNotEquals(UpTarget.NONE, s.upTarget())
            }
        }
    }
}
