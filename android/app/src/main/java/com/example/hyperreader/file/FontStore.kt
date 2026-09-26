package com.example.hyperreader.file

import android.content.Context
import android.net.Uri
import java.io.File

class FontStore(private val context: Context) {
    fun import(uri: Uri): String? = runCatching {
        val directory = File(context.filesDir, "fonts").apply { mkdirs() }
        val extension = when (context.contentResolver.getType(uri)) {
            "font/otf" -> "otf"
            else -> "ttf"
        }
        val target = File(directory, "${uri.toString().hashCode().toUInt().toString(16)}.$extension")
        if (!target.exists()) context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } } ?: return null
        target.absolutePath
    }.getOrNull()

    fun file(path: String?): File? = path?.let(::File)?.takeIf { it.isFile }
}
