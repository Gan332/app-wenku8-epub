package com.example.hyperreader.epub

import org.dom4j.Element
import org.jsoup.Jsoup
import org.jsoup.nodes.Element as JsoupElement
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * 把 [com.example.hyperreader.model.ContentBlock.Rich] 的行内 HTML 片段
 * 转换成 dom4j 元素节点，灌进一个 `<p>` 里。
 *
 * ## 为什么放在本工程而不是 vendored 模块
 *
 * potatoepub 是整体 vendored 进来的第三方代码，`THIRD_PARTY_NOTICES.md` 明确记着
 * 它「纯 JVM（只依赖 dom4j）」。行内 HTML 的解析需要 Jsoup，把它加进 vendored 模块
 * 会破坏这条依赖声明，也让日后与上游比对多一层噪音。因此 vendored 侧只提供
 * [io.nightfish.potatoepub.builder.SimpleContentBuilder.paragraphElement] 这样的
 * 空 `<p>`，**填充逻辑留在本包**。
 *
 * ## 输入的形态
 *
 * [com.example.hyperreader.model.ContentBlock.Rich.html] 并不是原始网页 HTML，
 * 而是 `Wenku8Parser.extractRichLines` 的产物：强调标签是**字面量** `<b>`，
 * 文本节点则**已做 HTML 转义**（`&` / `<` / `>` → `&amp;` / `&lt;` / `&gt;`）。
 * 因此这里先经 Jsoup 还原一次（顺带把 `&amp;` 还原回 `&`），再写进 dom4j ——
 * dom4j 的 `addText` 会再次转义，两次转义正好抵消，正文里不会冒出 `&amp;` 这种字面量。
 *
 * ## 安全
 *
 * 白名单外的标签一律**展开**（只保留子节点内容），所有属性一律丢弃。
 * 这与解析期 `Wenku8Parser.EMPHASIS_TAGS` 的口径一致，属于纵深防御：
 * 即便上游哪天改了 sanitize 逻辑，这里也不会让任意标签或属性进入成品 EPUB。
 */
internal object RichParagraphs {

    /** 允许保留的行内强调标签；其余标签展开为纯文本。 */
    private val INLINE_TAGS = setOf("b", "strong", "i", "em", "u", "s", "sup", "sub")

    /**
     * 把 [html] 片段的节点灌进 [target]（一个新建的 `<p>`）。
     *
     * 片段为空时 [target] 保持为空段落，调用方无需兜底。
     */
    fun append(target: Element, html: String) {
        if (html.isBlank()) return
        val fragment = Jsoup.parseBodyFragment(html, "", Parser.htmlParser())
        appendChildren(fragment.body(), target)
    }

    /** 递归拷贝：文本原样 addText，白名单标签递归，其余标签只取子节点。 */
    private fun appendChildren(from: JsoupElement, to: Element) {
        for (node in from.childNodes()) {
            when (node) {
                is TextNode -> if (node.text().isNotEmpty()) to.addText(node.text())

                is JsoupElement -> {
                    val tag = node.tagName().lowercase()
                    if (tag in INLINE_TAGS) {
                        // 强调标签不带任何属性：解析期已把 style 规范化成标签本身，
                        // 保留属性等于把 class / id / href 之类重新引入正文。
                        appendChildren(node, to.addElement(tag))
                    } else {
                        // 非白名单标签展开：内容保留，标签与属性全部丢弃
                        appendChildren(node, to)
                    }
                }

                // Comment / DataNode（script、style 等）：丢弃
                else -> Unit
            }
        }
    }
}