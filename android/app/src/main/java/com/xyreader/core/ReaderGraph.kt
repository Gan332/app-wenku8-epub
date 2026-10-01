package com.xyreader.core

/**
 * 阅读器的依赖注入点（对应上游的 `com.xyreader.data.AppGraph`）。
 *
 * 上游用 Room + 全局单例 `LibraryRepository`；本工程的数据层是既有 DataStore 仓储，
 * 因此这里只保留**一个可安装的窄接口** [ReaderRepository]：由
 * `com.example.hyperreader` 侧在进程启动时安装实现，`com.xyreader` 侧只读不写，
 * 从而不让移植进来的阅读器反向依赖宿主包名。
 */
object ReaderGraph {

    @Volatile
    private var repository: ReaderRepository? = null

    /** 安装实现（幂等；重复安装以最后一次为准，便于阅读会话按书重建）。 */
    fun install(repository: ReaderRepository) {
        this.repository = repository
    }

    /**
     * 取当前仓库。未安装即抛错——这是装配错误，静默兜底只会把问题推迟到更难定位的地方
     * （表现为阅读器打不开书），因此在读取点直接失败并给出可执行的修复提示。
     */
    fun readerRepository(): ReaderRepository =
        repository ?: error(
            "ReaderRepository 尚未安装：请在 Application.onCreate 中调用 ReaderGraph.install(...)",
        )
}
