package com.wenku8.epubstudio

import com.wenku8.epubstudio.model.Book
import com.wenku8.epubstudio.model.ExportJob
import com.wenku8.epubstudio.model.JobPhase
import com.wenku8.epubstudio.model.JobProgress
import com.wenku8.epubstudio.model.JobStatus
import com.wenku8.epubstudio.ui.NotificationRoute
import com.wenku8.epubstudio.ui.RouteSnapshot
import com.wenku8.epubstudio.ui.RouteTarget
import com.wenku8.epubstudio.ui.activeExportJobs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知栏点击路由的纯逻辑测试，不依赖 Android 运行时。
 */
class NotifyRoutingTest {
    private fun job(id: String, status: JobStatus = JobStatus.running) = ExportJob(
        id = id,
        status = status,
        book = Book(title = "测试书", sourceUrl = "https://www.wenku8.net/novel/2/2835/index.htm", bookUrl = "https://www.wenku8.net/book/2835.htm"),
        chapterCount = 3,
        progress = JobProgress(phase = JobPhase.fetching, percent = 40, completed = 1, total = 3, imageCompleted = 2, message = "正在下载"),
        createdAt = "2026-09-25T00:00:00Z",
        updatedAt = "2026-09-25T00:01:00Z",
    )

    @Test
    fun resolvesEveryKnownRoute() {
        assertEquals(
            RouteTarget.Progress("job-1"),
            NotificationRoute.resolve("export_progress", "job-1", listOf(job("job-1"))),
        )
        assertEquals(
            RouteTarget.History,
            NotificationRoute.resolve("history", null, listOf(job("job-1"))),
        )
        assertEquals(
            RouteTarget.Bookshelf,
            NotificationRoute.resolve("bookshelf", null, listOf(job("job-1"))),
        )
    }

    @Test
    fun unknownOrMissingRouteHasNoSideEffect() {
        assertNull(NotificationRoute.resolve(null, "job-1", listOf(job("job-1"))))
        assertNull(NotificationRoute.resolve("", "job-1", listOf(job("job-1"))))
        assertNull(NotificationRoute.resolve("   ", "job-1", listOf(job("job-1"))))
        assertNull(NotificationRoute.resolve("teleport", "job-1", listOf(job("job-1"))))
        assertNull(NotificationRoute.resolve("export_progress.v2", "job-1", listOf(job("job-1"))))
    }

    @Test
    fun missingJobIdIsIgnored() {
        assertNull(NotificationRoute.resolve("export_progress", null))
        assertNull(NotificationRoute.resolve("export_progress", ""))
        assertNull(NotificationRoute.resolve("export_progress", "   "))
    }

    @Test
    fun keepsProgressWhenJobListIsNotLoadedYet() {
        assertEquals(RouteTarget.Progress("job-9"), NotificationRoute.resolve("export_progress", "job-9", emptyList()))
    }

    @Test
    fun fallsBackToHistoryWhenJobIsUnknown() {
        val jobs = listOf(job("job-1"), job("job-2", JobStatus.completed))
        assertEquals(RouteTarget.History, NotificationRoute.resolve("export_progress", "job-missing", jobs))
    }

    @Test
    fun repeatedDeliveryIsIdempotent() {
        val jobs = listOf(job("job-1"), job("job-2", JobStatus.completed))
        val initial = RouteSnapshot()

        val progressOnce = NotificationRoute.applyRoute(initial, NotificationRoute.resolve("export_progress", "job-1", jobs))
        val progressTwice = NotificationRoute.applyRoute(progressOnce, NotificationRoute.resolve("export_progress", "job-1", jobs))
        assertEquals(progressOnce, progressTwice)
        assertEquals(RouteTarget.Progress("job-1"), progressTwice.destination)
        assertEquals("job-1", progressTwice.activeJobId)

        val historyOnce = NotificationRoute.applyRoute(initial, NotificationRoute.resolve("history", null, jobs))
        val historyTwice = NotificationRoute.applyRoute(historyOnce, NotificationRoute.resolve("history", null, jobs))
        assertEquals(historyOnce, historyTwice)
        assertTrue(historyTwice.showJobHistory)

        val shelfOnce = NotificationRoute.applyRoute(initial, NotificationRoute.resolve("bookshelf", null, jobs))
        val shelfTwice = NotificationRoute.applyRoute(shelfOnce, NotificationRoute.resolve("bookshelf", null, jobs))
        assertEquals(shelfOnce, shelfTwice)
        assertEquals(false, shelfTwice.showJobHistory)

        // 未知路由对快照没有任何影响。
        assertEquals(shelfTwice, NotificationRoute.applyRoute(shelfTwice, NotificationRoute.resolve("nope", "job-1", jobs)))
    }

    @Test
    fun defaultRouteFollowsJobLifecycle() {
        assertEquals(NotificationRoute.ROUTE_EXPORT_PROGRESS, NotificationRoute.defaultFor(0))
        assertEquals(NotificationRoute.ROUTE_EXPORT_PROGRESS, NotificationRoute.defaultFor(72, JobStatus.running))
        assertEquals(NotificationRoute.ROUTE_EXPORT_PROGRESS, NotificationRoute.defaultFor(0, JobStatus.queued))
        assertEquals(NotificationRoute.ROUTE_JOB_HISTORY, NotificationRoute.defaultFor(100))
        assertEquals(NotificationRoute.ROUTE_JOB_HISTORY, NotificationRoute.defaultFor(100, JobStatus.completed))
        assertEquals(NotificationRoute.ROUTE_JOB_HISTORY, NotificationRoute.defaultFor(40, JobStatus.failed))
        assertEquals(NotificationRoute.ROUTE_JOB_HISTORY, NotificationRoute.defaultFor(40, JobStatus.canceled))
    }

    @Test
    fun requestCodeIsStablePerJobAndDistinctAcrossJobs() {
        assertEquals(NotificationRoute.requestCodeFor("job-1"), NotificationRoute.requestCodeFor("job-1"))
        assertNotEquals(NotificationRoute.requestCodeFor("job-1"), NotificationRoute.requestCodeFor("job-2"))
    }

    @Test
    fun onlyOngoingJobsShowProgressCards() {
        val jobs = listOf(
            job("running", JobStatus.running),
            job("queued", JobStatus.queued),
            job("done", JobStatus.completed),
            job("bad", JobStatus.failed),
            job("gone", JobStatus.canceled),
        )
        assertEquals(listOf("running", "queued"), activeExportJobs(jobs).map { it.id })
        assertTrue(activeExportJobs(emptyList()).isEmpty())
        assertTrue(activeExportJobs(listOf(job("done", JobStatus.completed))).isEmpty())
    }
}
