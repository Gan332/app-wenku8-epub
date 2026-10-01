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

/** 阅读器内添加的书签 */
@Serializable
data class BookmarkEntity(
    val id: Long = 0,
    val bookId: Long,
    val pageIndex: Int,
    val createdAt: Long = 0,
)
