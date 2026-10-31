package com.example.hyperreader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import com.example.hyperreader.MessageCard
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全屏榜单展开页（0.18.0，对应 LNR 的 `ExpandedPageScreen`）。
 *
 * 展示当前榜单（[StudioUiState.activeExplorePage]）的全部行，网格排布书卡；
 * 顶部提供返回与刷新。**刷新用按钮而非下拉手势**——MiuiX 0.9.4 其实自带 `PullToRefresh`
 * （探索首页已在用），但本页是「展开某榜单」的临时视图，按钮比手势更省事，也不必处理
 * 网格滚动与下拉的冲突。
 *
 * 顶栏对齐 LNR：返回是图标按钮（不是文字「‹ 返回探索」）、标题是页内大标题
 * （`displayLarge` + W600），与探索首页、书架页的页内标题同一套形状；
 * 网格单元的封面比例与圆角取自横排书卡规格（[LnrDimens.exploreCoverWidth]）。
 */
@Composable
fun ExploreExpandedScreen(state: StudioUiState, viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val books = state.exploreRows.flatMap { it.books }.distinctBy { it.id }
    // 左右内边距由宿主给一次（StudioApp 的 Column 已 padding(pagePadding)）；这里只加页内间距。
    Column(modifier.fillMaxSize().padding(vertical = UiDimens.spaceS)) {
        // 榜单名与返回由顶栏给（PageChrome：返回图标 + `chromeTitle`，0.19.0-alpha05）；
        // 这里只留刷新动作，页内不再重复画大标题。刷新仍用按钮而非下拉手势：
        // 本页是「展开某榜单」的临时视图，按钮省事且不必处理网格滚动与下拉的冲突。
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "共 ${books.size} 本",
                fontSize = UiDimens.caption,
                color = secondaryTextColor(),
            )
            TextButton(text = if (state.exploreBusy) "刷新中…" else "刷新", onClick = viewModel::refreshExplore, enabled = !state.exploreBusy)
        }
        state.exploreMessage?.let { message -> MessageCard(message) }
        when {
            state.exploreBusy && books.isEmpty() -> LoadingBlock("正在加载…")

            books.isEmpty() -> EmptyState(title = "这一榜暂时没有内容。")

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = LnrDimens.exploreExpandedCellMin),
                contentPadding = PaddingValues(vertical = UiDimens.spaceS),
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceL),
            ) {
                items(books, key = { it.id }) { book ->
                    ExpandedBookCell(book = book, onClick = { viewModel.openSearchBook(book) })
                }
            }
        }
    }
}

/**
 * 展开页的网格单元：封面 + 书名。
 *
 * 封面的比例与圆角与探索首页的横排书卡一致（98:138 / 6dp），标题同样预留两行高度，
 * 于是同一行的单元格等高，往下滚时不会因为标题长短而高低不齐。
 */
@Composable
private fun ExpandedBookCell(book: SearchBook, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val titleHeight = with(LocalDensity.current) { (UiDimens.caption * LnrDimens.exploreTitleHeightFactor).toDp() }
    Column(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick).pressableScale(interaction),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(UiDimens.exploreCoverWidth / UiDimens.exploreCoverHeight)
                .clip(RoundedCornerShape(UiDimens.panelCorner))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(UiDimens.spaceXXS),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(UiDimens.panelCorner - UiDimens.spaceXXS))
                    .background(MiuixTheme.colorScheme.surface),
            ) {
                CoverImage(
                    url = book.coverUrl,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    targetWidthDp = EXPANDED_COVER_TARGET_WIDTH_DP,
                )
            }
        }
        Text(
            book.title,
            modifier = Modifier.height(titleHeight).wrapContentHeight(Alignment.Top),
            fontSize = UiDimens.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 下采样目标宽度（dp）：网格单元比横排书卡大，取 180dp 覆盖常见屏宽的两列布局。 */
private const val EXPANDED_COVER_TARGET_WIDTH_DP = 180
