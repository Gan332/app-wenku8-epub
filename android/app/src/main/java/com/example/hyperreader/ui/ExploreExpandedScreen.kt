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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hyperreader.MessageCard
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.ui.cover.CoverImage
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
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
 */
@Composable
fun ExploreExpandedScreen(state: StudioUiState, viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val pageTitle = state.activeExplorePage?.title.orEmpty().ifBlank { "榜单" }
    val books = state.exploreRows.flatMap { it.books }.distinctBy { it.id }
    Column(modifier.fillMaxSize().padding(horizontal = UiDimens.pagePadding, vertical = UiDimens.spaceS)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "‹ 返回探索", onClick = viewModel::closeExploreExpanded)
            Text(
                pageTitle,
                modifier = Modifier.weight(1f).padding(horizontal = UiDimens.spaceS),
                fontSize = UiDimens.section,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            TextButton(text = if (state.exploreBusy) "刷新中…" else "刷新", onClick = viewModel::refreshExplore, enabled = !state.exploreBusy)
        }
        state.exploreMessage?.let { message -> MessageCard(message) }
        when {
            state.exploreBusy && books.isEmpty() -> Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin).padding(top = UiDimens.spaceL),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            ) {
                CircularProgressIndicator(size = 22.dp)
                Text("正在加载…", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            }

            books.isEmpty() -> Text(
                "这一榜暂时没有内容。",
                modifier = Modifier.padding(top = UiDimens.spaceL),
                fontSize = UiDimens.caption,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = EXPANDED_CELL_MIN),
                contentPadding = PaddingValues(vertical = UiDimens.spaceS),
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
            ) {
                items(books, key = { it.id }) { book ->
                    ExpandedBookCell(book = book, onClick = { viewModel.openSearchBook(book) })
                }
            }
        }
    }
}

/** 展开页的网格单元：封面 + 书名。 */
@Composable
private fun ExpandedBookCell(book: SearchBook, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick).pressableScale(interaction),
        verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            CoverImage(
                url = book.coverUrl,
                contentDescription = book.title,
                modifier = Modifier.fillMaxWidth(),
                targetWidthDp = 180,
            )
        }
        Text(book.title, fontSize = UiDimens.caption, maxLines = 2)
    }
}

/** 网格最小单元宽度：按屏幕宽度自适应出列数。 */
private val EXPANDED_CELL_MIN = 132.dp
