package com.example.hyperreader.epub

import com.example.hyperreader.core.Wenku8Exception
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB 容器写出：只关心 ZIP 细节，不参与内容生成。
 *
 * `mimetype` 必须是**第一个条目且不压缩**（EPUB 规范的硬要求），因此单独开
 * [writeMimetype]，让编排层不必记住 STORED/CRC 这些字段。
 */
internal class EpubArchiveWriter(private val zip: ZipOutputStream) {

    fun writeMimetype() {
        writeStored(MIMETYPE, "application/epub+zip".toByteArray(StandardCharsets.UTF_8))
    }

    fun writeStored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun writeText(name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    fun writeFile(name: String, source: File) {
        if (!source.exists()) {
            throw Wenku8Exception("图片缓存不存在：${source.name}", "IMAGE_CACHE_MISSING")
        }
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(source).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        const val MIMETYPE = "mimetype"
    }
}
