package com.xyreader.core

import kotlinx.coroutines.flow.Flow

/**
 * 阅读器所需的最小仓库契约。
 *
 * 上游（xy-reader）的 `LibraryRepository` 同时承载书库扫描、本地仓库管理、WebDAV /
 * Google Drive 远程仓库、分组、封面等**整个应用的数据层**；本工程的数据层是既有的
 * DataStore 书架 + EPUB 仓储，不需要也不应该把那套搬进来。
 *
 * 因此这里按「阅读器真正用到的能力」收窄成 8 个成员——`ReaderViewModel` /
 * `ReaderScreen` 只依赖本接口，实现由宿主阅读器侧提供，
 * 把本工程的书架、阅读进度、阅读统计接进去。
 *
 * 全部方法可在任意线程调用（实现内部切 IO）。
 */
interface ReaderRepository {

    /** 全部书籍流（阅读器用它在打开时定位目标书、并持续同步元数据） */
    val books: Flow<List<BookEntity>>

    /** 全部书签（按创建时间倒序）；UI 层按当前书 bookId 过滤 */
    val bookmarks: Flow<List<BookmarkEntity>>

    /** 阅读配置（翻页模式 / 背景 / 排版参数），DataStore 持久化 */
    val readerPrefs: Flow<ReaderPrefs>

    suspend fun setReaderPrefs(prefs: ReaderPrefs)

    /** 保存阅读进度（阅读器翻页防抖后调用） */
    suspend fun saveProgress(bookId: Long, page: Int, totalPages: Int)

    suspend fun toggleFavorite(bookId: Long)

    suspend fun addBookmark(bookId: Long, pageIndex: Int)

    suspend fun removeBookmark(bookmarkId: Long)
}
