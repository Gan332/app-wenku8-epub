package com.example.hyperreader

import com.example.hyperreader.core.ExploreBookSeed
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.BookIndex
import com.example.hyperreader.model.Chapter
import com.example.hyperreader.ui.ExportStep
import com.example.hyperreader.ui.ExportWizard
import com.example.hyperreader.ui.StudioTab
import com.example.hyperreader.ui.StudioUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读优先的信息架构（0.17.0）的状态机夹具。
 *
 * 断言三件事：
 * 1. 底部导航只剩书架/探索/设置，导出向导退成覆盖层（四态，无「源站/详情」）；
 * 2. 发起导出必须清掉上一轮的 book/index/选中章节，避免误打包；
 * 3. 书架书的详情复用探索详情通路，且**不碰**导出向导状态（AGENTS §4.6 链路隔离）。
 */
class ExportWizardTest {

    private val book = Book(
        id = "2835",
        title = "测试书",
        author = "测试作者",
        sourceUrl = Wenku8Urls.book("2835"),
        bookUrl = Wenku8Urls.book("2835"),
    )

    private val index = BookIndex(
        title = "测试书",
        url = Wenku8Urls.index("2835"),
        bookId = "2835",
        chapters = listOf(
            Chapter("1", "第一章", Wenku8Urls.index("2835") + "#1", "正文", 1),
            Chapter("2", "第二章", Wenku8Urls.index("2835") + "#2", "正文", 2),
        ),
    )

    @Test
    fun navigationHasNoCreateTabAndWizardHasFourSteps() {
        assertEquals(listOf(StudioTab.BOOKSHELF, StudioTab.EXPLORE, StudioTab.SETTINGS), StudioTab.entries.toList())
        assertEquals(
            listOf(ExportStep.RESOLVING, ExportStep.CHAPTERS, ExportStep.PACKAGING, ExportStep.PROGRESS),
            ExportStep.entries.toList(),
        )
    }

    @Test
    fun beginEntersResolvingAndDropsLeftovers() {
        val dirty = StudioUiState(
            book = book,
            index = index,
            selectedIds = setOf("1", "2"),
            exportStep = ExportStep.PACKAGING,
            busy = true,
            detailError = "旧错误",
            message = "旧提示",
            tab = StudioTab.EXPLORE,
        )
        val started = ExportWizard.begin(dirty, "https://www.wenku8.net/book/2835.htm")

        assertEquals(ExportStep.RESOLVING, started.exportStep)
        assertEquals(StudioTab.BOOKSHELF, started.tab)
        assertEquals("https://www.wenku8.net/book/2835.htm", started.sourceUrl)
        assertNull(started.book)
        assertNull(started.index)
        assertTrue(started.selectedIds.isEmpty())
        assertFalse(started.busy)
        assertNull(started.detailError)
        assertNull(started.message)
    }

    @Test
    fun resolveSuccessSelectsAllAndMovesToChapters() {
        val resolving = ExportWizard.begin(StudioUiState(), "https://www.wenku8.net/book/2835.htm").copy(busy = true)
        val chapters = ExportWizard.resolved(resolving, book, index)

        assertEquals(ExportStep.CHAPTERS, chapters.exportStep)
        assertEquals(setOf("1", "2"), chapters.selectedIds)
        assertEquals(book, chapters.book)
        assertEquals(2, chapters.index?.chapters?.size)
        assertFalse(chapters.busy)
        assertNull(chapters.detailError)
    }

    @Test
    fun resolveFailureStaysResolvingWithRetryableError() {
        val resolving = ExportWizard.begin(StudioUiState(), "https://www.wenku8.net/book/2835.htm").copy(busy = true)
        val failed = ExportWizard.failed(resolving, "源站返回 HTTP 403。")

        assertEquals(ExportStep.RESOLVING, failed.exportStep)
        assertEquals("源站返回 HTTP 403。", failed.detailError)
        assertFalse(failed.busy)
        assertNull(failed.index)
    }

    @Test
    fun closeLeavesWizardButKeepsParsedBookForNextRound() {
        val chapters = ExportWizard.resolved(StudioUiState(), book, index)
        val closed = ExportWizard.close(chapters.copy(busy = true, detailError = "x"))

        assertNull(closed.exportStep)
        assertFalse(closed.busy)
        assertNull(closed.detailError)
        // 书籍与目录留在状态里供展示，但下一次 begin 会清空
        assertEquals(book, closed.book)
    }

    @Test
    fun shelfDetailReusesExplorePathWithoutTouchingExportState() {
        val chapters = ExportWizard.resolved(StudioUiState(), book, index)
        val seed = ExploreBookSeed(
            id = "2835",
            title = "测试书",
            author = "测试作者",
            sourceUrl = Wenku8Urls.book("2835"),
        )
        val detail = ExportWizard.openShelfDetail(chapters, seed)

        assertEquals("2835", detail.exploreDetailId)
        assertTrue(detail.exploreDetailLoading)
        assertNull(detail.exploreDetail)
        assertNull(detail.exploreDetailError)
        assertEquals(seed, detail.exploreDetailSeed)
        // 链路隔离：导出向导与 book/index 原封不动
        assertEquals(ExportStep.CHAPTERS, detail.exportStep)
        assertEquals(book, detail.book)
        assertEquals(setOf("1", "2"), detail.selectedIds)
    }
}