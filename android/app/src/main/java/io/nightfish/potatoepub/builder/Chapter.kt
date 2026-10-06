package io.nightfish.potatoepub.builder

import org.dom4j.Document

class Chapter(
    val title: String,
    val chapterContent: Document?,
    val chapters: List<Chapter>?,
    /**
     * 新增参数：显式章节 id。为 null 时沿用上游的 hash 派生公式（行为不变）。
     *
     * 上游公式由 `chapterContent.hashCode() + title.hashCode()` 派生，
     * 标题与内容都相同的两个章节会拿到同一个 id —— 于是 manifest 与 spine 出现重复条目、
     * `documents` map 后写覆盖先写，**成品里少一章**。调用方需要稳定且唯一的 id 时
     * 传本参数（例如按序号 `chapter_1`、`chapter_2`）。
     */
    val idOverride: String? = null,
) {
    val id: String = idOverride ?: ("chapter_" + (if (chapters != null) (chapters.first().id + title.hashCode()).hashCode() else (chapterContent.hashCode() + title.hashCode())).hashCode())

    constructor(title: String, chapterContent: Document) : this(
        title,
        chapterContent,
        chapters = null
    )

    constructor(title: String, chapters: List<Chapter>) : this(title, null, chapters)
}