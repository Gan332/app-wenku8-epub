package com.example.hyperreader.core

import java.nio.charset.Charset

/**
 * wenku8 页面编码判定（0.18.0）。
 *
 * 源站声明 `gb2312` / `gbk`，实际输出 **GB18030**——LNR 早已踩过这个坑并留了注释
 * （issue #485）：`• ・ 〜` 等字符不在 GBK 字符集内，会以 GB18030 独有的 4 字节序列传输，
 * 按 GBK 解码会碎成乱码。GB18030 是 GBK 的严格超集，原本能解出的内容不受影响。
 *
 * 因此：**凡声明 gb2312 / gbk / 未声明，一律按 GB18030 解码**；只有明确声明 UTF-8 才用 UTF-8。
 */
object Wenku8Encoding {
    private val CHARSET_IN_TEXT = Regex("charset\\s*=\\s*[\"']?([^;\\s\"'/>]+)", RegexOption.IGNORE_CASE)

    /** 响应头 Content-Type 或 HTML 前 4KB 里声明的字符集（小写）。 */
    fun declaredCharset(contentType: String, headSnippet: String): String? =
        CHARSET_IN_TEXT.find("$contentType\n$headSnippet")?.groupValues?.get(1)?.lowercase()?.trim()?.ifBlank { null }

    /** 归一化：gb2312/gbk/缺省 → GB18030；其余按声明。 */
    fun normalize(declared: String?): String = when (declared?.lowercase()?.trim()) {
        null, "", "gb2312", "gbk", "gb18030", "x-gbk" -> "GB18030"
        else -> declared
    }

    /** 解码页面字节；`declared` 来自 [declaredCharset]，可空。 */
    fun decode(bytes: ByteArray, declared: String?): String {
        val charset = normalize(declared)
        return String(bytes, runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8))
    }
}