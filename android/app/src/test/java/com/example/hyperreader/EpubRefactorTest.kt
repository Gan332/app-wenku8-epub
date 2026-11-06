package com.example.hyperreader

import com.example.hyperreader.epub.EpubBuilder
import com.example.hyperreader.epub.EpubChapterResolver
import com.example.hyperreader.epub.EpubDocumentWriter
import com.example.hyperreader.epub.EpubPackageBuilder
import com.example.hyperreader.model.Book
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.model.DownloadedImage
import com.example.hyperreader.model.ParsedChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 自研导出引擎拆分后的结构测试：转义、manifest/spine 对应、临时文件清理。
 *
 * 这些点原先只能通过「落盘 → 解压 → 读字符串」间接覆盖；拆出纯函数后可以直接断言，
 * 失败信息也更接近出错的那一步。
 */
class EpubRefactorTest {

    private fun book(title: String = "测试书") = Book(
        title = title,
        author = "作者",
        sourceUrl = "https://www.wenku8.net/book/1.htm",
        bookUrl = "https://www.wenku8.net/book/1.htm",
    )

    private fun chapter(id: String, title: String, blocks: List<ContentBlock>) = ParsedChapter(
        id = id,
        title = title,
        volume = "正文",
        order = 1,
        sourceUrl = "https://www.wenku8.net/novel/2/1/$id.html",
        blocks = blocks,
    )

    @Test
    fun escapesEveryXmlSpecialCharacter() {
        assertEquals(
            "a&amp;b&lt;c&gt;d&quot;e&apos;f",
            EpubDocumentWriter.escape("a&b<c>d\"e'f"),
        )
    }

    @Test
    fun manifestAndSpineCoverEveryChapterInOrder() {
        val resolved = EpubChapterResolver.resolve(
            listOf(
                chapter("c1", "第一章", listOf(ContentBlock.Text("甲"))),
                chapter("c2", "第二章", listOf(ContentBlock.Text("乙"))),
            ),
            emptyList(),
        )
        val manifest = EpubPackageBuilder.manifest(resolved, cover = null, images = emptyList())
        val spine = EpubPackageBuilder.spine(resolved)

        // 固定结构在前，章节按序号接在后面
        assertEquals(listOf("nav", "ncx", "style", "title-page"), manifest.take(4).map { it.id })
        assertEquals(listOf("title-page", "chapter-1", "chapter-2"), spine)

        val manifestIds = manifest.map { it.id }.toSet()
        assertTrue("spine 里每个 idref 都必须出现在 manifest 中", spine.all { it in manifestIds })
        assertTrue(manifest.any { it.href == "text/chapter-0001.xhtml" })
        assertTrue(manifest.any { it.href == "text/chapter-0002.xhtml" })
    }

    @Test
    fun duplicateTitledChaptersStillGetDistinctFiles() {
        val resolved = EpubChapterResolver.resolve(
            listOf(
                chapter("c1", "同名章节", listOf(ContentBlock.Text("相同正文"))),
                chapter("c2", "同名章节", listOf(ContentBlock.Text("相同正文"))),
            ),
            emptyList(),
        )
        assertEquals(2, resolved.map { it.fileName }.toSet().size)
    }

    @Test
    fun failedImageLeavesNoTemporaryFile() {
        val dir = Files.createTempDirectory("epub-tmp-cleanup").toFile()
        val output = File(dir, "book.epub")
        val missing = DownloadedImage(
            chapterId = "c1",
            sourceId = "c1",
            chapterIndex = 0,
            globalIndex = 0,
            fileName = "missing.png",
            manifestId = "img-1",
            mime = "image/png",
            localPath = File(dir, "does-not-exist.png").absolutePath,
            ext = "png",
            bytes = 0,
        )
        try {
            EpubBuilder().build(
                book(),
                listOf(chapter("c1", "插图", listOf(ContentBlock.Image(0)))),
                listOf(missing),
                null,
                output,
            )
            fail("缺图应中断导出")
        } catch (_: Exception) {
        }

        assertFalse("失败后不得留下半截临时包", File(dir, "book.epub.tmp").exists())
        assertFalse("失败后不得留下半截成品", output.exists())
    }
}
