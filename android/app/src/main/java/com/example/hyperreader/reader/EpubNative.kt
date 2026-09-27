package com.example.hyperreader.reader

/**
 * Rust 解析核心（`rust/epub-core` → `libepub_core.so`）的 JNI 入口。
 *
 * 职责边界：Rust 只做**结构解析**（container/OPF/nav/NCX + 读出章节 HTML），
 * HTML → 内容块仍走 Kotlin Jsoup（`parseBlocks`），保证两条路径行为一致。
 *
 * **native 是加速路径，不是单点依赖**：
 * - so 缺失（装载失败）→ [available] = false，全部走 legacy zip+Jsoup
 * - `parse` 返回 null（结构解析失败、超限、JSON 序列化失败）→ 调用方回退 legacy
 *
 * 对应 Rust：`Java_com_example_hyperreader_reader_EpubNative_parse`。
 */
object EpubNative {
    val available: Boolean = try {
        System.loadLibrary("epub_core")
        true
    } catch (_: Throwable) {
        false
    }

    /** @return JSON（`{"packageDir", "title", "author", "language", "chapters":[…]}`），失败返回 null。 */
    external fun parse(path: String): String?
}

/** [EpubNative.parse] 返回 JSON 的 Kotlin 映射（字段名与 Rust serde 序列化一一对应）。 */
@kotlinx.serialization.Serializable
internal data class NativeEpubJson(
    val packageDir: String,
    val title: String = "EPUB 阅读",
    val author: String = "",
    val language: String = "zh-CN",
    val chapters: List<NativeChapterJson> = emptyList(),
)

@kotlinx.serialization.Serializable
internal data class NativeChapterJson(
    val id: String,
    val title: String,
    val href: String,
    val html: String,
)
