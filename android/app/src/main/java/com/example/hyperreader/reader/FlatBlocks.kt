package com.example.hyperreader.reader

/**
 * 全书摊平后的一个内容块。
 *
 * 之前上下滚动模式是**每章一个 LazyColumn**，翻到章节末尾会整块重建、
 * 出现明显跳变，大文件滚动也卡。摊平成一个列表后：
 * - 章节之间连续滚动，天然「无缝」
 * - 只构建可见项，大文件滚动更顺
 * - 段落下标由顺序推导，不再需要组合期的可变计数器
 *
 * 只持有 block 的引用，不复制内容，内存增量可忽略。
 */
data class FlatBlock(
    val key: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val isChapterStart: Boolean,
    /** 段落在**本章内**的下标（每章从 0 重新计数）；非段落块为 -1。 */
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

/**
 * 断点恢复：在摊平列表中定位「第 [chapterIndex] 章 · 第 [paragraphIndex] 段」。
 *
 * - 章不存在 → null（调用方不动）
 * - 段落 ≤ 0 或该章内不存在该段落 → 章首
 *
 * 纯函数。恢复流程曾被初始 snapshotFlow 的竞态破坏（跳回第 0 章、段落覆盖成 0），
 * 定位语义在这里单独锁定并测试。
 */
fun resumeTargetIndex(flat: List<FlatBlock>, chapterIndex: Int, paragraphIndex: Int): Int? {
    val chapterStart = flat.indexOfFirst { it.chapterIndex == chapterIndex }
    if (chapterStart < 0) return null
    if (paragraphIndex <= 0) return chapterStart
    val exact = flat.indexOfFirst { it.chapterIndex == chapterIndex && it.paragraphIndex == paragraphIndex }
    return if (exact >= 0) exact else chapterStart
}

/**
 * 断点恢复（翻页模式）：第 [paragraphIndex] 个段落在 [ReaderChapter.blocks] 中的下标。
 *
 * 段落 ≤ 0 或超出本章范围 → 0（章首）。
 */
fun paragraphItemIndex(chapter: ReaderChapter, paragraphIndex: Int): Int {
    if (paragraphIndex <= 0) return 0
    var seen = 0
    chapter.blocks.forEachIndexed { index, block ->
        if (block is ReaderBlock.Paragraph) {
            if (seen == paragraphIndex) return index
            seen++
        }
    }
    return 0
}
