package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.hyperreader.MessageCard
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 探索页（0.18.0 起按 LNR `ExploreHomeScreen` 的信息结构重排，0.19.0 起对齐其排版规格）。
 *
 * 结构：标题区（标题 + 搜索入口）→ 分区（本地书目 / 榜单 / 标签），
 * 每个分区都是「标题行 + 内容」；榜单分区里每行是**横向滑动书卡**，
 * 行尾箭头进入全屏榜单展开页（`ExploreExpandedScreen`，对应 LNR 的 `ExpandedPage`）。
 *
 * 排版对齐上游的四处（数值全部来自 `LnrDimens`）：
 * - 标题区高 64dp（LNR `MediumTopAppBar` 的内容区），48dp 图标槽 + 大标题 + 搜索图标按钮；
 * - 书行标题 48dp 行高 + 行尾箭头图标按钮（LNR 46dp/40dp，这里取触摸下限 48dp）；
 * - 横排书卡封面 98×138dp、封面左右各 4dp、卡间距 4dp、标题固定两行高；
 * - 书行两端渐隐 + 吸附式横滑 + 行尾分割线（LNR `ExploreRowSection`）。
 *
 * 数据来源与合规边界不变：年度/月度榜单走匿名公开页，排行榜与官方标签走用户本人会话
 * （AGENTS §4.2 / §4.5.2）。
 */
@Composable
fun ExploreScreen(
    state: StudioUiState,
    viewModel: StudioViewModel,
    onLogin: () -> Unit,
    onGoToCatalog: () -> Unit,
    onOpenSearch: () -> Unit,
    /**
     * 探索页滚动位置由宿主持有（`StudioApp` 按 tab 缓存），
     * 避免切走 tab 再回来时列表跳回顶部。
     */
    listState: LazyListState,
) {
    // 换页时回到顶部：否则从「今日更新」切到「新书一览」还停在上一个榜单的滚动位置。
    val activePageId = state.activeExplorePage?.id
    LaunchedEffect(activePageId) { listState.scrollToItem(0) }
    // 下拉刷新：对应 LNR ExplorePage 的 PullToRefreshBox。MiuiX 0.9.4 自带 PullToRefresh，
    // 之前 ExploreExpandedScreen 注释说「没有等价组件」是误判。
    PullToRefresh(
        isRefreshing = state.exploreBusy,
        onRefresh = { state.activeExplorePage?.let(viewModel::loadExplore) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            // 左右内边距由宿主给一次（StudioApp 的 Column 已 padding(pagePadding)）。
            // 这里再写一遍的话实际左右各 32dp，与书架页的 16dp 不一致。
            contentPadding = PaddingValues(vertical = UiDimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            item(key = "header") { ExploreTitleRow(onOpenSearch = onOpenSearch) }

            item(key = "catalog") {
                CatalogSectionCard(state = state, onGoToCatalog = onGoToCatalog)
            }

            if (!state.loggedIn) {
                item(key = "login-hint") { LoginHintCard(onLogin = onLogin) }
            }

            // 榜单切换用 TabRow（对应 LNR 的 PrimaryTabRow）。
            // 形状差异：LNR 的 Material TabRow 用「下划线指示器」，MiuiX TabRow 用
            // 「选中项胶囊高亮」且没有指示器插槽，本工程界面统一 MiuiX，故不复刻下划线；
            // 行高取触摸下限 48dp（MiuiX 默认 42dp，LNR 的 Tab 是 48dp）。
            item(key = "page-switcher") {
                val pages = viewModel.explorePages
                val selectedIndex = pages.indexOfFirst { it.id == activePageId }.coerceAtLeast(0)
                // MiuiX TabRow 的项宽是固定值（默认 76dp），装不下「今日更新」「2026 年度精选」
                // 这样的标签就会省略成「今日更…」。按最长标题实测后把 min/max 一起放宽，
                // 项宽仍然统一，只是整行更宽、继续横向滚动。
                val tabWidth = rememberExploreTabWidth(pages)
                TabRow(
                    tabs = pages.map { it.title },
                    selectedTabIndex = selectedIndex,
                    onTabSelected = { pages.getOrNull(it)?.let(viewModel::loadExplore) },
                    modifier = Modifier.fillMaxWidth(),
                    minWidth = tabWidth,
                    maxWidth = tabWidth,
                    height = UiDimens.touchMin,
                )
            }

            if (state.exploreBusy && state.exploreRows.isEmpty()) {
                item(key = "explore-loading") { ExploreRowsSkeleton() }
            }

            items(state.exploreRows, key = { it.title }) { row ->
                val page = row.expandedPageId?.let { id -> viewModel.explorePages.firstOrNull { it.id == id } }
                    ?: state.activeExplorePage
                ExploreRowSection(
                    title = row.title,
                    books = row.books,
                    onOpenBook = { viewModel.openSearchBook(it) },
                    onMore = page?.let { target -> { viewModel.openExploreExpanded(target) } },
                )
            }

            if (!state.exploreBusy && state.exploreRows.isEmpty() && state.catalogSize == 0) {
                item(key = "empty") {
                    EmptyState(
                        title = "还没有可浏览的内容",
                        description = "更新本地书目缓存，或在设置里配置中继后再来。",
                        actionLabel = "前往设置",
                        onAction = onGoToCatalog,
                    )
                }
            }

            // —— 标签浏览（0.14.x 起，官方标签需登录，未登录回落到本地索引）——
            if (state.exploreTags.isNotEmpty()) {
                item(key = "tags") {
                    TagSection(
                        tags = state.exploreTags,
                        activeTag = state.activeTag,
                        onSelect = viewModel::selectTag,
                        onClear = viewModel::clearTag,
                    )
                }
            }

            state.exploreMessage?.let { message -> item(key = "explore-message") { MessageCard(message) } }
        }
    }
}

/** MiuiX `TabItem` 在标签文字左右各留 12dp（库内常量，未公开为 token）。 */
private val EXPLORE_TAB_ITEM_PADDING = 12.dp

/**
 * 榜单切换标签的项宽：按**最长标题**实测，而不是用 MiuiX 的默认固定宽度。
 *
 * 缺陷背景：MiuiX `TabRow` 默认项宽 76dp，且标签只显示一行、超出即省略；
 * 榜单标题里的「今日更新」「热门轻小说」「2026 年度精选」都超过这个宽度，
 * 首屏标签会变成「今日更…」。这里用与 `TabItem` 相同的字号（body1）和
 * 选中态字重（Bold）测量，再补上它内部的左右内边距——系统字体放大时
 * `Density.fontScale` 变化会触发重测，不会再次截断。
 */
@Composable
private fun rememberExploreTabWidth(pages: List<ExplorePage>): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MiuixTheme.textStyles.body1
    return remember(pages, textStyle, density) {
        val longest = pages.maxOfOrNull { page ->
            textMeasurer.measure(
                text = AnnotatedString(page.title),
                style = textStyle.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
            ).size.width
        } ?: 0
        with(density) { longest.toDp() } + EXPLORE_TAB_ITEM_PADDING * 2
    }.coerceAtLeast(TabRowDefaults.TabRowMinWidth)
}

/**
 * 页内大标题区（对应 LNR `ExploreHomeScreen.TopBar` 的展开形态）。
 *
 * 宿主 Scaffold 的 `TopAppBar` 已经是页面标题，这里画的是 LNR `MediumTopAppBar`
 * 那套「48dp 图标槽 + displayLarge 大标题 + 右侧操作」的形状：图标槽与标题同一行，
 * 搜索是图标按钮而非文字按钮。行高取 [LnrDimens.topBarContentHeight]。
 */
@Composable
private fun ExploreTitleRow(onOpenSearch: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 页名由顶栏给（PageChrome，0.19.0-alpha05）：这里不再重复画「探索」大标题与
        // 同义图标槽，只留一行来源说明 + 搜索入口。
        Text(
            "Wenku8 轻小说文库 · 年度/月度榜单无需登录",
            modifier = Modifier.weight(1f),
            fontSize = UiDimens.captionSmall,
            color = secondaryTextColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onOpenSearch, modifier = Modifier.size(UiDimens.touchMin)) {
            Icon(
                imageVector = ImageVector.vectorResource(com.example.hyperreader.R.drawable.search_24px),
                contentDescription = "搜索",
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 未登录提示：榜单与官方标签需要会话，站内搜索同理（AGENTS §4.2）。 */
@Composable
private fun LoginHintCard(onLogin: () -> Unit) {
    // Card 的 insideMargin 已经是内边距，行内不要再加一遍（否则卡片内左右各 32dp）。
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "排行榜、站内搜索与官方标签需要登录",
                modifier = Modifier.weight(1f),
                fontSize = UiDimens.caption,
                color = secondaryTextColor(),
            )
            TextButton(text = "登录", onClick = onLogin, modifier = Modifier.heightIn(min = UiDimens.touchMin))
        }
    }
}

/** 榜单骨架：按真实书卡尺寸出图（封面 98×138 + 卡间距），数据到达时下方内容不跳位。 */
@Composable
private fun ExploreRowsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        ShimmerLine(heightDp = 18)
        Row(horizontalArrangement = Arrangement.spacedBy(LnrDimens.exploreCardGap)) {
            repeat(2) {
                ShimmerBlock(Modifier.size(LnrDimens.exploreCoverWidth, LnrDimens.exploreCoverHeight))
            }
        }
    }
}

/** 本地书目分区：缓存规模、更新时间与入口。 */
@Composable
private fun CatalogSectionCard(state: StudioUiState, onGoToCatalog: () -> Unit) {
    val updated = state.catalogUpdatedAt.takeIf { it > 0 }
        ?.let { "上次更新 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" }
        ?: "尚未更新"
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
        Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
            SectionHeader("本地书目", trailing = { if (state.catalogSize == 0) TextButton(text = "更新缓存", onClick = onGoToCatalog) })
            Text("共 ${state.catalogSize} 本 · $updated", fontSize = UiDimens.body, fontWeight = FontWeight.Bold)
            Text("搜索与浏览基于本地缓存，断网也能用。", fontSize = UiDimens.captionSmall, color = secondaryTextColor())
            state.catalogProgress?.let { (done, total) ->
                Text("正在抓取 $done / $total", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 榜单分区（LNR `ExploreRowSection`）：标题行 + 横向书卡行 + 分割线。
 *
 * 与上游只有两处刻意的差异，都在标题行上：
 * - 行高 48dp（LNR 46dp）、行尾按钮 48dp（LNR 40dp）——AGENTS §4.4 要求按钮不小于
 *   48dp，宁可差 2dp 也不缩小触摸目标；
 * - 行尾是箭头图标按钮（LNR 同款 `arrow_forward_24px`），不再是文字「更多」。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ExploreRowSection(
    title: String,
    books: List<SearchBook>,
    onOpenBook: (SearchBook) -> Unit,
    onMore: (() -> Unit)?,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(UiDimens.touchMin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                fontSize = UiDimens.section,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onMore != null) {
                IconButton(onClick = onMore, modifier = Modifier.size(UiDimens.touchMin)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(com.example.hyperreader.R.drawable.arrow_forward_24px),
                        contentDescription = "查看全部 $title",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (books.isEmpty()) {
            EmptyState(title = "这一栏暂时没有内容")
        } else {
            val rowState = rememberLazyListState()
            LazyRow(
                state = rowState,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalFadingEdge()
                    .padding(bottom = LnrDimens.exploreRowBottomPadding),
                horizontalArrangement = Arrangement.spacedBy(LnrDimens.exploreCardGap),
                // LNR 用 rememberSnapFlingBehavior：横滑一次吸附一张卡，不会停在半张卡上。
                flingBehavior = rememberSnapFlingBehavior(rowState),
            ) {
                items(books, key = { it.id }) { book ->
                    ExploreBookCard(book = book, onClick = { onOpenBook(book) })
                }
            }
        }
        HorizontalDivider(Modifier.fillMaxWidth())
    }
}

/**
 * 横排书卡（LNR `ExploreBookCard`）：封面 98×138dp、卡片宽 106dp（封面左右各 4dp）。
 *
 * 标题固定占两行高度（[LnrDimens.exploreTitleHeightFactor]），同一行里各卡片的作者行
 * 才对得齐；不预留时，只有一个字的书名会把作者行顶到封面正下方，与邻卡错位一行。
 */
@Composable
private fun ExploreBookCard(book: SearchBook, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // LNR 写 `16.sp * 2.2f`（其标题字号就是 16sp）。本项目标题小一档（13sp），
    // 但预留高度仍按 16sp 基准算：MiSans 的行高倍率大于 1.1，用 13sp 基准会裁掉第二行。
    val titleHeight = with(LocalDensity.current) { (UiDimens.section * LnrDimens.exploreTitleHeightFactor).toDp() }
    Column(
        modifier = Modifier
            .width(EXPLORE_CARD_WIDTH)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction),
    ) {
        Box(Modifier.padding(horizontal = LnrDimens.exploreCoverInset)) {
            CoverImage(
                url = book.coverUrl,
                contentDescription = book.title,
                modifier = Modifier
                    .size(LnrDimens.exploreCoverWidth, LnrDimens.exploreCoverHeight)
                    .clip(RoundedCornerShape(LnrDimens.posterCardCorner)),
                targetWidthDp = LnrDimens.exploreCoverWidth.value.toInt(),
            )
        }
        Column(
            modifier = Modifier.width(EXPLORE_CARD_WIDTH).padding(top = UiDimens.spaceS),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXXS),
        ) {
            Text(
                book.title,
                modifier = Modifier.height(titleHeight).wrapContentHeight(Alignment.Top),
                fontSize = UiDimens.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Text(
                    book.author,
                    fontSize = UiDimens.captionSmall,
                    color = tertiaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 横排书卡总宽：封面 + 左右各 [LnrDimens.exploreCoverInset]（LNR 是 106dp）。 */
private val EXPLORE_CARD_WIDTH = LnrDimens.exploreCoverWidth + LnrDimens.exploreCoverInset * 2

/** 标签分区：横向标签行，选中态带勾。 */
@Composable
private fun TagSection(tags: List<String>, activeTag: String?, onSelect: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        SectionHeader("按标签浏览", trailing = if (activeTag != null) ({ TextButton(text = "清除", onClick = onClear) }) else null)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
            items(tags, key = { it }) { tag ->
                TextButton(text = if (tag == activeTag) "✓ $tag" else tag, onClick = { onSelect(tag) })
            }
        }
    }
}

/**
 * 横滑行两端渐隐。
 *
 * 与 LNR `utils/Extentions.kt` 的 `fadingEdge` 同一做法：离屏合成 + `DstIn` 蒙版，
 * 两端用 `Color.White` 的 alpha 做遮罩（颜色本身被 `DstIn` 丢弃，只看 alpha）。
 * 滚动内容超出视口时不是硬切断，而是渐隐，提示「还能继续滑」。
 */
private fun Modifier.horizontalFadingEdge(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0.01f to Color.Transparent,
                0.03f to Color.White,
                0.97f to Color.White,
                0.99f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
