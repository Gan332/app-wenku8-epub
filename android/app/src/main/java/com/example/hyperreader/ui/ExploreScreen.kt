package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.hyperreader.MessageCard
import com.example.hyperreader.core.ExplorePage
import com.example.hyperreader.model.SearchBook
import com.example.hyperreader.model.SearchField
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
fun ExploreScreen(state: StudioUiState, viewModel: StudioViewModel, onLogin: () -> Unit, onGoToCatalog: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("探索", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("数据源：Wenku8 轻小说文库 · 公开页面，无需登录", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val updated = state.catalogUpdatedAt.takeIf { it > 0 }
                    ?.let { "上次更新 ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" }
                    ?: "尚未更新"
                Text("本地书目 ${state.catalogSize} 本 · $updated", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("搜索与浏览均基于本地缓存，断网也能用。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
                if (state.catalogLoading) {
                    val progress = state.catalogProgress
                    Text("正在抓取 ${progress?.first ?: 0} / ${progress?.second ?: 0}", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                }
                if (state.catalogSize == 0) {
                    TextButton(text = "前往设置更新书目", onClick = onGoToCatalog, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (!state.loggedIn) {
            Text("无需登录即可搜索；登录后可在设置页使用站内搜索作为补充。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 12.sp)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(viewModel.explorePages) { page ->
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
        Button(onClick = viewModel::searchLocal, enabled = state.searchQuery.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("搜索本地书目") }
        if (state.searchHistory.isNotEmpty()) {
            Text("最近搜索", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.searchHistory.take(8)) { keyword -> TextButton(text = keyword, onClick = { viewModel.setSearchQuery(keyword) }) }
            }
        }
        state.exploreMessage?.let { MessageCard(it) }
        state.searchMessage?.let { MessageCard(it) }
        if (state.exploreBusy) Text("正在加载公开榜单…", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        if (!state.exploreBusy && state.exploreRows.isEmpty() && state.localResults.isEmpty() && state.catalogSize == 0) {
            Text("本地书目为空，点击「更新书目缓存」抓取公开榜单。", color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f), fontSize = 13.sp)
        }
        if (state.localResults.isNotEmpty()) Text("本地搜索结果 ${state.localResults.size} 条", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
        val localItems = state.localResults.map { ExploreListItem.Book(it.toSearchBook()) }
        val exploreItems = buildList {
            state.exploreRows.forEach { row ->
                add(ExploreListItem.Header(row.title))
                row.books.forEach { add(ExploreListItem.Book(it)) }
            }
        }
        // weight(1f) 让列表拿到剩余高度并自行滚动；否则内容被底部导航截断且无法滑动
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(localItems, key = { "local-${it.key}" }) { item ->
                (item as? ExploreListItem.Book)?.let { book ->
                    ExploreBookCard(book.book, onOpen = { viewModel.openSearchBook(book.book) }, onAdd = { viewModel.addSearchToShelf(book.book) })
                }
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
