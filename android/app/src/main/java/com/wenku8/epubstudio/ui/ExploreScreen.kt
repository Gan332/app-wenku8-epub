package com.wenku8.epubstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wenku8.epubstudio.core.ExplorePage
import com.wenku8.epubstudio.model.SearchBook
import com.wenku8.epubstudio.model.SearchField
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private sealed interface ExploreListItem {
    val key: String
    data class Header(val title: String) : ExploreListItem { override val key = "header-$title" }
    data class Book(val book: SearchBook) : ExploreListItem { override val key = "book-${book.id}" }
}

@Composable
fun ExploreScreen(state: StudioUiState, viewModel: StudioViewModel, onLogin: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("探索", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("数据源：Wenku8 轻小说文库", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        if (!state.loggedIn) {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("登录后可搜索和浏览更多分类", fontSize = 14.sp)
                    TextButton(text = "登录", onClick = onLogin)
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(viewModel.explorePages) { page ->
                val selected = state.exploreRows.firstOrNull()?.title == page.title
                TextButton(text = page.title, onClick = { viewModel.loadExplore(page) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(text = "按书名", onClick = { viewModel.setSearchField(SearchField.TITLE) })
            TextButton(text = "按作者", onClick = { viewModel.setSearchField(SearchField.AUTHOR) })
        }
        TextField(
            value = state.searchQuery,
            onValueChange = viewModel::setSearchQuery,
            label = if (state.searchField == SearchField.TITLE) "搜索书名" else "搜索作者",
            useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = viewModel::searchBooks, enabled = !state.searchBusy && state.searchQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (state.searchBusy) "搜索中…" else "搜索") }
        if (state.searchHistory.isNotEmpty()) {
            Text("最近搜索", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.searchHistory.take(8)) { keyword -> TextButton(text = keyword, onClick = { viewModel.setSearchQuery(keyword) }) }
            }
        }
        state.exploreMessage?.let { MessageCard(it) }
        state.searchMessage?.let { MessageCard(it) }
        if (state.exploreBusy) Text("正在加载探索数据…", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        if (!state.exploreBusy && state.exploreRows.isEmpty() && state.searchResults.isEmpty()) {
            Button(onClick = { viewModel.loadExplore(viewModel.explorePages.first()) }, modifier = Modifier.fillMaxWidth()) { Text("加载今日更新") }
        }
        val exploreItems = buildList {
            state.exploreRows.forEach { row ->
                add(ExploreListItem.Header(row.title))
                row.books.forEach { add(ExploreListItem.Book(it)) }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.searchResults, key = { "search-${it.id}" }) { book ->
                ExploreBookCard(book, onOpen = { viewModel.openSearchBook(book) }, onAdd = { viewModel.addSearchToShelf(book) })
            }
            items(exploreItems, key = { "explore-${it.key}" }) { item ->
                when (item) {
                    is ExploreListItem.Header -> Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    is ExploreListItem.Book -> ExploreBookCard(item.book, onOpen = { viewModel.openSearchBook(item.book) }, onAdd = { viewModel.addSearchToShelf(item.book) })
                }
            }
        }
    }
}

@Composable
private fun ExploreBookCard(book: SearchBook, onOpen: () -> Unit, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(book.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (book.author.isNotBlank()) Text("作者：${book.author}", fontSize = 13.sp)
            val meta = buildList {
                if (book.category.isNotBlank()) add(book.category)
                if (book.status.isNotBlank()) add(book.status)
                if (book.updatedAt.isNotBlank()) add("更新 ${book.updatedAt}")
                if (book.wordCount != null) add("${book.wordCount} 字")
            }
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(text = "查看详情与目录", onClick = onOpen)
                TextButton(text = "加入书架", onClick = onAdd)
            }
        }
    }
}
