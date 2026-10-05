package io.nightfish.potatoepub.builder

import io.nightfish.potatoepub.xml.Attribute
import io.nightfish.potatoepub.xml.XmlBuilder
import org.dom4j.Document
import org.dom4j.DocumentHelper
import org.dom4j.Element
import java.io.File

@Suppress("MemberVisibilityCanBePrivate")
class SimpleContentBuilder {
    private val _images: MutableMap<Pair<String, String>, File> = mutableMapOf()
    val images: Map<Pair<String, String>, File> get() = _images

    /** href → MIME。上游把章节插图的 mediaType 写死 `image/jpeg`，png/webp 会被 OPF 谎报。 */
    private val _imageMimes: MutableMap<String, String> = mutableMapOf()

    /** 取图片的真实 MIME，未登记时回落到上游默认的 `image/jpeg`。 */
    fun mimeOf(href: String): String = _imageMimes[href] ?: "image/jpeg"

    val document: Document = DocumentHelper.createDocument()
    val rootElement: Element = document.addElement("html", "http://www.w3.org/1999/xhtml")
    val headElement: Element = rootElement.addElement("head")
    val bodyElement: Element = rootElement.addElement("body")
    val contentElement: Element = bodyElement.addElement("div").addAttribute("id", "content")

    init {
        document.addDocType("html", "", "")
        rootElement
            .addAttribute("xmlns", "http://www.w3.org/1999/xhtml")
            .addAttribute("xmlns:epub", "http://www.idpf.org/2007/ops")
            .addAttribute("lang", "en")
            .addAttribute("xml:lang", "en")
    }

    fun title(src: String) {
        headElement.addElement("title").addText(src)
    }

    fun headline(level: Int, content: String) {
        contentElement.addElement("h$level").addText(content)
    }

    fun br() {
        contentElement.addElement("br")
    }

    fun text(content: String) {
        var result = Regex("&#([0-8]|1[1-2]|1[4-9]|2[0-9]|3[0-1]);").replace(content, "")
        result = Regex(
            "&#x(0[0-8BCEF]|1[0-9A-F]|7F|8[0-9A-F]|9[0-9A-F]|A[0-9A-F]|B[0-9A-F]|C[0-9A-F]|D[0-9A-F]|E[0-9A-F]|F[0-9A-F]);",
            RegexOption.IGNORE_CASE
        )
            .replace(result, "")
        contentElement.addText(result)
    }

    /**
     * 插入整页插图。
     *
     * [mime] 是新增参数：wenku8 上的插图有 png/gif/webp，写死 `image/jpeg` 会让 OPF
     * 谎报资源类型，部分阅读器据此判定资源损坏并不渲染该图。默认保持上游行为。
     */
    fun image(
        image: File,
        id: String = "image_${image.hashCode()}",
        src: String = "image/$id.jpg",
        mime: String = "image/jpeg"
    ) {
        _images[Pair(id, src)] = image
        _imageMimes[src] = mime
        XmlBuilder.ElementBuilder(contentElement, "div", arrayOf(Attribute("class", "div_image"))) {
            "img"(
                "border" to 0,
                "class" to "image_content",
                "src" to src
            )
        }
    }

    fun build(): Document {
        return document
    }
}