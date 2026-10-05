package com.example.hyperreader.service

import java.io.File
import java.security.MessageDigest

/**
 * 导出专用的磁盘缓存：URL 内容寻址（sha1），正文页与插图分开存。
 *
 * 存在的意义是**重复导出零请求**：第一次导出后，同书二次导出（含
 * 「改设置重导」「上次失败重试」）全部命中缓存，秒级完成，不再受
 * 1s/请求的源站限流拖累。只服务导出链路 —— 不碰 `Wenku8HttpClient`
 * 本体，目录抓取与在线阅读行为零变化。
 *
 * 写入走「临时文件 + rename」：rename 前目标名不存在，
 * 因此读取侧不可能看到半截文件（半截 = 命中失败 = 安全地重下）。
 */
class ExportCache(root: File) {
    private val pagesDir = File(root, "pages").apply { mkdirs() }
    private val imagesDir = File(root, "images").apply { mkdirs() }

    /** 命中返回正文 HTML，未命中返回 null。 */
    fun page(url: String): String? {
        val file = File(pagesDir, key(url))
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun putPage(url: String, html: String) {
        runCatching {
            val target = File(pagesDir, key(url))
            val temp = File(pagesDir, "${key(url)}.tmp")
            temp.writeText(html, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    /**
     * 命中返回图片文件（扩展名决定 mime），未命中返回 null。
     *
     * 必须过滤掉 [IMAGE_EXTENSIONS] 之外的扩展名：`putImage` 先写 `${key}.jpg.tmp` 再 rename，
     * 进程在两步之间被杀就会留下 `.tmp` 残留。原先的 `startsWith("${key}.")` 会命中它，
     * `extension` 变成 `tmp`、`imageMimeFor` 兜底成 image/jpeg，最终 EPUB 里少一张图
     * 且**没有任何告警**——第三方阅读器按扩展名/魔数不符直接拒绝渲染。
     */
    fun image(url: String): File? {
        val prefix = "${key(url)}."
        return imagesDir.listFiles { file ->
            file.isFile && file.length() > 0L &&
                file.name.startsWith(prefix) &&
                file.extension.lowercase() in IMAGE_EXTENSIONS
        }?.firstOrNull()
    }

    /**
     * 落盘缓存并返回缓存后的文件。
     * @param ext 扩展名（jpg/png/gif/webp，来自下载期的魔数检测）
     */
    fun putImage(url: String, source: File, ext: String): File? {
        if (!source.isFile || source.length() == 0L) return null
        return runCatching {
            val target = File(imagesDir, "${key(url)}.$ext")
            if (target.isFile && target.length() > 0L) return@runCatching target
            val temp = File(imagesDir, "${key(url)}.$ext.tmp")
            source.copyTo(temp, overwrite = true)
            // 临时文件 + rename：终名在 rename 成功前不存在 → 读取侧永远不会看到半截文件
            val ok = temp.renameTo(target) || run { target.delete(); temp.renameTo(target) }
            if (ok) target else { temp.delete(); null }
        }.getOrNull()
    }

    private fun key(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) { digest.forEach { append(String.format("%02x", it)) } }
    }

    /**
     * 容量上限：按最后修改时间**新者优先保留**，超出 [maxBytes] 逐个淘汰。
     * 导出成功后调用 —— 失败时不裁剪，让重试继续命中缓存。
     */
    fun trim(maxBytes: Long) {
        runCatching {
            val files = (pagesDir.listFiles().orEmpty().toList() + imagesDir.listFiles().orEmpty().toList())
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= maxBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }
}

/**
 * 缓存图片的合法扩展名（与 [imageMimeFor] 的集合一致）。
 *
 * 只认这些，才能把 `putImage` 中途失败留下的 `.tmp` 残留排除在缓存命中之外。
 */
internal val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

/** 扩展名 → MIME（与 Wenku8HttpClient.detectImage 的集合一致）。 */
internal fun imageMimeFor(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> "image/jpeg"
}
