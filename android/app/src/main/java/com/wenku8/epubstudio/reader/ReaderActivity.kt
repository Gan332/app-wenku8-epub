package com.wenku8.epubstudio.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.wenku8.epubstudio.file.FontStore
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ReaderActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels()
    private val epubPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val id = "local:${uri.toString().hashCode()}"
        lifecycleScope.launch {
            (application as com.wenku8.epubstudio.Wenku8Application).bookshelfRepository.add(
                com.wenku8.epubstudio.model.BookshelfEntry(
                    id = id,
                    bookId = id,
                    title = uri.lastPathSegment?.substringAfterLast('/') ?: "本地 EPUB",
                    source = com.wenku8.epubstudio.model.BookshelfSource.LOCAL_EPUB,
                    localUri = uri.toString(),
                )
            )
        }
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(EXTRA_URI, uri.toString()).putExtra(EXTRA_BOOK_ID, id))
        finish()
    }
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val path = FontStore(this).import(uri)
        if (path != null) viewModel.updateFontUri(path)
    }

    override fun onStart() {
        super.onStart()
        viewModel.startSession()
    }

    override fun onStop() {
        viewModel.stopSession()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty())
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        if (uri.scheme.isNullOrBlank()) {
            finish()
            return
        }
        viewModel.load(uri, bookId)
        setContent {
            com.wenku8.epubstudio.ui.AppMiuixTheme {
                ReaderScreen(
                    viewModel = viewModel,
                    onImportFont = { fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")) },
                    onImportEpub = { epubPicker.launch(arrayOf("application/epub+zip", "application/octet-stream", "application/zip", "*/*")) },
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_URI = "epub_uri"
        const val EXTRA_BOOK_ID = "book_id"
    }
}
