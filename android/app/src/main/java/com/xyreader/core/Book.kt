package com.xyreader.core

import kotlinx.serialization.Serializable

/**
 * 一本漫画/书籍的库记录。
 *
 * 上游（xy-reader）用 Room `@Entity` 持久化；本工程按 AGENTS §4.3「只用 DataStore、
 * 不引入 Room」的约束，把它降级为**纯数据类**——阅读器只把它当作「怎么定位这本书」
 * 的描述符（uri / format / 进度），持久化由本工程既有的 DataStore 仓储负责。
 */
data class BookEntity(
    val id: Long = 0,
    val title: String,
    /** SAF 文档 tree/document URI，或 file:// 路径 */
    val uri: String,
    /** 所在父目录 URI（仓库内定位用，可为空） */
    val parentUri: String? = null,
    val isDirectory: Boolean = false,
    /** BookFormat.name */
    val format: String,
    val size: Long = 0,
    /** 封面缓存文件绝对路径，未生成时为 null */
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    /** 最近阅读时间 epoch ms，未读过为 null */
    val lastReadAt: Long? = null,
    val addedAt: Long = 0,
    val isFavorite: Boolean = false,
    /** 所属书架分组 id；null = 未分组 */
    val groupId: Long? = null,
    /** 所属本地仓库 id（本地扫描入库时写入） */
    val localRepoId: Long? = null,
)

/**
 * 阅读器内添加的书签。
 *
 * [bookId] 是阅读器视角的 Long id（`xyBookIdOf` 的 32 位哈希）。**它不可逆**，
 * 因此跨书的书签中心页无法反查宿主书，靠下面三个冗余字段还原归属：
 * [hostBookId]（本工程书 id）、[bookTitle] 与 [bookSource]。
 *
 * 冗余是有意的取舍：书名/来源在书签产生时冻结，之后即使书被移出书架，
 * 书签列表仍能显示可读的条目，而不是变成一堆指向不存在书籍的孤儿。
 */
@Serializable
data class BookmarkEntity(
    val id: Long = 0,
    val bookId: Long,
    val pageIndex: Int,
    val createdAt: Long = 0,
    /** 加书签时所在页的正文摘录，供跨书列表显示内容而非只有页码。 */
    val snippet: String = "",
    /** 本工程的字符串 bookId（书架条目 id / wenku8 书籍编号），跨书跳转用。 */
    val hostBookId: String = "",
    /** 书名快照。 */
    val bookTitle: String = "",
    /** 来源标记（本地 EPUB / wenku8 在线），决定点击书签时开哪个阅读器。 */
    val bookSource: String = "",
)
