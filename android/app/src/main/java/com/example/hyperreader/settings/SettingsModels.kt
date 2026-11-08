package com.example.hyperreader.settings

import kotlinx.serialization.Serializable

enum class AppThemeMode { SYSTEM, LIGHT, DARK, MONET }

enum class ReaderBackground { PAPER, LIGHT, GREEN, DARK, OLED, CUSTOM }

enum class ReaderPageTurnMode { HORIZONTAL, VERTICAL }

@Serializable
data class AppThemeSettings(
    val mode: AppThemeMode = AppThemeMode.MONET,
    val useDynamicColor: Boolean = true,
    val accentColor: Int = 0xFFA34B2F.toInt(),
)

@Serializable
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.7f,
    val paragraphSpacingDp: Int = 12,
    val horizontalPaddingDp: Int = 20,
    val background: ReaderBackground = ReaderBackground.PAPER,
    val customBackgroundColor: Int = 0xFFF4EFE6.toInt(),
    val textColor: Int = 0xFF272522.toInt(),
    val pageTurnMode: ReaderPageTurnMode = ReaderPageTurnMode.HORIZONTAL,
    val keepScreenOn: Boolean = true,
    val immersiveMode: Boolean = true,
    val fontUri: String? = null,
) {
    companion object {
        const val MIN_FONT_SIZE = 12f
        const val MAX_FONT_SIZE = 32f
        const val MIN_LINE_HEIGHT = 1.2f
        const val MAX_LINE_HEIGHT = 2.6f
    }
}

@Serializable
data class ReadingProgress(
    val bookId: String,
    val chapterId: String,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val updatedAt: Long = 0L,
)

/**
 * 书架排序方式（0.19.0-alpha03 起可在书架页切换）。
 *
 * 原先排序**硬编码**在 `BookshelfRepository.entries` 里（置顶 → 最近阅读 → 标题），
 * 用户无从选择。这里把它提成可选设置，参考 LNR 的 `BookshelfSortType`
 * （`default` / `latest` / `name` / `word_count`）。
 *
 * 与 LNR 的差异：上游「Latest」指**最新章节更新时间**，本工程 `BookshelfEntry`
 * 没有该字段（见 `ui/BookshelfGroup` 的说明：不为视觉差异引入无用数据）；
 * 因此 [RecentRead] 用既有的 `lastReadAt` 回落 `addedAt`，语义是「最近读/加入」。
 *
 * 排序**不改动分组**：置顶永远在最前，两段结构不变，排序只在各段内部生效。
 * 排列逻辑在 `ui/sortBookshelf`（纯函数）。
 */
@Serializable
enum class BookshelfSort(
    val label: String,
    val summary: String,
) {
    /** 最近阅读优先；从未读过的按加入时间。默认值，与 0.19.0 之前的行为一致。 */
    RecentRead("最近阅读", "读过的排在前面，未读按加入时间"),

    /** 最近加入优先。 */
    RecentlyAdded("最近加入", "新加入的书排在前面"),

    /** 书名升序。 */
    Title("书名", "按书名升序排列"),

    /** 字数多的优先；未知字数（本地书未解析）排在最后。 */
    WordCount("字数", "字数多的排在前面，未知字数排最后"),
    ;

    companion object {
        fun fromName(raw: String?): BookshelfSort = entries.firstOrNull { it.name == raw } ?: RecentRead
    }
}

/**
 * EPUB 导出引擎（0.14.0 起可在设置里切换）。
 *
 * 两个引擎产出同样的 EPUB 3.3 结构、调用契约一致，区别见各自实现的 KDoc：
 * - [CLASSIC]：自研 `epub/EpubBuilder`，保留已 sanitize 的行内强调标签
 * - [POTATO]：LightNovelReader 的 `:epub` 模块（`io.nightfish.potatoepub`，Apache-2.0），
 *   正文按段落纯文本写入
 */
@kotlinx.serialization.Serializable
enum class EpubEngine(val label: String, val summary: String) {
    CLASSIC("自研引擎", "保留行内强调，默认"),
    POTATO("LNR 引擎", "段落纯文本，结构更规范"),
    ;

    companion object {
        fun fromName(raw: String?): EpubEngine =
            entries.firstOrNull { it.name == raw } ?: CLASSIC
    }
}

/**
 * 首选直连入口（0.19.0，设置 → 网络）。
 *
 * 规范 URL 永远是 `www.wenku8.net`（书架、EPUB 内链、缓存键都以它为准）；
 * 这里只决定**第一次**请求打哪个镜像边缘，被 Cloudflare 拦截时仍按候选顺序轮换。
 *
 * 起因：镜像的可用性随网络与时间波动（社区实现实测 `.net` 更容易被拦，
 * 见 TakaHoshino/Wenku8Reader 的默认镜像选择），把起始入口交给用户比写死一个更稳。
 */
@Serializable
enum class DirectEntry(val label: String, val base: String) {
    NET("wenku8.net", "https://www.wenku8.net"),
    CC("wenku8.cc", "https://www.wenku8.cc"),
    ;

    companion object {
        fun fromName(raw: String?): DirectEntry = entries.firstOrNull { it.name == raw } ?: NET
    }
}

/**
 * 在线阅读数据源（设置 → 阅读器设置）。
 *
 * - [BUILTIN]：本工程的目录/正文解析链路（[com.example.hyperreader.reader.OnlineReaderSource]）
 * - [LNR]：LightNovelReader 书源契约（`Wenku8BookSource`）加一层阅读器适配
 *
 * 两条链路都只用 AGENTS §4.2 的匿名公开端点，并共享同一份限流客户端。
 */
@Serializable
enum class OnlineSource(val label: String, val summary: String) {
    BUILTIN("内置解析", "目录与正文由本工程解析，默认"),
    LNR("LNR 书源", "走 LightNovelReader 书源契约取目录与正文"),
    ;

    companion object {
        fun fromName(raw: String?): OnlineSource = entries.firstOrNull { it.name == raw } ?: BUILTIN
    }
}

/**
 * 屏幕刷新率策略（设置 → 阅读器设置 → 阅读显示）。
 *
 * 只影响本 App 窗口（`Window.attributes.preferredRefreshRate`），不修改系统设置；
 * 设备不支持目标帧率时由系统钳到最接近的模式。
 *
 * [ADAPTIVE] 不指定帧率，交给系统按内容/触控调度——这是 Android 默认行为，也最省电。
 */
@Serializable
enum class RefreshRate(val label: String, val summary: String, val hz: Float?) {
    ADAPTIVE("自适应", "由系统按内容调度，最省电（推荐）", null),
    HZ_60("60 Hz", "固定 60 帧，续航优先", 60f),
    HZ_90("90 Hz", "固定 90 帧，流畅与耗电均衡", 90f),
    HZ_120("120 Hz", "固定 120 帧，翻页最顺滑但更耗电", 120f),
    ;

    companion object {
        fun fromName(raw: String?): RefreshRate = entries.firstOrNull { it.name == raw } ?: ADAPTIVE
    }
}
