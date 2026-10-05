package com.example.hyperreader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hyperreader.core.ExploreBookDetail
import com.example.hyperreader.core.ExploreBookSeed
import com.example.hyperreader.ui.cover.CoverImage
import com.example.hyperreader.ui.cover.CoverViewerDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 元数据行最小高度，符合 48dp 触摸目标约定。 */

/**
 * 探索页书籍详情：**全屏独立页面**，与创建导出流程彻底分开。
 *
 * 数据来自 [ExploreBookDetail]（只调公开 API，见 `ExploreDetailRepository`）。
 * 页面自己不发起任何请求：加载/重试都走 [StudioViewModel]。
 *
 * @param detail 接口返回的完整数据；为 null 时用 [seed] 先渲染标题/作者等已知字段。
 * @param seed 进入页面时已有的信息（点击来源的榜单/索引条目）。
 */
@Composable
fun ExploreDetailScreen(
    seed: ExploreBookSeed?,
    detail: ExploreBookDetail?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddToShelf: () -> Unit,
    onReadOnline: () -> Unit,
    onSameAuthor: () -> Unit,
    onTagClick: (String) -> Unit,
    /** 点目录里的某一章直接在线读这一章（0.18.0）。 */
    onReadChapter: (com.example.hyperreader.model.Chapter) -> Unit = {},
    /** 直接用指定引擎导出 EPUB（两个小按钮各对应一个引擎）。 */
    onExport: (com.example.hyperreader.settings.EpubEngine) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val book = detail?.book
    val title = book?.title ?: seed?.title.orEmpty()
    val author = book?.author ?: seed?.author.orEmpty()
    val category = book?.category ?: seed?.category.orEmpty()
    val coverUrl = book?.coverUrl ?: seed?.coverUrl
    val summary = book?.summary.orEmpty()
    val tags = book?.tags.orEmpty()
    val status = (book?.status ?: seed?.status.orEmpty()).ifBlank { if (book?.isComplete == true) "完结" else "" }
    val wordCount = book?.wordCount ?: seed?.wordCount
    val updatedAt = book?.updatedAt ?: seed?.updatedAt.orEmpty()
    val bookId = book?.id ?: seed?.id.orEmpty()

    var showCover by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = UiDimens.pagePadding, end = UiDimens.pagePadding, top = UiDimens.spaceS, bottom = UiDimens.spaceXL),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
    ) {
        item(key = "back") {
            TextButton(text = "‹ 返回探索", onClick = onBack)
        }

        // 加载中且还没有任何数据：只渲染 loading 卡片，避免闪一屏空卡片。
        // 用条件分支包住后续 item，而不是 `return@LazyColumn` —— 后者的标签
        // 指向 LazyListScope 的非内联 lambda，写起来脆且不利于阅读。
        val emptyLoading = loading && detail == null && seed == null
        if (emptyLoading) {
            item(key = "loading-full") {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    ShimmerLine(heightDp = 22)
                    ShimmerLine(heightDp = 16)
                    ShimmerLine(heightDp = 120)
                    Text("正在获取书籍信息…", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .75f))
                }
            }
        }

        if (!emptyLoading) {
            item(key = "cover") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .width(UiDimens.coverWidth)
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(UiDimens.cardCorner))
                            .clickable(enabled = coverUrl != null) { showCover = true },
                    ) {
                        CoverImage(
                            url = coverUrl,
                            contentDescription = title,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.75f),
                            targetWidthDp = 480,
                        )
                    }
                }
            }

            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                    Text(title.ifBlank { "未命名" }, fontSize = UiDimens.title, fontWeight = FontWeight.Bold)
                    if (author.isNotBlank()) Text("作者：$author", fontSize = UiDimens.bodyStrong)
                    if (category.isNotBlank()) {
                        Text(category, color = MiuixTheme.colorScheme.primary, fontSize = UiDimens.captionSmall, fontWeight = FontWeight.Bold)
                    }
                    // 接口回来后给一个「已是最新」的轻提示；加载中显示进度，不阻塞已渲染的内容
                    if (loading) {
                        LoadingBlock("正在通过接口刷新详情…")
                    }
                }
            }

            // 书籍信息全部来自 articleinfo.php 一个接口；0.18.0 起**每个字段一张独立 Card**，
            // 避免一屏信息挤在一张大卡里难以扫读
            if (status.isNotBlank()) item(key = "field-status") { FieldCard("状态", status, index = 0) }
            wordCount?.let { count ->
                item(key = "field-word-count") { FieldCard("全文字数", "${formatWordCount(count)} 字", index = 1) }
            }
            if (updatedAt.isNotBlank()) item(key = "field-updated") { FieldCard("最后更新", updatedAt, index = 2) }
            item(key = "field-chapter-count") {
                FieldCard(
                    label = "章节数",
                    value = when {
                        detail != null && detail.chapterCount > 0 -> "${detail.chapterCount}"
                        loading -> "获取中…"
                        else -> "未知"
                    },
                    index = 3,
                )
            }
            item(key = "field-source") { FieldCard("来源", "Wenku8 轻小说文库", index = 4) }

            // 目录页单独失败：书籍信息仍可用，就地提示 + 重试，不整页报错
            if (error != null) {
                item(key = "error") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                            Text("部分信息加载失败", fontSize = UiDimens.bodyStrong, fontWeight = FontWeight.Bold)
                            Text(error, fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.error)
                            TextButton(
                                text = "重试",
                                onClick = onRetry,
                                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                            )
                        }
                    }
                }
            }

            if (tags.isNotEmpty()) {
                item(key = "tags") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                            Text("标签", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                                items(tags) { tag -> TextButton(text = tag, onClick = { onTagClick(tag) }) }
                            }
                        }
                    }
                }
            }

            item(key = "summary") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                        Text("内容简介", fontWeight = FontWeight.Bold)
                        Text(
                            text = summary.ifBlank { if (loading) "正在获取简介…" else "源站没有提供简介。" },
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            fontSize = UiDimens.body,
                        )
                    }
                }
            }

            // —— 章节目录（0.18.0）：默认预览前20 章，可搜索/展开，点章直接在线读 ——
            item(key = "toc") {
                ChapterTocCard(
                    chapters = detail?.chapters.orEmpty(),
                    loading = loading && detail == null,
                    onReadChapter = onReadChapter,
                )
            }

            item(key = "actions") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                        if (detail != null || !loading) {
                            Button(
                                onClick = onReadOnline,
                                enabled = bookId.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                            ) { Text("在线阅读") }
                            TextButton(
                                text = "加入书架",
                                onClick = onAddToShelf,
                                enabled = detail != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                            )
                            TextButton(
                                text = "查看同作者作品",
                                onClick = onSameAuthor,
                                enabled = detail != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                            )
                        }
                        // —— 两种 EPUB 导出引擎（小按钮，并排）——
                        // 直接用本页已加载的目录建任务，不经过创建流程的选章步骤；
                        // 引擎按任务指定，不受设置里的全局选择影响。
                        if (detail != null && detail.chapters.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                            ) {
                                com.example.hyperreader.settings.EpubEngine.entries.forEach { option ->
                                    TextButton(
                                        text = "导出 · ${option.label}",
                                        onClick = { onExport(option) },
                                        modifier = Modifier.weight(1f).heightIn(min = UiDimens.touchMin),
                                    )
                                }
                            }
                        }
                        Text(
                            "本页面只读取源站公开信息，不经过导出解析流程。",
                            fontSize = UiDimens.captionSmall,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f),
                        )
                    }
                }
            }
        }
    }

    if (showCover && coverUrl != null) {
        CoverViewerDialog(url = coverUrl, title = title, onDismiss = { showCover = false })
    }
}

/**
 * 单个元数据字段的独立卡片（0.18.0）。
 *
 * 字段级隔离：每张卡只承载一个「标签 + 值」，行高不低于 [UiDimens.rowMin]，便于扫读。
 */
@Composable
internal fun FieldCard(label: String, value: String, index: Int = 0) {
    Card(Modifier.fillMaxWidth().staggeredEnter(index), insideMargin = PaddingValues(vertical = UiDimens.spaceXXS)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = UiDimens.rowMin)
                .padding(horizontal = UiDimens.cardInset, vertical = UiDimens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f), fontSize = UiDimens.body)
            Text(
                value,
                modifier = Modifier.weight(1f).padding(start = UiDimens.spaceM),
                fontSize = UiDimens.bodyStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
        }
    }
}

/**
 * 章节目录卡片（0.18.0）。
 *
 * - 折叠时只渲染前 [ChapterPreview.PREVIEW_LIMIT] 章，展开后限高滚动，避免千章一次性构建；
 * - 搜索按章节标题与卷名过滤（[ChapterPreview] 是纯逻辑，便于单测）；
 * - 点某章直接在线读该章（经 [onReadChapter] 交给宿主的在线阅读器）。
 */
@Composable
private fun ChapterTocCard(
    chapters: List<com.example.hyperreader.model.Chapter>,
    loading: Boolean,
    onReadChapter: (com.example.hyperreader.model.Chapter) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val visible = remember(chapters, query, expanded) { ChapterPreview.preview(chapters, query, expanded) }
    val filteredCount = remember(chapters, query) { ChapterPreview.filter(chapters, query).size }
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
            Text("章节目录（$filteredCount）", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
            when {
                chapters.isEmpty() -> Text(
                    if (loading) "正在获取目录…" else "目录尚未就绪。",
                    fontSize = UiDimens.body,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                )
                else -> {
                    if (chapters.size > ChapterPreview.PREVIEW_LIMIT) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            label = "搜索章节",
                            useLabelAsPlaceholder = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // 展开/收起动画：AnimatedVisibility 常驻（visible 随展开态变化），
                    // 不能包在 `if (expanded)` 里 —— 那样进入时已是 true，动画不会触发
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(tween(Motion.duration(UiDimens.MOTION_SLOW), easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeIn(tween(Motion.duration())),
                        exit = shrinkVertically(tween(Motion.duration(UiDimens.MOTION_MEDIUM))) + fadeOut(tween(Motion.duration(UiDimens.MOTION_FAST))),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = ChapterPreview.EXPANDED_MAX_HEIGHT_DP.dp),
                            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS),
                        ) {
                            items(visible, key = { it.id }) { chapter ->
                                ChapterTocRow(chapter) { onReadChapter(chapter) }
                            }
                        }
                    }
                    if (!expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS)) {
                            visible.forEach { chapter ->
                                ChapterTocRow(chapter) { onReadChapter(chapter) }
                            }
                        }
                    }
                    Text(
                        text = when {
                            filteredCount == 0 -> "没有匹配的章节。"
                            expanded -> "已展开全部 $filteredCount 章。"
                            else -> "仅显示前 ${visible.size} / $filteredCount 章。"
                        },
                        fontSize = UiDimens.captionSmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                    )
                    if (ChapterPreview.canExpand(chapters, query, expanded)) {
                        TextButton(
                            text = if (expanded) "收起" else "展开全部 $filteredCount 章",
                            onClick = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
                        )
                    }
                }
            }
        }
    }
}

/** 目录中的一行：卷名 + 章节名，整行可点（不低于 [UiDimens.rowMin]）。 */
@Composable
private fun ChapterTocRow(chapter: com.example.hyperreader.model.Chapter, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = UiDimens.rowMin)
            .clickable(onClick = onClick)
            .padding(horizontal = UiDimens.spaceS, vertical = UiDimens.spaceXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chapter.volume, fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
        Text(
            text = chapter.title,
            modifier = Modifier.weight(1f).padding(start = UiDimens.spaceS),
            fontSize = UiDimens.body,
            maxLines = 1,
        )
        if (chapter.isIllustration) Text("插图", fontSize = UiDimens.badge, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
    }
}
