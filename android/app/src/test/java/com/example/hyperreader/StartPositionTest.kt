package com.example.hyperreader

import com.example.hyperreader.model.Chapter
import com.example.hyperreader.reader.StartPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 在线阅读起始位置（0.18.0）的夹具。
 *
 * 核心不变量：显式指定起始章 → 页码归零（页进度只与已打开的页轴配对，AGENTS §4.8）。
 */
class StartPositionTest {

    private val catalog = listOf(
        Chapter("10001", "第一章", "https://www.wenku8.net/novel/2/2365/10001.htm"),
        Chapter("10002", "第二章", "https://www.wenku8.net/novel/2/2365/10002.htm"),
        Chapter("10003", "第三章", "https://www.wenku8.net/novel/2/2365/10003.htm"),
    )

    @Test
    fun requestedChapterWinsAndPageResetsToZero() {
        val start = StartPosition.resolve(
            requestedChapterId = "10003",
            catalog = catalog,
            rememberedChapterId = "10001",
            rememberedPage = 42,
        )
        assertEquals("10003", start.chapterId)
        assertEquals(0, start.page)
    }

    @Test
    fun unknownRequestedChapterFallsBackToRememberedPosition() {
        val start = StartPosition.resolve(
            requestedChapterId = "99999",
            catalog = catalog,
            rememberedChapterId = "10002",
            rememberedPage = 7,
        )
        assertEquals("10002", start.chapterId)
        assertEquals(7, start.page)
    }

    @Test
    fun noRequestAndNoProgressStartsAtFirstChapter() {
        val start = StartPosition.resolve(
            requestedChapterId = null,
            catalog = catalog,
            rememberedChapterId = null,
            rememberedPage = null,
        )
        assertEquals("10001", start.chapterId)
        assertEquals(0, start.page)
    }

    @Test
    fun staleRememberedChapterFallsBackToFirstChapterWithSamePage() {
        // 源站重编目录后旧章 id 可能不存在：章回落到首章，但页码沿用（章内页轴语义不变）
        val start = StartPosition.resolve(
            requestedChapterId = null,
            catalog = catalog,
            rememberedChapterId = "88888",
            rememberedPage = 5,
        )
        assertEquals("10001", start.chapterId)
        assertEquals(5, start.page)
    }

    @Test
    fun emptyCatalogYieldsNoChapter() {
        val start = StartPosition.resolve("10001", emptyList(), null, null)
        assertNull(start.chapterId)
        assertEquals(0, start.page)
    }
}