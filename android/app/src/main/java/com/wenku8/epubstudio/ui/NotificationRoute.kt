package com.wenku8.epubstudio.ui

import com.wenku8.epubstudio.model.ExportJob
import com.wenku8.epubstudio.model.JobStatus

/**
 * 通知栏点击后要打开的目标。纯数据，不依赖 Android 运行时，方便单元测试。
 */
sealed interface RouteTarget {
    /** 打开某个导出任务的进度界面。 */
    data class Progress(val jobId: String) : RouteTarget

    /** 打开书架的「导出记录」。 */
    data object History : RouteTarget

    /** 打开书架首页。 */
    data object Bookshelf : RouteTarget
}

/**
 * 路由决策的最小快照。真实状态由 [StudioUiState] 承载，
 * 这里只保留 [applyRoute] 需要的字段，用于验证重复投递的幂等性。
 */
data class RouteSnapshot(
    val destination: RouteTarget? = null,
    val activeJobId: String? = null,
    val showJobHistory: Boolean = false,
)

/**
 * 通知栏路由的纯函数实现。
 *
 * 规则：
 * - 路由为空、未知或缺少 jobId 时返回 `null`，调用方应完全忽略，不产生任何副作用；
 * - `export_progress` 指向的任务在已知任务列表里不存在时降级为导出记录；
 * - [applyRoute] 是幂等的：同一路由重复投递得到同样的快照。
 */
object NotificationRoute {
    const val ROUTE_EXPORT_PROGRESS = StudioViewModel.ROUTE_EXPORT_PROGRESS
    const val ROUTE_JOB_HISTORY = StudioViewModel.ROUTE_JOB_HISTORY
    const val ROUTE_BOOKSHELF = StudioViewModel.ROUTE_BOOKSHELF

    /**
     * @param jobs 已知的任务列表。为空表示列表尚未加载完，此时不做存在性校验。
     */
    fun resolve(route: String?, jobId: String?, jobs: List<ExportJob> = emptyList()): RouteTarget? {
        val id = jobId?.trim().orEmpty()
        return when (route?.trim()) {
            ROUTE_EXPORT_PROGRESS -> {
                if (id.isEmpty()) {
                    null
                } else if (jobs.isNotEmpty() && jobs.none { it.id == id }) {
                    RouteTarget.History
                } else {
                    RouteTarget.Progress(id)
                }
            }
            ROUTE_JOB_HISTORY -> RouteTarget.History
            ROUTE_BOOKSHELF -> RouteTarget.Bookshelf
            else -> null
        }
    }

    /**
     * 通知没有显式携带路由时的默认决策：进行中进该任务，已结束进导出记录。
     */
    fun defaultFor(percent: Int, status: JobStatus? = null): String = when {
        status != null && !status.isOngoing() -> ROUTE_JOB_HISTORY
        percent >= 100 -> ROUTE_JOB_HISTORY
        else -> ROUTE_EXPORT_PROGRESS
    }

    /** 幂等地把一次路由投递应用到快照上。 */
    fun applyRoute(snapshot: RouteSnapshot, target: RouteTarget?): RouteSnapshot = when (target) {
        null -> snapshot
        is RouteTarget.Progress -> RouteSnapshot(RouteTarget.Progress(target.jobId), target.jobId, false)
        RouteTarget.History -> RouteSnapshot(RouteTarget.History, snapshot.activeJobId, true)
        RouteTarget.Bookshelf -> RouteSnapshot(RouteTarget.Bookshelf, snapshot.activeJobId, false)
    }

    /**
     * PendingIntent 的 requestCode。按 jobId 派生，保证同一任务稳定、不同任务互不覆盖。
     */
    fun requestCodeFor(jobId: String): Int = jobId.hashCode()
}

/** 只有排队中和运行中的任务算「进行中」。 */
fun JobStatus.isOngoing(): Boolean = this == JobStatus.queued || this == JobStatus.running

/** 书架首页需要显示进度卡片的进行中任务。 */
fun activeExportJobs(jobs: List<ExportJob>): List<ExportJob> = jobs.filter { it.status.isOngoing() }
