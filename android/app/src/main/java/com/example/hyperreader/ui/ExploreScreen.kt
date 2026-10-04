package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hyperreader.MessageCard
import com.example.hyperreader.core.ExploreBooksRow
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 探索页（0.18.0 起按 LNR `ExploreHomeScreen` 的信息结构重排）。
 *
 * 结构：标题区（标题 + 搜索入口）→ 分区（本地书目 / 榜单 / 标签），
 * 每个分区都是「标题行 + 内容」；榜单分区里每行是**横向滑动书卡**，
 * 行尾「更多」进入全屏榜单展开页（`ExploreExpandedScreen`，对应 LNR 的 `ExpandedPage`）。
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
) {
    val listState = rememberLazyListState()
    // 换页时回到顶部：否则从「今日更新」切到「新书一览」还停在上一个榜单的滚动位置。
    val activePageId = state.activeExplorePage?.id
    androidx.compose.runtime.LaunchedEffect(activePageId) { listState.scrollToItem(0) }
    // 下拉刷新：对应 LNR ExplorePage 的 PullToRefreshBox。MiuiX 0.9.4 自带 PullToRefresh，
    // 之前 ExploreExpandedScreen 注释说「没有等价组件」是误判。
    PullToRefresh(
        isRefreshing = state.exploreBusy,
        onRefresh = { state.activeExplorePage?.let(viewModel::loadExplore) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = UiDimens.pagePadding, vertical = UiDimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
        ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                Text("探索", fontSize = UiDimens.display, fontWeight = FontWeight.Bold)
                Text(
                    "Wenku8 轻小说文库 · 年度/月度榜单无需登录",
                    fontSize = UiDimens.caption,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                )
                TextButton(text = "搜索书名 / 作者", onClick = onOpenSearch, modifier = Modifier.heightIn(min = UiDimens.touchMin))
            }
        }

        item(key = "catalog") {
            CatalogSectionCard(state = state, onGoToCatalog = onGoToCatalog)
        }

        if (!state.loggedIn) {
            item(key = "login-hint") {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(UiDimens.cardInset),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "排行榜、站内搜索与官方标签需要登录",
                            modifier = Modifier.weight(1f),
                            fontSize = UiDimens.caption,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
                        )
                        TextButton(text = "登录", onClick = onLogin, modifier = Modifier.heightIn(min = UiDimens.touchMin))
                    }
                }
            }
        }

        // 榜单切换用 TabRow（对应 LNR 的 PrimaryTabRow），12 个榜单挤在横向 Chip 行里不好点。
        item(key = "page-switcher") {
            val pages = viewModel.explorePages
            val selectedIndex = pages.indexOfFirst { it.id == activePageId }.coerceAtLeast(0)
            TabRow(
                tabs = pages.map { it.title },
                selectedTabIndex = selectedIndex,
                onTabSelected = { pages.getOrNull(it)?.let(viewModel::loadExplore) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.exploreBusy && state.exploreRows.isEmpty()) {
            item(key = "explore-loading") {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                    ShimmerLine(heightDp = 18)
                    ShimmerLine(heightDp = 132)
                    ShimmerLine(heightDp = 18)
                }
            }
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
                ExploreEmptyState(
                    title = "还没有可浏览的内容",
                    description = "更新本地书目缓存，或在设置里配置中继后再来。",
                    actionText = "前往设置",
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
            Text("搜索与浏览基于本地缓存，断网也能用。", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            state.catalogProgress?.let { (done, total) ->
                Text("正在抓取 $done / $total", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

/** 榜单分区：标题行（含「更多」）+ 横向书卡行。 */
@Composable
private fun ExploreRowSection(
    title: String,
    books: List<SearchBook>,
    onOpenBook: (SearchBook) -> Unit,
    onMore: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
        // trailing 有明确的 `@Composable` 目标类型，所以这里用 if 而不是 let 包一层 lambda
        SectionHeader(
            title = title,
            trailing = if (onMore != null) ({ TextButton(text = "更多", onClick = onMore) }) else null,
        )
        if (books.isEmpty()) {
            Text("这一栏暂时没有内容。", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS)) {
                items(books, key = { it.id }) { book ->
                    BookPosterCard(book = book, onClick = { onOpenBook(book) })
                }
            }
        }
    }
}

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

/** 分区标题行：左侧标题，右侧可选操作。 */
@Composable
private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
        trailing?.invoke()
    }
}

/** 横向书卡：封面 + 书名 + 作者，点击进入详情。 */
@Composable
private fun BookPosterCard(book: SearchBook, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .width(POSTER_WIDTH)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .pressableScale(interaction)
            .padding(vertical = UiDimens.spaceXS),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_RATIO)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            CoverImage(
                url = book.coverUrl,
                contentDescription = book.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(POSTER_RATIO),
                targetWidthDp = POSTER_WIDTH.value.toInt(),
            )
        }
        Text(book.title, fontSize = UiDimens.caption, maxLines = 2)
        if (book.author.isNotBlank()) {
            Text(book.author, fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 1)
        }
    }
}

/** 离线/空态：图标位用标题替代，保持 MiuiX 风格且不引 Material 的 `EmptyPage`。 */
@Composable
private fun ExploreEmptyState(title: String, description: String, actionText: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(UiDimens.spaceL),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = UiDimens.section, fontWeight = FontWeight.Bold)
            Text(description, fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            TextButton(text = actionText, onClick = onAction, modifier = Modifier.heightIn(min = UiDimens.touchMin))
        }
    }
}

private val POSTER_WIDTH = 132.dp

/** 竖版封面比例 3:4。 */
private const val POSTER_RATIO = 3f / 4f
