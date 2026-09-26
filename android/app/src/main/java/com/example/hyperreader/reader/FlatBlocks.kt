package com.example.hyperreader.reader

/**
 * 全书摊平后的一个内容块。
 *
 * 之前上下滚动模式是**每章一个 LazyColumn**，翻到章节末尾会整块重建、
 * 出现明显跳变，大文件滚动也卡。摊平成一个列表后：
 * - 章节之间连续滚动，天然「无缝」
 * - 只构建可见项，大文件滚动更顺
 * - 段落下标全局唯一，不再需要组合期的可变计数器
 *
 * 只持有 block 的引用，不复制内容，内存增量可忽略。
 */
data class FlatBlock(
    val key: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val isChapterStart: Boolean,
    /** 段落在**本章内**的下标；非段落块为 -1。 */
    val paragraphIndex: Int,
    val block: ReaderBlock,
)

fun flattenBook(book: ReaderBook): List<FlatBlock> = buildList {
    book.chapters.forEachIndexed { chapterIndex, chapter ->
        var paragraph = 0
        chapter.blocks.forEachIndexed { blockIndex, block ->
            add(
                FlatBlock(
                    key = "${chapter.id}#$blockIndex",
                    chapterIndex = chapterIndex,
                    chapterTitle = chapter.title,
                    isChapterStart = blockIndex == 0,
                    paragraphIndex = if (block is ReaderBlock.Paragraph) paragraph else -1,
                    block = block,
                ),
            )
            if (block is ReaderBlock.Paragraph) paragraph++
        }
    }
}
