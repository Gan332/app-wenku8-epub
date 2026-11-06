package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.hyperreader.MessageCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 同作者作品页（0.19.0-alpha05 修复「点了没反应」）。
 *
 * 之前「同作者」只把作品写进本地书目索引，界面既不跳转也不展示，
 * 用户看到的就是按钮无效。现在它是一条独立的全屏覆盖页（`ShellPage.AUTHOR`）：
 *
 * - 先出书名占位，再逐本补全封面/字数（见 `CatalogRepository.expandAuthor`）；
 * - 点书进详情前先关掉本页，避免「作者页」与「详情」两层抢显示；
 * - 页名只由顶栏给（`PageChrome`），这里不重复画大标题。
 */
@Composable
fun AuthorWorksScreen(state: StudioUiState, viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val books = state.authorWorksBooks
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize().padding(vertical = UiDimens.spaceS)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (state.authorWorksLoading) "正在获取…" else "共 ${books.size} 部作品",
                fontSize = UiDimens.caption,
                color = secondaryTextColor(),
            )
            TextButton(
                text = if (state.authorWorksLoading) "刷新中…" else "刷新",
                onClick = viewModel::refreshAuthorWorks,
                enabled = !state.authorWorksLoading && state.authorWorksAnchorId.isNotBlank(),
            )
        }

        state.authorWorksMessage?.let { message -> MessageCard(message) }

        when {
            state.authorWorksLoading && books.isEmpty() -> LoadingBlock("正在获取同作者作品…")

            // 失败原因已经由 MessageCard 显示，不要再叠一个「没有作品」的空态
            books.isEmpty() && state.authorWorksMessage != null -> Unit

            books.isEmpty() -> EmptyState(
                title = "没有找到作品",
                description = "源站没有收录这位作者的公开作品。",
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = UiDimens.spaceS),
                verticalArrangement = Arrangement.spacedBy(UiDimens.spaceM),
            ) {
                items(books, key = { it.id }) { book ->
                    SearchResultCard(
                        result = book,
                        onOpen = {
                            // 先关作者页再进详情：ShellPage 里作者页优先级更高，
                            // 不关的话点书后仍停在作者页。
                            viewModel.closeAuthorWorks()
                            viewModel.openSearchBook(book)
                        },
                    )
                }
            }
        }
    }
}
