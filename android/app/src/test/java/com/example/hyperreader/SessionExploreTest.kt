package com.example.hyperreader

import com.example.hyperreader.core.Wenku8Exception
import com.example.hyperreader.core.Wenku8Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录墙内端点（toplist.php / tags.php）的解析夹具。
 *
 * 这些页面匿名访问会被 302 到登录页，拿不到真实 HTML，夹具按上游
 * `defaultplugin/wenku8` 的选择器结构合成：
 * - toplist.php 卡片：`#content > table > tbody > tr > td > div`
 * - tags.php 标签行：`div:nth-child(1)` 封面 + `div:nth-child(2) > b > a` 标题 + `p` 作者
 * - tags.php 标签锚点：`a[href~=tags\.php?t=...]`，t 参数为 GBK 字节的百分号编码
 */
class SessionExploreTest {

    @Test
    fun tagListParserDecodesGbkParamsAndDedupes() {
        val html = """
            <html><body><div id="content">
            <a href="tags.php?t=%c1%b5%b0%ae">恋爱</a>
            <a href="/modules/article/tags.php?t=%d0%a3%d4%b0">校园</a>
            <a href="tags.php">全部标签</a>
            <a href="tags.php?t=">空</a>
            <a href="tags.php?t=%c1%b5%b0%ae">重复锚点</a>
            <a href="tags.php?t=%b0%d9%ba%cf&amp;v=1">百合</a>
            </div></body></html>
        """.trimIndent()
        val tags = Wenku8Parser.parseTagList(html, "https://www.wenku8.net/modules/article/tags.php")
        assertEquals(listOf("恋爱", "校园", "百合"), tags)
    }

    @Test
    fun tagListParserRejectsLoginPage() {
        val html = "<html><head><title>用户登录</title></head><body><form action=\"/login.php\"></form></body></html>"
        val error = runCatching { Wenku8Parser.parseTagList(html, "https://www.wenku8.net/login.php") }.exceptionOrNull()
        assertTrue(error is Wenku8Exception)
        assertEquals("AUTH_REQUIRED", (error as Wenku8Exception).code)
    }

    @Test
    fun toplistCardSurvivesTextlessCoverAnchorFirst() {
        // 封面链接（无文字）排在标题链接之前：id 必须等到标题解析成功才占位，
        // 否则整本书会被当成重复丢掉
        val html = """
            <html><body><div id="content"><table><tbody><tr><td><div>
            <div><a href="/book/2835.htm"><img src="/images/2023/2835.jpg" alt=""></a></div>
            <div><a href="/book/2835.htm" title="测试书">测试书</a><p>作者：测试作者 / 字数：207,000字 / 更新：2026-09-20</p></div>
            </div></td></tr></tbody></table></div></body></html>
        """.trimIndent()
        val results = Wenku8Parser.parseSearchResults(html, "https://www.wenku8.net/modules/article/toplist.php?sort=lastupdate")
        val book = results.single()
        assertEquals("2835", book.id)
        assertEquals("测试书", book.title)
        assertEquals("测试作者", book.author)
        assertEquals("2026-09-20", book.updatedAt)
        assertEquals(207000L, book.wordCount)
        // 封面在兄弟 div 里，文字容器找不到时向上爬一级
        assertEquals("https://www.wenku8.net/images/2023/2835.jpg", book.coverUrl)
    }

    @Test
    fun tagsRowExtractsAuthorAndCoverFromSiblingBlocks() {
        val html = """
            <html><body><div id="content"><table><tbody>
            <tr><td>分页</td></tr>
            <tr><td><div>
            <div><a href="/book/3988.htm"><img src="/images/c/3988.jpg"></a></div>
            <div><b><a href="/book/3988.htm">あちき volt</a></b><p>连载中</p><p>作者：测试作者 / 文库：富士见</p></div>
            </div></td></tr>
            </tbody></table></div></body></html>
        """.trimIndent()
        val book = Wenku8Parser.parseSearchResults(html, "https://www.wenku8.net/modules/article/tags.php?t=%c1%b5%b0%ae").single()
        assertEquals("3988", book.id)
        assertEquals("あちき volt", book.title)
        assertEquals("测试作者", book.author)
        assertEquals("https://www.wenku8.net/images/c/3988.jpg", book.coverUrl)
    }
}
