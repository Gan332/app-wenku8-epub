package com.wenku8.epubstudio.file

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File

@CapacitorPlugin(name = "EpubFile")
class EpubFilePlugin : Plugin() {
    @PluginMethod
    fun save(call: PluginCall) {
        val sourceUri = call.getString("sourceUri")
        val fileName = safeFileName(call.getString("fileName") ?: "book.epub")
        if (sourceUri.isNullOrBlank()) {
            call.reject("缺少 EPUB 文件。", "EPUB_NOT_READY")
            return
        }
        try {
            val result = if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(sourceUri, fileName) else saveToAppDocuments(sourceUri, fileName)
            call.resolve(JSObject().put("uri", result.first).put("bytes", result.second).put("location", if (Build.VERSION.SDK_INT >= 29) "Download/EPUB" else "应用文档/EPUB"))
        } catch (error: Throwable) {
            call.reject(error.message ?: "保存 EPUB 失败。", "EPUB_SAVE_FAILED")
        }
    }

    @PluginMethod
    fun share(call: PluginCall) {
        val sourceUri = call.getString("sourceUri")
        val fileName = safeFileName(call.getString("fileName") ?: "book.epub")
        if (sourceUri.isNullOrBlank()) {
            call.reject("缺少 EPUB 文件。", "EPUB_NOT_READY")
            return
        }
        try {
            val uri = if (sourceUri.startsWith("content://")) Uri.parse(sourceUri) else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(Uri.parse(sourceUri).path!!))
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = call.getString("mimeType") ?: "application/epub+zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享 EPUB"))
            call.resolve()
        } catch (error: Throwable) {
            call.reject(error.message ?: "分享 EPUB 失败。", "EPUB_SHARE_FAILED")
        }
    }

    private fun saveToMediaStore(sourceUri: String, fileName: String): Pair<String, Long> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/EPUB")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("无法创建 Downloads 文件。")
        try {
            resolver.openInputStream(Uri.parse(sourceUri)).use { input ->
                requireNotNull(input) { "无法读取 EPUB 文件。" }
                resolver.openOutputStream(target).use { output ->
                    requireNotNull(output) { "无法写入 Downloads 文件。" }
                    output.use { stream -> input.copyTo(stream) }
                }
            }
            val bytes = resolver.openAssetFileDescriptor(target, "r")?.use { it.length } ?: -1L
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return target.toString() to bytes
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }

    private fun saveToAppDocuments(sourceUri: String, fileName: String): Pair<String, Long> {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val directory = File(root, "EPUB")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("无法创建 EPUB 目录。")
        val target = File(directory, fileName)
        context.contentResolver.openInputStream(Uri.parse(sourceUri)).use { input ->
            requireNotNull(input) { "无法读取 EPUB 文件。" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return uri.toString() to target.length()
    }

    private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._\\-\u4e00-\u9fff]"), "_").ifBlank { "book.epub" }
}
