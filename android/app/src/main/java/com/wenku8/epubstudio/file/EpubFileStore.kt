package com.wenku8.epubstudio.file

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

class EpubFileStore(private val context: Context) {
    fun save(source: File, fileName: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) return saveMediaStore(source, fileName)
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val directory = File(root, "EPUB").apply { mkdirs() }
        val target = File(directory, safeName(fileName))
        source.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    fun share(uri: Uri, fileName: String) {
        val shareUri = if (uri.scheme == "content") uri else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(uri.path ?: return))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/epub+zip"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 EPUB"))
    }

    private fun saveMediaStore(source: File, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName(fileName))
            put(MediaStore.MediaColumns.MIME_TYPE, "application/epub+zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/EPUB")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建 EPUB 文件")
        try {
            resolver.openOutputStream(target).use { output -> requireNotNull(output); source.inputStream().use { it.copyTo(output) } }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return target
        } catch (error: Throwable) {
            resolver.delete(target, null, null)
            throw error
        }
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._\\-\u4e00-\u9fff]"), "_").ifBlank { "book.epub" }
}
