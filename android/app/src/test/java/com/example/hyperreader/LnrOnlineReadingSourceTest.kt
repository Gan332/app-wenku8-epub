package com.example.hyperreader

import com.example.hyperreader.model.Chapter
import com.example.hyperreader.model.ContentBlock
import com.example.hyperreader.reader.toParsedChapter
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LNR 书源版的组件 JSON → 本工程 [com.example.hyperreader.model.ParsedChapter] 映射。
 *
 * 纯 JVM 测试，不触网：书源接口只负责取 JSON，阅读器只依赖映射后的段落与插图。
 */
class LnrOnlineReadingSourceTest {

    private val chapter = Chapter(
        id = "100",
        title = "第一章",
        url = "https://www.wenku8.net/novel/2/2835/100.htm",
        volume = "第一卷",
        order = 1,
    )

    private fun paragraph(text: String) = buildJsonObject {
        put("id", "lightnovelreader:paragraph")
        putJsonObject("data") {
            putJsonObject("paragraph") {
                putJsonArray("textNodes") {
                    addJsonObject { put("text", text) }
                }
            }
        }
    }

    private fun image(uri: String) = buildJsonObject {
        put("id", "lightnovelreader:image")
        putJsonObject("data") { put("uri", uri) }
    }

    @Test
    fun paragraphsAndImagesMapToContentBlocksInOrder() {
        val content = ChapterContent(
            id = "100",
            title = "",
            content = buildJsonObject {
                putJsonArray("components") {
                    add(paragraph("第一段正文。"))
                    add(image("https://img.wenku8.com/image/2/2835/2835_001.jpg"))
                    add(paragraph("第二段正文。"))
                    addJsonObject { put("id", "custom:unknown") }
                }
            },
        )
        val parsed = content.toParsedChapter(chapter)
        assertEquals("100", parsed.id)
        assertEquals("第一章", parsed.title) // 书源 title 为空时回退目录标题
        assertEquals("第一卷", parsed.volume)
        assertEquals(listOf("https://img.wenku8.com/image/2/2835/2835_001.jpg"), parsed.imageUrls)
        assertEquals(
            listOf(
                ContentBlock.Text("第一段正文。"),
                ContentBlock.Image(0),
                ContentBlock.Text("第二段正文。"),
            ),
            parsed.blocks,
        )
        assertTrue(parsed.textLength > 0)
    }

    @Test
    fun blankParagraphsAreDroppedAndUnknownComponentsAreIgnored() {
        val content = ChapterContent(
            id = "100",
            title = "书源标题",
            content = buildJsonObject {
                putJsonArray("components") {
                    add(paragraph("   "))
                    addJsonObject {
                        put("id", "lightnovelreader:paragraph")
                        putJsonObject("data") { putJsonObject("paragraph") { putJsonArray("textNodes") {} } }
                    }
                }
            },
        )
        val parsed = content.toParsedChapter(chapter)
        assertEquals("书源标题", parsed.title)
        assertTrue(parsed.blocks.isEmpty())
        assertEquals(0, parsed.textLength)
    }
}
